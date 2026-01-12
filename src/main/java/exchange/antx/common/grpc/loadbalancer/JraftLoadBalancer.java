package exchange.antx.common.grpc.loadbalancer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import exchange.antx.common.grpc.GrpcConst;
import exchange.antx.proto.common.CommonProto;
import exchange.antx.proto.common.RpcMethodExtOpt;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.*;

@Slf4j
public class JraftLoadBalancer extends LoadBalancer {

    private static final Attributes.Key<AtomicReference<JraftData>> ATTR_KEY_JRAFT_DATA_REF = Attributes.Key.create("jraft-data-ref");

    @VisibleForTesting
    static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
            Attributes.Key.create("state-info");

    private final Helper helper;
    private final Map<EquivalentAddressGroup, Subchannel> subchannels =
            new HashMap<>();
    private final Random random;

    private ConnectivityState currentState;
    private RoundRobinPicker currentPicker = new EmptyPicker(EMPTY_OK);

    JraftLoadBalancer(Helper helper) {
        this.helper = checkNotNull(helper, "helper");
        this.random = new Random();
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
        Set<EquivalentAddressGroup> currentAddrs = subchannels.keySet();
        Map<EquivalentAddressGroup, EquivalentAddressGroup> latestAddrs = stripAttrs(servers);
        Set<EquivalentAddressGroup> removedAddrs = setsDifference(currentAddrs, latestAddrs.keySet());

        for (Map.Entry<EquivalentAddressGroup, EquivalentAddressGroup> latestEntry :
                latestAddrs.entrySet()) {
            EquivalentAddressGroup strippedAddressGroup = latestEntry.getKey();
            EquivalentAddressGroup originalAddressGroup = latestEntry.getValue();

            final String jraftGroupId = originalAddressGroup.getAttributes().get(GrpcConst.JRAFT_GROUP_ID_ATTR_KEY);
            final String jraftServerId = originalAddressGroup.getAttributes().get(GrpcConst.JRAFT_SERVER_ID_ATTR_KEY);
            final Long jraftLeaderTerm = originalAddressGroup.getAttributes().get(GrpcConst.JRAFT_LEADER_TERM_ATTR_KEY);
            final JraftData jraftData = jraftGroupId != null && jraftServerId != null && jraftLeaderTerm != null
                    ? new JraftData(jraftGroupId, jraftServerId, jraftLeaderTerm) : null;

            Subchannel existingSubchannel = subchannels.get(strippedAddressGroup);
            if (existingSubchannel != null) {
                // EAG's Attributes may have changed.
                AtomicReference<JraftData> jraftDataRef = existingSubchannel.getAttributes().get(ATTR_KEY_JRAFT_DATA_REF);
                if (jraftDataRef != null) {
                    jraftDataRef.set(jraftData);
                }
                existingSubchannel.updateAddresses(Collections.singletonList(originalAddressGroup));
                continue;
            }
            // Create new subchannels for new addresses.

            // NB(lukaszx0): we don't merge `attributes` with `subchannelAttr` because subchannel
            // doesn't need them. They're describing the resolved server list but we're not taking
            // any action based on this information.
            Attributes.Builder subchannelAttrs = Attributes.newBuilder()
                    // NB(lukaszx0): because attributes are immutable we can't set new value for the key
                    // after creation but since we can mutate the values we leverage that and set
                    // AtomicReference which will allow mutating state info for given channel.
                    .set(STATE_INFO, new Ref<>(ConnectivityStateInfo.forNonError(IDLE)))
                    .set(ATTR_KEY_JRAFT_DATA_REF, new AtomicReference<>(jraftData));

            final Subchannel subchannel = checkNotNull(
                    helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                            .setAddresses(originalAddressGroup)
                            .setAttributes(subchannelAttrs.build())
                            .build()),
                    "subchannel");
            subchannel.start(new SubchannelStateListener() {
                @Override
                public void onSubchannelState(ConnectivityStateInfo state) {
                    processSubchannelState(subchannel, state);
                }
            });
            subchannels.put(strippedAddressGroup, subchannel);
            subchannel.requestConnection();
        }

        ArrayList<Subchannel> removedSubchannels = new ArrayList<>();
        for (EquivalentAddressGroup addressGroup : removedAddrs) {
            removedSubchannels.add(subchannels.remove(addressGroup));
        }

        // Update the picker before shutting down the subchannels, to reduce the chance of the race
        // between picking a subchannel and shutting it down.
        updateBalancingState();

        // Shutdown removed subchannels
        for (Subchannel removedSubchannel : removedSubchannels) {
            shutdownSubchannel(removedSubchannel);
        }
    }

    @Override
    public void handleNameResolutionError(Status error) {
        if (currentState != READY) {
            updateBalancingState(TRANSIENT_FAILURE, new EmptyPicker(error));
        }
    }

    private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
        if (subchannels.get(stripAttrs(subchannel.getAddresses())) != subchannel) {
            return;
        }
        if (stateInfo.getState() == TRANSIENT_FAILURE || stateInfo.getState() == IDLE) {
            helper.refreshNameResolution();
        }
        if (stateInfo.getState() == IDLE) {
            subchannel.requestConnection();
        }
        Ref<ConnectivityStateInfo> subchannelStateRef = getSubchannelStateInfoRef(subchannel);
        if (subchannelStateRef.value.getState().equals(TRANSIENT_FAILURE)) {
            if (stateInfo.getState().equals(CONNECTING) || stateInfo.getState().equals(IDLE)) {
                return;
            }
        }
        subchannelStateRef.value = stateInfo;
        updateBalancingState();
    }

    private void shutdownSubchannel(Subchannel subchannel) {
        subchannel.shutdown();
        getSubchannelStateInfoRef(subchannel).value =
                ConnectivityStateInfo.forNonError(SHUTDOWN);
    }

    @Override
    public void shutdown() {
        for (Subchannel subchannel : getSubchannels()) {
            shutdownSubchannel(subchannel);
        }
        subchannels.clear();
    }

    private static final Status EMPTY_OK = Status.OK.withDescription("no subchannels ready");

    /**
     * Updates picker with the list of active subchannels (state == READY).
     */
    @SuppressWarnings("ReferenceEquality")
    private void updateBalancingState() {
        List<Subchannel> activeList = filterNonFailingSubchannels(getSubchannels());
        if (activeList.isEmpty()) {
            // No READY subchannels, determine aggregate state and error status
            boolean isConnecting = false;
            Status aggStatus = EMPTY_OK;
            for (Subchannel subchannel : getSubchannels()) {
                ConnectivityStateInfo stateInfo = getSubchannelStateInfoRef(subchannel).value;
                // This subchannel IDLE is not because of channel IDLE_TIMEOUT,
                // in which case LB is already shutdown.
                // RRLB will request connection immediately on subchannel IDLE.
                if (stateInfo.getState() == CONNECTING || stateInfo.getState() == IDLE) {
                    isConnecting = true;
                }
                if (aggStatus == EMPTY_OK || !aggStatus.isOk()) {
                    aggStatus = stateInfo.getStatus();
                }
            }
            updateBalancingState(isConnecting ? CONNECTING : TRANSIENT_FAILURE,
                    // If all subchannels are TRANSIENT_FAILURE, return the Status associated with
                    // an arbitrary subchannel, otherwise return OK.
                    new EmptyPicker(aggStatus));
        } else {
            // initialize the Picker to a random start index to ensure that a high frequency of Picker
            // churn does not skew subchannel selection.
            int startIndex = random.nextInt(activeList.size());
            updateBalancingState(READY, new ReadyPicker(activeList, startIndex));
        }
    }

    private void updateBalancingState(ConnectivityState state, RoundRobinPicker picker) {
        if (state != currentState || !picker.isEquivalentTo(currentPicker)) {
            helper.updateBalancingState(state, picker);
            currentState = state;
            currentPicker = picker;
        }
    }

    /**
     * Filters out non-ready subchannels.
     */
    private static List<Subchannel> filterNonFailingSubchannels(
            Collection<Subchannel> subchannels) {
        List<Subchannel> readySubchannels = new ArrayList<>(subchannels.size());
        for (Subchannel subchannel : subchannels) {
            if (isReady(subchannel)) {
                readySubchannels.add(subchannel);
            }
        }
        return readySubchannels;
    }

    /**
     * Converts list of {@link EquivalentAddressGroup} to {@link EquivalentAddressGroup} set and
     * remove all attributes. The values are the original EAGs.
     */
    private static Map<EquivalentAddressGroup, EquivalentAddressGroup> stripAttrs(
            List<EquivalentAddressGroup> groupList) {
        Map<EquivalentAddressGroup, EquivalentAddressGroup> addrs = new HashMap<>(groupList.size() * 2);
        for (EquivalentAddressGroup group : groupList) {
            addrs.put(stripAttrs(group), group);
        }
        return addrs;
    }

    private static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
        return new EquivalentAddressGroup(eag.getAddresses());
    }

    @VisibleForTesting
    Collection<Subchannel> getSubchannels() {
        return subchannels.values();
    }

    private static Ref<ConnectivityStateInfo> getSubchannelStateInfoRef(
            Subchannel subchannel) {
        return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
    }

    // package-private to avoid synthetic access
    static boolean isReady(Subchannel subchannel) {
        return getSubchannelStateInfoRef(subchannel).value.getState() == READY;
    }

    private static <T> Set<T> setsDifference(Set<T> a, Set<T> b) {
        Set<T> aCopy = new HashSet<>(a);
        aCopy.removeAll(b);
        return aCopy;
    }

    // Only subclasses are ReadyPicker or EmptyPicker
    private abstract static class RoundRobinPicker extends SubchannelPicker {
        abstract boolean isEquivalentTo(RoundRobinPicker picker);
    }

    @VisibleForTesting
    static final class ReadyPicker extends RoundRobinPicker {
        private static final AtomicIntegerFieldUpdater<ReadyPicker> indexUpdater =
                AtomicIntegerFieldUpdater.newUpdater(ReadyPicker.class, "index");

        private final List<Subchannel> list; // non-empty
        @SuppressWarnings("unused")
        private volatile int index;

        ReadyPicker(List<Subchannel> list, int startIndex) {
            Preconditions.checkArgument(!list.isEmpty(), "empty list");
            this.list = list;
            this.index = startIndex - 1;
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            final RpcMethodExtOpt rpcMethodExtOpt;
            Object schemaDescriptorObj = args.getMethodDescriptor().getSchemaDescriptor();
            if (schemaDescriptorObj instanceof ProtoMethodDescriptorSupplier) {
                rpcMethodExtOpt = ((ProtoMethodDescriptorSupplier) schemaDescriptorObj)
                        .getMethodDescriptor()
                        .getOptions()
                        .getExtension(CommonProto.rpcMethodExtOpt);
            } else {
                rpcMethodExtOpt = RpcMethodExtOpt.getDefaultInstance();
            }
            Subchannel subchannel = null;
            if (rpcMethodExtOpt.getIsWriteOperation()) {
                subchannel = leaderSubchannel();
            }
            if (subchannel == null) {
                subchannel = nextSubchannel();
            }
            return PickResult.withSubchannel(subchannel);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(ReadyPicker.class).add("list", list).toString();
        }

        private Subchannel leaderSubchannel() {
            Subchannel leaderSubchannel = null;
            long leaderTerm = 0L;
            for (Subchannel subchannel : list) {
                AtomicReference<JraftData> jraftDataRef = subchannel.getAttributes().get(ATTR_KEY_JRAFT_DATA_REF);
                JraftData jraftData = jraftDataRef != null ? jraftDataRef.get() : null;
                long tmpLeaderTerm = jraftData != null ? jraftData.getLeaderTerm() : 0L;
                if (tmpLeaderTerm > leaderTerm) {
                    leaderTerm = tmpLeaderTerm;
                    leaderSubchannel = subchannel;
                }
            }
            return leaderSubchannel;
        }

        private Subchannel nextSubchannel() {
            int size = list.size();
            int i = indexUpdater.incrementAndGet(this);
            if (i >= size) {
                int oldi = i;
                i %= size;
                indexUpdater.compareAndSet(this, oldi, i);
            }
            return list.get(i);
        }

        @VisibleForTesting
        List<Subchannel> getList() {
            return list;
        }

        @Override
        boolean isEquivalentTo(RoundRobinPicker picker) {
            if (!(picker instanceof ReadyPicker)) {
                return false;
            }
            ReadyPicker other = (ReadyPicker) picker;
            // the lists cannot contain duplicate subchannels
            return other == this
                    || (list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list));
        }
    }

    @VisibleForTesting
    static final class EmptyPicker extends RoundRobinPicker {

        private final Status status;

        EmptyPicker(@Nonnull Status status) {
            this.status = Preconditions.checkNotNull(status, "status");
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            return status.isOk() ? PickResult.withNoResult() : PickResult.withError(status);
        }

        @Override
        boolean isEquivalentTo(RoundRobinPicker picker) {
            return picker instanceof EmptyPicker && (Objects.equal(status, ((EmptyPicker) picker).status)
                    || (status.isOk() && ((EmptyPicker) picker).status.isOk()));
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(EmptyPicker.class).add("status", status).toString();
        }
    }

    /**
     * A lighter weight Reference than AtomicReference.
     */
    @VisibleForTesting
    static final class Ref<T> {
        T value;

        Ref(T value) {
            this.value = value;
        }
    }
}
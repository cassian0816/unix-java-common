/*
 * Copyright (c) 2016-2021 Michael Zhang <yidongnan@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package exchange.antx.common.grpc.nameresolver;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import exchange.antx.common.grpc.GrpcConst;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.SharedResourceHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * The DiscoveryClientNameResolver resolves the service hosts and their associated gRPC port using the channel's name
 * and spring's cloud {@link NacosNameResolver}. The ports are extracted from the {@code gRPC_port} metadata.
 *
 * @author Michael (yidongnan@gmail.com)
 * @author Daniel Theuke (daniel.theuke@heuboe.de)
 */
@Slf4j
public class NacosNameResolver extends NameResolver {

    private static final List<Instance> KEEP_PREVIOUS = null;

    private final String name;
    private final NamingService client;
    private final SynchronizationContext syncContext;
    private final Consumer<NacosNameResolver> shutdownHook;
    private final SharedResourceHolder.Resource<Executor> executorResource;
    private final boolean usingExecutorResource;

    // The field must be accessed from syncContext, although the methods on an Listener2 can be called
    // from any thread.
    private Listener2 listener;
    // Following fields must be accessed from syncContext
    private Executor executor;
    private boolean resolving;
    private List<Instance> instanceList = Lists.newArrayList();

    /**
     * Creates a new DiscoveryClientNameResolver.
     *
     * @param name             The name of the service to look up.
     * @param client           The client used to look up the service addresses.
     * @param args             The name resolver args.
     * @param executorResource The executor resource.
     * @param shutdownHook     The optional cleaner used during {@link #shutdown()}
     */
    public NacosNameResolver(final String name, final NamingService client, final Args args,
                             final SharedResourceHolder.Resource<Executor> executorResource,
                             final Consumer<NacosNameResolver> shutdownHook) {
        this.name = name;
        this.client = client;
        this.syncContext = requireNonNull(args.getSynchronizationContext(), "syncContext");
        this.shutdownHook = shutdownHook;
        this.executor = args.getOffloadExecutor();
        this.usingExecutorResource = this.executor == null;
        this.executorResource = executorResource;
    }

    /**
     * Gets the name of the service to get the instances of.
     *
     * @return The name associated with this resolver.
     */
    protected final String getName() {
        return this.name;
    }

    /**
     * Checks whether this resolver is active. E.g. {@code #start} has been called, but not {@code #shutdown()}.
     *
     * @return True, if there is a listener attached. False, otherwise.
     */
    protected final boolean isActive() {
        return this.listener != null;
    }

    @Override
    public final String getServiceAuthority() {
        return this.name;
    }

    @Override
    public void start(final Listener2 listener) {
        checkState(!isActive(), "already started");
        if (this.usingExecutorResource) {
            this.executor = SharedResourceHolder.get(this.executorResource);
        }
        this.listener = checkNotNull(listener, "listener");
        resolve();
    }

    @Override
    public void refresh() {
        checkState(isActive(), "not started");
        resolve();
    }

    /**
     * Triggers a refresh on the listener from non-grpc threads. This method can safely be called, even if the listener
     * hasn't been started yet.
     *
     * @see #refresh()
     */
    public void refreshFromExternal() {
        this.syncContext.execute(() -> {
            if (isActive()) {
                resolve();
            }
        });
    }

    /**
     * Discovers matching service instances. Can be overwritten to apply some custom filtering.
     *
     * @return A list of service instances to use.
     */
    protected List<Instance> discoverServers() throws NacosException {
        return this.client.getAllInstances(this.name);
    }

    /**
     * Extracts the gRPC server port from the given service instance. Can be overwritten for a custom port mapping.
     *
     * @param instance The instance to extract the port from.
     * @return The gRPC server port.
     * @throws IllegalArgumentException If the specified port definition couldn't be parsed.
     */
    protected int getGrpcPort(final Instance instance) {
        return instance.getPort();
    }


    /**
     * Checks whether this instance should update its connections.
     *
     * @param newInstanceList The new instances that should be compared to the stored ones.
     * @return True, if the given instance list contains different entries than the stored ones.
     */
    protected boolean needsToUpdateConnections(final List<Instance> newInstanceList) {
        if (this.instanceList.size() != newInstanceList.size()) {
            return true;
        }
        for (final Instance instance : this.instanceList) {
            final int port = getGrpcPort(instance);
            boolean isSame = false;
            for (final Instance newInstance : newInstanceList) {
                final int newPort = getGrpcPort(newInstance);
                if (newInstance.getIp().equals(instance.getIp()) && port == newPort && isMetadataEquals(instance.getMetadata(), newInstance.getMetadata())) {
                    isSame = true;
                    break;
                }
            }
            if (!isSame) {
                return true;
            }
        }
        return false;
    }

    private boolean isMetadataEquals(final Map<String, String> metadata, final Map<String, String> newMetadata) {
        return Objects.equals(metadata, newMetadata);
    }

    private void resolve() {
        log.debug("Scheduled resolve for {}", this.name);
        if (this.resolving) {
            return;
        }
        this.resolving = true;
        this.executor.execute(new Resolve(this.listener));
    }

    @Override
    public void shutdown() {
        this.listener = null;
        if (this.executor != null && this.usingExecutorResource) {
            this.executor = SharedResourceHolder.release(this.executorResource, this.executor);
        }
        this.instanceList = Lists.newArrayList();
        if (this.shutdownHook != null) {
            this.shutdownHook.accept(this);
        }
    }

    @Override
    public String toString() {
        return "DiscoveryClientNameResolver [name=" + this.name + ", discoveryClient=" + this.client + "]";
    }

    /**
     * The logic for updating the gRPC server list using a discovery client.
     */
    private final class Resolve implements Runnable {

        // The listener is stored in an extra variable to avoid NPEs if the resolver is shutdown while resolving
        private final Listener2 savedListener;

        /**
         * Creates a new Resolve that stores a snapshot of the relevant states of the resolver.
         *
         * @param listener The listener to send the results to.
         */
        Resolve(final Listener2 listener) {
            this.savedListener = requireNonNull(listener, "listener");
        }

        @Override
        public void run() {
            final AtomicReference<List<Instance>> resultContainer = new AtomicReference<>(KEEP_PREVIOUS);
            try {
                resultContainer.set(resolveInternal());
            } catch (final Exception e) {
                this.savedListener.onError(Status.UNAVAILABLE.withCause(e)
                        .withDescription("Failed to update server list for " + getName()));
                resultContainer.set(Lists.newArrayList());
            } finally {
                NacosNameResolver.this.syncContext.execute(() -> {
                    NacosNameResolver.this.resolving = false;
                    final List<Instance> result = resultContainer.get();
                    if (result != KEEP_PREVIOUS && isActive()) {
                        NacosNameResolver.this.instanceList = result;
                    }
                });
            }
        }

        /**
         * Do the actual update checks and resolving logic.
         *
         * @return The new service instance list that is used to connect to the gRPC server or null if the old ones
         * should be used.
         */
        private List<Instance> resolveInternal() throws NacosException {
            // Discover servers
            final List<Instance> newInstanceList = discoverServers();
            if (CollectionUtils.isEmpty(newInstanceList)) {
                log.error("No servers found for {}", getName());
                this.savedListener.onError(Status.UNAVAILABLE.withDescription("No servers found for " + getName()));
                return Lists.newArrayList();
            } else {
                log.debug("Got {} candidate servers for {}", newInstanceList.size(), getName());
            }

            // Check for changes
            if (!needsToUpdateConnections(newInstanceList)) {
                log.debug("Nothing has changed... skipping update for {}", getName());
                return KEEP_PREVIOUS;
            }

            // Set new servers
            log.debug("Ready to update server list for {}, instanceList {}", getName(), newInstanceList);
            this.savedListener.onResult(ResolutionResult.newBuilder()
                    .setAddresses(toTargets(newInstanceList))
                    .build());
            log.info("Done updating server list for {}, instanceList {}", getName(), newInstanceList);
            return newInstanceList;
        }

        private List<EquivalentAddressGroup> toTargets(final List<Instance> newInstanceList) {
            final List<EquivalentAddressGroup> targets = Lists.newArrayList();
            for (final Instance instance : newInstanceList) {
                targets.add(toTarget(instance));
            }
            return targets;
        }

        private EquivalentAddressGroup toTarget(final Instance instance) {
            final String host = instance.getIp();
            final int port = getGrpcPort(instance);
            final Attributes attributes = getAttributes(instance);
            log.debug("Found gRPC server {}:{} for {}", host, port, getName());
            return new EquivalentAddressGroup(new InetSocketAddress(host, port), attributes);
        }

        protected Attributes getAttributes(final Instance instance) {
            final Attributes.Builder builder = Attributes.newBuilder();
            String jraftGroupId = instance.getMetadata().get(GrpcConst.JRAFT_GROUP_ID_NAME);
            if (jraftGroupId != null) {
                builder.set(GrpcConst.JRAFT_GROUP_ID_ATTR_KEY, jraftGroupId);
            }
            String jraftServerId = instance.getMetadata().get("jraft.server-id");
            if (jraftServerId != null) {
                builder.set(GrpcConst.JRAFT_SERVER_ID_ATTR_KEY, jraftServerId);
            }
            Long jraftLeaderTerm = Longs.tryParse(Strings.nullToEmpty(instance.getMetadata().get(GrpcConst.JRAFT_LEADER_TERM_NAME)));
            if (jraftLeaderTerm != null) {
                builder.set(GrpcConst.JRAFT_LEADER_TERM_ATTR_KEY, jraftLeaderTerm);
            }
            return builder.build();
        }
    }

}

package exchange.antx.common.util;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import cosmos.auth.v1beta1.Auth;
import cosmos.auth.v1beta1.QueryGrpc;
import cosmos.auth.v1beta1.QueryOuterClass;
import cosmos.crypto.secp256k1.Keys;
import cosmos.msg.v1.Msg;
import cosmos.tx.signing.v1beta1.Signing;
import cosmos.tx.v1beta1.ServiceGrpc;
import cosmos.tx.v1beta1.ServiceOuterClass;
import cosmos.tx.v1beta1.TxOuterClass;
import org.bitcoinj.base.Bech32;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.internal.CryptoUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CosmosUtil {

    public static String pubKeyToAddress(String hrp, byte[] pubKeyCompressEncoded) {
        return Bech32.encodeBytes(Bech32.Encoding.BECH32, hrp, CryptoUtils.sha256hash160(pubKeyCompressEncoded));
    }

    public static String ecKeyToAddress(String hrp, ECKey ecKey) {
        return Bech32.encodeBytes(Bech32.Encoding.BECH32, hrp, ecKey.getPubKeyHash());
    }

    public static TxOuterClass.AuthInfo buildAuthInfo(
            List<String> signerAddressList,
            Map<String, ECKey> signerAddressToECKeyMap,
            Map<String, Long> signerAddressToSequenceMap,
            TxOuterClass.Fee fee) {
        TxOuterClass.AuthInfo.Builder authInfoBuilder = TxOuterClass.AuthInfo.newBuilder();
        for (String signerAddress : signerAddressList) {
            ECKey signerECKey = signerAddressToECKeyMap.get(signerAddress);
            if (signerECKey == null) {
                throw new RuntimeException("can not find signer ECKey for address: " + signerAddress);
            }
            Long signerSequence = signerAddressToSequenceMap.get(signerAddress);
            if (signerSequence == null) {
                throw new RuntimeException("can not find signer sequence for address: " + signerAddress);
            }
            authInfoBuilder.addSignerInfos(TxOuterClass.SignerInfo.newBuilder()
                    .setPublicKey(Any.pack(
                            Keys.PubKey.newBuilder()
                                    .setKey(ByteString.copyFrom(signerECKey.getPubKeyPoint().getEncoded(true)))
                                    .build(),
                            "/"))
                    .setModeInfo(TxOuterClass.ModeInfo.newBuilder()
                            .setSingle(TxOuterClass.ModeInfo.Single.newBuilder()
                                    .setMode(Signing.SignMode.SIGN_MODE_DIRECT)
                                    .build())
                            .build())
                    .setSequence(signerSequence)
                    .build());
        }
        return authInfoBuilder
                .setFee(fee)
                .build();
    }

    public static TxOuterClass.AuthInfo buildAuthInfoUnordered(
            List<String> signerAddressList,
            Map<String, ECKey> signerAddressToECKeyMap,
            TxOuterClass.Fee fee) {
        TxOuterClass.AuthInfo.Builder authInfoBuilder = TxOuterClass.AuthInfo.newBuilder();
        for (String signerAddress : signerAddressList) {
            ECKey signerECKey = signerAddressToECKeyMap.get(signerAddress);
            if (signerECKey == null) {
                throw new RuntimeException("can not find signer ECKey for address: " + signerAddress);
            }
            authInfoBuilder.addSignerInfos(TxOuterClass.SignerInfo.newBuilder()
                    .setPublicKey(Any.pack(
                            Keys.PubKey.newBuilder()
                                    .setKey(ByteString.copyFrom(signerECKey.getPubKeyPoint().getEncoded(true)))
                                    .build(),
                            "/"))
                    .setModeInfo(TxOuterClass.ModeInfo.newBuilder()
                            .setSingle(TxOuterClass.ModeInfo.Single.newBuilder()
                                    .setMode(Signing.SignMode.SIGN_MODE_DIRECT)
                                    .build())
                            .build())
                    .setSequence(0)
                    .build());
        }
        return authInfoBuilder
                .setFee(fee)
                .build();
    }

    public static byte[] signMessage(
            ECKey signerECKey,
            long accountNumber,
            TxOuterClass.TxBody txBody,
            TxOuterClass.AuthInfo authInfo,
            String chainId) {
        TxOuterClass.SignDoc signDoc = TxOuterClass.SignDoc.newBuilder()
                .setBodyBytes(txBody.toByteString())
                .setAuthInfoBytes(authInfo.toByteString())
                .setAccountNumber(accountNumber)
                .setChainId(chainId)
                .build();
        ECKey.ECDSASignature signature = signerECKey.sign(Sha256Hash.of(signDoc.toByteArray()));
        return Bytes.concat(
                ByteUtils.bigIntegerToBytes(signature.r, 32),
                ByteUtils.bigIntegerToBytes(signature.s, 32));
    }

    public static ServiceOuterClass.BroadcastTxRequest buildBroadcastTxRequest(
            Map<String, ECKey> signerAddressToECKeyMap,
            Map<String, Long> signerAddressToSequenceMap,
            Map<String, Long> signerAddressToAccountNumberMap,
            List<Message> msgList,
            List<String> msgSignerAddressList,
            TxOuterClass.Fee fee,
            String chainId,
            ServiceOuterClass.BroadcastMode broadcastMode) {
        LinkedHashSet<String> signerAddressSet = new LinkedHashSet<>(msgSignerAddressList);
        signerAddressSet.add(fee.getPayer());
        List<String> signerAddressList = new ArrayList<>(signerAddressSet);

        TxOuterClass.AuthInfo authInfo = buildAuthInfo(signerAddressList, signerAddressToECKeyMap, signerAddressToSequenceMap, fee);

        TxOuterClass.TxBody.Builder txBodyBuilder = TxOuterClass.TxBody.newBuilder();
        for (Message msg : msgList) {
            txBodyBuilder.addMessages(Any.pack(msg, "/"));
        }
        TxOuterClass.TxBody txBody = txBodyBuilder.build();

        TxOuterClass.Tx.Builder txBuilder = TxOuterClass.Tx.newBuilder();
        txBuilder.setBody(txBody);
        txBuilder.setAuthInfo(authInfo);
        for (String signerAddress : signerAddressList) {
            ECKey signerECKey = signerAddressToECKeyMap.get(signerAddress);
            if (signerECKey == null) {
                throw new RuntimeException("can not find signer ECKey for address: " + signerAddress);
            }
            Long signerAccountNumber = signerAddressToAccountNumberMap.get(signerAddress);
            if (signerAccountNumber == null) {
                throw new RuntimeException("can not find signer accountNumber for address: " + signerAddress);
            }
            txBuilder.addSignatures(ByteString.copyFrom(
                    signMessage(signerECKey, signerAccountNumber, txBody, authInfo, chainId)));
        }
        TxOuterClass.Tx tx = txBuilder.build();

        return ServiceOuterClass.BroadcastTxRequest.newBuilder()
                .setTxBytes(tx.toByteString())
                .setMode(broadcastMode)
                .build();
    }

    public static ServiceOuterClass.BroadcastTxRequest buildBroadcastTxRequestUnordered(
            Map<String, ECKey> signerAddressToECKeyMap,
            Map<String, Long> signerAddressToAccountNumberMap,
            List<Message> msgList,
            List<String> msgSignerAddressList,
            TxOuterClass.Fee fee,
            String chainId,
            ServiceOuterClass.BroadcastMode broadcastMode,
            Timestamp timeoutTimestamp) {
        LinkedHashSet<String> signerAddressSet = new LinkedHashSet<>(msgSignerAddressList);
        signerAddressSet.add(fee.getPayer());
        List<String> signerAddressList = new ArrayList<>(signerAddressSet);

        TxOuterClass.AuthInfo authInfo = buildAuthInfoUnordered(signerAddressList, signerAddressToECKeyMap, fee);

        TxOuterClass.TxBody.Builder txBodyBuilder = TxOuterClass.TxBody.newBuilder();
        for (Message msg : msgList) {
            txBodyBuilder.addMessages(Any.pack(msg, "/"));
        }
        txBodyBuilder.setUnordered(true);
        txBodyBuilder.setTimeoutTimestamp(timeoutTimestamp);
        TxOuterClass.TxBody txBody = txBodyBuilder.build();

        TxOuterClass.Tx.Builder txBuilder = TxOuterClass.Tx.newBuilder();
        txBuilder.setBody(txBody);
        txBuilder.setAuthInfo(authInfo);
        for (String signerAddress : signerAddressList) {
            ECKey signerECKey = signerAddressToECKeyMap.get(signerAddress);
            if (signerECKey == null) {
                throw new RuntimeException("can not find signer ECKey for address: " + signerAddress);
            }
            Long signerAccountNumber = signerAddressToAccountNumberMap.get(signerAddress);
            if (signerAccountNumber == null) {
                throw new RuntimeException("can not find signer accountNumber for address: " + signerAddress);
            }
            txBuilder.addSignatures(ByteString.copyFrom(
                    signMessage(signerECKey, signerAccountNumber, txBody, authInfo, chainId)));
        }
        TxOuterClass.Tx tx = txBuilder.build();

        return ServiceOuterClass.BroadcastTxRequest.newBuilder()
                .setTxBytes(tx.toByteString())
                .setMode(broadcastMode)
                .build();
    }

    public static ListenableFuture<Map<String, Auth.BaseAccount>> queryBaseAccount(
            QueryGrpc.QueryFutureStub queryFutureStub,
            Set<String> addressSet) {
        if (addressSet.isEmpty()) {
            return Futures.immediateFuture(Collections.emptyMap());
        } else if (addressSet.size() == 1) {
            return Futures.transform(
                    queryFutureStub.account(QueryOuterClass.QueryAccountRequest.newBuilder()
                            .setAddress(addressSet.iterator().next())
                            .build()),
                    response -> {
                        if (response != null
                                && response.hasAccount()
                                && response.getAccount().is(cosmos.auth.v1beta1.Auth.BaseAccount.class)) {
                            try {
                                Auth.BaseAccount baseAccount = response.getAccount().unpack(Auth.BaseAccount.class);
                                return Collections.singletonMap(baseAccount.getAddress(), baseAccount);
                            } catch (InvalidProtocolBufferException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            return Collections.emptyMap();
                        }
                    },
                    MoreExecutors.directExecutor());
        } else {
            List<ListenableFuture<QueryOuterClass.QueryAccountResponse>> futureList = new ArrayList<>();
            for (String address : addressSet) {
                futureList.add(queryFutureStub.account(QueryOuterClass.QueryAccountRequest.newBuilder()
                        .setAddress(address)
                        .build()));
            }
            return Futures.transform(
                    Futures.allAsList(futureList),
                    responseList -> {
                        Map<String, Auth.BaseAccount> baseAccountMap = new HashMap<>();
                        for (QueryOuterClass.QueryAccountResponse response : responseList) {
                            if (response != null
                                    && response.hasAccount()
                                    && response.getAccount().is(cosmos.auth.v1beta1.Auth.BaseAccount.class)) {
                                try {
                                    Auth.BaseAccount baseAccount = response.getAccount().unpack(Auth.BaseAccount.class);
                                    baseAccountMap.put(baseAccount.getAddress(), baseAccount);
                                } catch (InvalidProtocolBufferException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        return baseAccountMap;
                    },
                    MoreExecutors.directExecutor());
        }
    }

    public static ListenableFuture<ServiceOuterClass.BroadcastTxResponse> broadcastTx(
            QueryGrpc.QueryFutureStub queryFutureStub,
            ServiceGrpc.ServiceFutureStub serviceFutureStub,
            Map<String, ECKey> signerAddressToECKeyMap,
            List<Message> msgList,
            List<String> msgSignerAddressList,
            TxOuterClass.Fee fee,
            String chainId,
            ServiceOuterClass.BroadcastMode broadcastMode) {
        ListenableFuture<Map<String, Auth.BaseAccount>> baseAccountFuture = queryBaseAccount(
                queryFutureStub,
                signerAddressToECKeyMap.keySet());
        return Futures.transformAsync(
                baseAccountFuture,
                signerAddressToBaseAccountMap -> {
                    Map<String, Long> signerAddressToSequenceMap = new HashMap<>();
                    Map<String, Long> signerAddressToAccountNumberMap = new HashMap<>();
                    if (signerAddressToBaseAccountMap != null) {
                        for (Map.Entry<String, Auth.BaseAccount> entry : signerAddressToBaseAccountMap.entrySet()) {
                            signerAddressToSequenceMap.put(entry.getKey(), entry.getValue().getSequence());
                            signerAddressToAccountNumberMap.put(entry.getKey(), entry.getValue().getAccountNumber());
                        }
                    }
                    return serviceFutureStub.broadcastTx(buildBroadcastTxRequest(
                            signerAddressToECKeyMap,
                            signerAddressToSequenceMap,
                            signerAddressToAccountNumberMap,
                            msgList,
                            msgSignerAddressList,
                            fee,
                            chainId,
                            broadcastMode));
                },
                MoreExecutors.directExecutor());
    }

    public static ListenableFuture<ServiceOuterClass.BroadcastTxResponse> broadcastTxUnordered(
            QueryGrpc.QueryFutureStub queryFutureStub,
            ServiceGrpc.ServiceFutureStub serviceFutureStub,
            Map<String, ECKey> signerAddressToECKeyMap,
            List<Message> msgList,
            List<String> msgSignerAddressList,
            TxOuterClass.Fee fee,
            String chainId,
            ServiceOuterClass.BroadcastMode broadcastMode,
            Timestamp timeoutTimestamp) {
        ListenableFuture<Map<String, Auth.BaseAccount>> baseAccountFuture = queryBaseAccount(
                queryFutureStub,
                signerAddressToECKeyMap.keySet());
        return Futures.transformAsync(
                baseAccountFuture,
                signerAddressToBaseAccountMap -> {
                    Map<String, Long> signerAddressToAccountNumberMap = new HashMap<>();
                    if (signerAddressToBaseAccountMap != null) {
                        for (Map.Entry<String, Auth.BaseAccount> entry : signerAddressToBaseAccountMap.entrySet()) {
                            signerAddressToAccountNumberMap.put(entry.getKey(), entry.getValue().getAccountNumber());
                        }
                    }
                    return serviceFutureStub.broadcastTx(buildBroadcastTxRequestUnordered(
                            signerAddressToECKeyMap,
                            signerAddressToAccountNumberMap,
                            msgList,
                            msgSignerAddressList,
                            fee,
                            chainId,
                            broadcastMode,
                            timeoutTimestamp));
                },
                MoreExecutors.directExecutor());
    }

    private static final ConcurrentHashMap<Class<? extends Message>, ImmutableList<Descriptors.FieldDescriptor>>
            MSG_SIGNER_FIELD_DESCRIPTOR_MAP = new ConcurrentHashMap<>();

    public static List<String> getMsgSigner(Message msg) {
        ImmutableList<Descriptors.FieldDescriptor> fieldDescriptorList = MSG_SIGNER_FIELD_DESCRIPTOR_MAP.get(msg.getClass());
        if (fieldDescriptorList == null) {
            Set<String> signerFieldNameSet = new LinkedHashSet<>(msg.getDescriptorForType().getOptions().getExtension(Msg.signer));
            List<Descriptors.FieldDescriptor> list = new ArrayList<>(signerFieldNameSet.size());
            for (String signerFieldName : signerFieldNameSet) {
                Descriptors.FieldDescriptor fieldDescriptor = msg.getDescriptorForType().findFieldByName(signerFieldName);
                if (fieldDescriptor != null && fieldDescriptor.getType() == Descriptors.FieldDescriptor.Type.STRING) {
                    list.add(fieldDescriptor);
                }
            }
            fieldDescriptorList = ImmutableList.copyOf(list);
            MSG_SIGNER_FIELD_DESCRIPTOR_MAP.putIfAbsent(msg.getClass(), fieldDescriptorList);
        }
        List<String> signerList = new ArrayList<>(fieldDescriptorList.size());
        for (Descriptors.FieldDescriptor fieldDescriptor : fieldDescriptorList) {
            signerList.add((String) msg.getField(fieldDescriptor));
        }
        return signerList;
    }

    public static ListenableFuture<ServiceOuterClass.BroadcastTxResponse> broadcastTx(
            QueryGrpc.QueryFutureStub queryFutureStub,
            ServiceGrpc.ServiceFutureStub serviceFutureStub,
            Map<String, ECKey> signerAddressToECKeyMap,
            List<Message> msgList,
            TxOuterClass.Fee fee,
            String chainId,
            ServiceOuterClass.BroadcastMode broadcastMode) {
        List<String> msgSignerAddressList = new ArrayList<>();
        for (Message msg : msgList) {
            msgSignerAddressList.addAll(getMsgSigner(msg));
        }
        return broadcastTx(
                queryFutureStub,
                serviceFutureStub,
                signerAddressToECKeyMap,
                msgList,
                msgSignerAddressList,
                fee,
                chainId,
                broadcastMode);
    }

    public static ListenableFuture<ServiceOuterClass.BroadcastTxResponse> broadcastTxUnordered(
            QueryGrpc.QueryFutureStub queryFutureStub,
            ServiceGrpc.ServiceFutureStub serviceFutureStub,
            Map<String, ECKey> signerAddressToECKeyMap,
            List<Message> msgList,
            TxOuterClass.Fee fee,
            String chainId,
            ServiceOuterClass.BroadcastMode broadcastMode,
            Timestamp timeoutTimestamp) {
        List<String> msgSignerAddressList = new ArrayList<>();
        for (Message msg : msgList) {
            msgSignerAddressList.addAll(getMsgSigner(msg));
        }
        return broadcastTxUnordered(
                queryFutureStub,
                serviceFutureStub,
                signerAddressToECKeyMap,
                msgList,
                msgSignerAddressList,
                fee,
                chainId,
                broadcastMode,
                timeoutTimestamp);
    }

}

package exchange.antx.common.util;


import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import cosmos.auth.v1beta1.Auth;
import cosmos.base.abci.v1beta1.Abci;
import cosmos.base.query.v1beta1.Pagination;
import cosmos.base.v1beta1.CoinOuterClass;
import cosmos.tx.v1beta1.ServiceOuterClass;
import cosmos.tx.v1beta1.TxOuterClass;
import exchange.antx.proto.chain.agent.ChainType;
import exchange.antx.proto.chain.agent.MsgBindAgent;
import exchange.antx.proto.chain.agent.QueryListAgentBindingByChainAddressRequest;
import exchange.antx.proto.chain.agent.QueryListAgentBindingByChainAddressResponse;
import exchange.antx.proto.chain.exchange.ExchangeType;
import exchange.antx.proto.chain.exchange.MarginMode;
import exchange.antx.proto.chain.order.MsgCreateOrder;
import exchange.antx.proto.chain.order.MsgCreateOrderResponse;
import exchange.antx.proto.chain.order.TimeInForce;
import exchange.antx.proto.chain.subaccount.MsgRegisterSubaccount;
import exchange.antx.proto.chain.subaccount.MsgRegisterSubaccountResponse;
import exchange.antx.proto.chain.subaccount.MsgSetPerpetualLeverage;
import exchange.antx.proto.chain.subaccount.MsgTransfer;
import exchange.antx.proto.chain.subaccount.QueryGetSubaccountByChainAddressRequest;
import exchange.antx.proto.chain.subaccount.QueryGetSubaccountByChainAddressResponse;
import exchange.antx.proto.chain.subaccount.Subaccount;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Disabled
public class CosmosUtilTest {

    private static final String CHAIN_ID = "antx-testnet";
    private static final ECKey ADMIN_KEY = ECKey.fromPrivate(BaseEncoding.base16().lowerCase()
            .decode("cfef9f13f7575cd86c8704fb17a76dcf30517b1c28fce97ae71b886abe13aec2"));
    private static final String ADMIN_ADDRESS = CosmosUtil.ecKeyToAddress("antx", ADMIN_KEY);

    private static final ECKeyPair BANK_ETH_KEY = ECKeyPair.create(BaseEncoding.base16().lowerCase()
            .decode("9ad056cfde48393922a2ac7a853bc7872a89cf5ef99477b64533ba6890a75ed5"));
    private static final String BANK_ETH_ADDRESS = Keys.toChecksumAddress(Keys.getAddress(BANK_ETH_KEY));

    private static ManagedChannel CHANNEL;
    private static cosmos.auth.v1beta1.QueryGrpc.QueryBlockingStub AUTH_QUERY_STUB;
    private static cosmos.tx.v1beta1.ServiceGrpc.ServiceBlockingStub TX_SERVICE_STUB;
    private static exchange.antx.proto.chain.subaccount.QueryGrpc.QueryBlockingStub SUBACCOUNT_QUERY_STUB;
    private static exchange.antx.proto.chain.agent.QueryGrpc.QueryBlockingStub AGENT_QUERY_STUB;
    private static long ADMIN_ACCOUNT_NUMBER;

    @BeforeAll
    public static void init() throws Exception {
        CHANNEL = ManagedChannelBuilder.forAddress("grpc.testnet.antx.ai", 80) // "127.0.0.1", 9090)
                .usePlaintext()
                .build();
        AUTH_QUERY_STUB = cosmos.auth.v1beta1.QueryGrpc.newBlockingStub(CHANNEL);
        TX_SERVICE_STUB = cosmos.tx.v1beta1.ServiceGrpc.newBlockingStub(CHANNEL);
        AGENT_QUERY_STUB = exchange.antx.proto.chain.agent.QueryGrpc.newBlockingStub(CHANNEL);
        SUBACCOUNT_QUERY_STUB = exchange.antx.proto.chain.subaccount.QueryGrpc.newBlockingStub(CHANNEL);

        cosmos.auth.v1beta1.QueryOuterClass.QueryAccountRequest request =
                cosmos.auth.v1beta1.QueryOuterClass.QueryAccountRequest.newBuilder()
                        .setAddress(ADMIN_ADDRESS)
                        .build();
        cosmos.auth.v1beta1.QueryOuterClass.QueryAccountResponse response = AUTH_QUERY_STUB.account(request);
        if (!response.hasAccount()
                || !response.getAccount().is(cosmos.auth.v1beta1.Auth.BaseAccount.class)) {
            throw new RuntimeException("admin account not found");
        }
        ADMIN_ACCOUNT_NUMBER = response.getAccount().unpack(Auth.BaseAccount.class).getAccountNumber();
    }

    @AfterAll
    public static void destroy() {
        CHANNEL.shutdown();
        CHANNEL = null;
    }

    @Test
    public void testGenerateRandomECKey() {
        ECKey ecKey = new ECKey(new SecureRandom());
        String address = CosmosUtil.ecKeyToAddress("antx", ecKey);
        System.out.println("privateKey: " + BaseEncoding.base16().lowerCase().encode(ecKey.getPrivKeyBytes()));
        System.out.println("publicKey: " + BaseEncoding.base16().lowerCase().encode(ecKey.getPubKeyPoint().getEncoded(true)));
        System.out.println("address: " + address);
    }

    @Test
    public void testFlow1() throws Throwable {
        // 1. 生成随机eth地址 (alice)
        ECKeyPair aliceEthKey = Keys.createEcKeyPair();
        String aliceEthAddress = Keys.toChecksumAddress(Keys.getAddress(aliceEthKey));
        ECKeyPair bobEthKey = Keys.createEcKeyPair();
        String bobEthAddress = Keys.toChecksumAddress(Keys.getAddress(bobEthKey));

        // 2. 使用管理员创建交易子账号 (alice)
        long bankSubaccountId = mustGetSubaccount(BANK_ETH_ADDRESS, "fee").getId();
        long aliceSubaccountId = registerSubaccount(aliceEthAddress);
        long bobSubaccountId = registerSubaccount(bobEthAddress);

        // 3. 生成随机代理cosmos账号 (bank & alice)
        ECKey bankAgentKey = new ECKey(new SecureRandom());
        String bankAgentAddress = CosmosUtil.ecKeyToAddress("antx", bankAgentKey);
        long bankAgentAccountNumber = mustGetAccountNumber(bankAgentAddress);
        ECKey aliceAgentKey = new ECKey(new SecureRandom());
        String aliceAgentAddress = CosmosUtil.ecKeyToAddress("antx", aliceAgentKey);
        long aliceAgentAccountNumber = mustGetAccountNumber(aliceAgentAddress);

        ECKey bobAgentKey = new ECKey(new SecureRandom());
        String bobAgentAddress = CosmosUtil.ecKeyToAddress("antx", bobAgentKey);
        long bobAgentAccountNumber = mustGetAccountNumber(bobAgentAddress);

        // 4. 绑定Agent (bank & alice)
        bindAgent(BANK_ETH_KEY, bankAgentKey, bankAgentAccountNumber);
        bindAgent(aliceEthKey, aliceAgentKey, aliceAgentAccountNumber);
        TimeUnit.SECONDS.sleep(1);
        bindAgent(bobEthKey, bobAgentKey, bobAgentAccountNumber);

        printBindAgent(BANK_ETH_ADDRESS);
        printBindAgent(aliceEthAddress);

        // 5. 发起转账 bank -> alice
        transfer(
                bankAgentKey,
                bankAgentAccountNumber,
                bankSubaccountId,
                aliceSubaccountId,
                1000,
                0,
                100000,
                "just test!");

        transfer(
                bankAgentKey,
                bankAgentAccountNumber,
                bankSubaccountId,
                bobSubaccountId,
                1000,
                0,
                200000,
                "just test 2!");

        // 6. 设置账户信息 杠杆 交易模拟 等 (alice)
        setPerpetualLeverage(
                aliceAgentKey,
                aliceAgentAccountNumber,
                aliceSubaccountId,
                200001L,
                10);

//        Assertions.assertEquals(
//                10,
//                mustGetSubaccount(aliceEthAddress, "main")
//                        .getExchangeIdToLeverageOrDefault(200001L, 0));

        // 7. 下委托单
        long orderId = createPerpetualOrder(
                aliceAgentKey,
                aliceAgentAccountNumber,
                aliceSubaccountId,
                200001L,
                10,
                true,
                0,
                100000,
                2,
                1,
                "test-alice-order-1");
        System.out.println("orderId: " + orderId);

        long orderId2 = createPerpetualOrder(
                aliceAgentKey,
                aliceAgentAccountNumber,
                aliceSubaccountId,
                200001L,
                10,
                true,
                0,
                100050,
                2,
                1,
                "test-alice-order-2");
        System.out.println("orderId2: " + orderId2);

        long orderId3 = createPerpetualOrder(
                bobAgentKey,
                bobAgentAccountNumber,
                bobSubaccountId,
                200001L,
                10,
                false,
                0,
                99990,
                2,
                3,
                "test-alice-order-3");
        System.out.println("orderId3: " + orderId3);

        long orderId4 = createPerpetualOrder(
                bobAgentKey,
                bobAgentAccountNumber,
                bobSubaccountId,
                200001L,
                10,
                false,
                0,
                101050,
                2,
                1,
                "test-alice-order-4");
        System.out.println("orderId4: " + orderId4);
    }

    private static Subaccount mustGetSubaccount(String ethAddress, String clientAccountId) throws Throwable {
        QueryGetSubaccountByChainAddressRequest request = QueryGetSubaccountByChainAddressRequest.newBuilder()
                .setChainType(ChainType.CHAIN_TYPE_EVM)
                .setChainAddress(ethAddress)
                .addClientAccountId(clientAccountId)
                .build();
        QueryGetSubaccountByChainAddressResponse response = SUBACCOUNT_QUERY_STUB.getSubaccountByChainAddress(request);
        Subaccount subaccount = response.getSubaccountList().stream()
                .filter(s -> s.getChainType() == ChainType.CHAIN_TYPE_EVM && s.getChainAddress().equals(ethAddress) && s.getClientAccountId().equals(clientAccountId))
                .findFirst()
                .orElse(null);
        if (subaccount == null) {
            throw new IllegalStateException("subaccount not found, ethAddress=" + ethAddress + ", clientAccountId=" + clientAccountId);
        }
        return subaccount;
    }

    private long registerSubaccount(String ethAddress) throws Throwable {
        MsgRegisterSubaccount msg = MsgRegisterSubaccount.newBuilder()
                .setOwnerAddress(ADMIN_ADDRESS)
                .setChainType(ChainType.CHAIN_TYPE_EVM)
                .setChainAddress(ethAddress)
                .setClientAccountId("main")
                .setIsSystemAccount(false)
                .build();
        ServiceOuterClass.BroadcastTxRequest broadcastTxRequest =
                CosmosUtil.buildBroadcastTxRequestUnordered(
                        Collections.singletonMap(ADMIN_ADDRESS, ADMIN_KEY),
                        Collections.singletonMap(ADMIN_ADDRESS, ADMIN_ACCOUNT_NUMBER),
                        Collections.singletonList(msg),
                        Collections.singletonList(ADMIN_ADDRESS),
                        TxOuterClass.Fee.newBuilder()
                                .setGasLimit(200000L)
                                .setPayer(ADMIN_ADDRESS)
                                .addAmount(CoinOuterClass.Coin.newBuilder()
                                        .setAmount("1")
                                        .setDenom("antx")
                                        .build())
                                .build(),
                        CHAIN_ID,
                        ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
                        Timestamps.add(Timestamps.now(), Durations.fromMinutes(5)));

        Abci.TxMsgData txMsgData = broadcastAndWaitTx(msg, broadcastTxRequest);
        MsgRegisterSubaccountResponse response =  txMsgData.getMsgResponses(0)
                .unpack(MsgRegisterSubaccountResponse.class);
        System.out.println("["+ msg.getDescriptorForType().getFullName() + "][response]: " + PbUtil.printJsonQuietly(response));

        return response.getSubaccountId();
    }

    private void bindAgent(ECKeyPair ethKey, ECKey agentKey, long agentAccountNumber) throws Throwable {
        String ethAddress = Keys.toChecksumAddress(Keys.getAddress(ethKey));
        String agentAddress = CosmosUtil.ecKeyToAddress("antx", agentKey);
        long createTime = System.currentTimeMillis();
        long expireTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14);

        String message = String.format(
                "Action:BindAgent\nAgentAddress:%s\nCreateTime:%d\nExpireTime:%d\nChainId:%s",
                agentAddress, createTime, expireTime, CHAIN_ID);
        Sign.SignatureData signatureData = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), ethKey);
        String ethSignature = Numeric.toHexString(Bytes.concat(signatureData.getR(), signatureData.getS(), signatureData.getV()));

        MsgBindAgent msg = MsgBindAgent.newBuilder()
                .setAgentAddress(agentAddress)
                .setChainType(ChainType.CHAIN_TYPE_EVM)
                .setChainAddress(ethAddress)
                .setCreateTime(createTime)
                .setExpireTime(expireTime)
                .setChainSignature(ethSignature)
                .build();

        ServiceOuterClass.BroadcastTxRequest broadcastTxRequest =
                CosmosUtil.buildBroadcastTxRequestUnordered(
                        Collections.singletonMap(agentAddress, agentKey),
                        Collections.singletonMap(agentAddress, agentAccountNumber),
                        Collections.singletonList(msg),
                        Collections.singletonList(agentAddress),
                        TxOuterClass.Fee.newBuilder()
                                .setGasLimit(200000L)
                                .setPayer(agentAddress)
                                .addAmount(CoinOuterClass.Coin.newBuilder()
                                        .setAmount("1")
                                        .setDenom("antx")
                                        .build())
                                .build(),
                        CHAIN_ID,
                        ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
                        Timestamps.add(Timestamps.now(), Durations.fromMinutes(5)));

        broadcastAndWaitTx(msg, broadcastTxRequest);
    }

    private void printBindAgent(String ethAddress) {
        ByteString nextPageKey = ByteString.EMPTY;
        do {
            QueryListAgentBindingByChainAddressResponse response =
                    AGENT_QUERY_STUB.listAgentBindingByChainAddress(
                            QueryListAgentBindingByChainAddressRequest.newBuilder()
                                    .setChainType(ChainType.CHAIN_TYPE_EVM)
                                    .setChainAddress(ethAddress)
                                    .setPagination(Pagination.PageRequest.newBuilder()
                                            .setKey(nextPageKey)
                                            .setLimit(1)
                                            .build())
                                    .build());
            System.out.println("[QueryListAgentBindingByEthAddress]: " + ethAddress + ","  + PbUtil.printJsonQuietly(response));
            nextPageKey = response.getPagination().getNextKey();
        } while (!nextPageKey.isEmpty());
    }

    private void transfer(
            ECKey agentKey,
            long agentAccountNumber,
            long senderSubaccountId,
            long receiverSubaccountId,
            long coinId,
            int amountScale,
            long amountValue,
            String remark) throws Throwable {
        String agentAddress = CosmosUtil.ecKeyToAddress("antx", agentKey);
        MsgTransfer msg = MsgTransfer.newBuilder()
                .setAgentAddress(agentAddress)
                .setSenderSubaccountId(senderSubaccountId)
                .setSenderExchangeType(ExchangeType.EXCHANGE_TYPE_PERPETUAL)
                .setReceiverSubaccountId(receiverSubaccountId)
                .setReceiverExchangeType(ExchangeType.EXCHANGE_TYPE_PERPETUAL)
                .setCoinId(coinId)
                .setAmountScale(amountScale)
                .setAmountValue(amountValue)
                .setRemark(remark)
                .build();

        ServiceOuterClass.BroadcastTxRequest broadcastTxRequest =
                CosmosUtil.buildBroadcastTxRequestUnordered(
                        Collections.singletonMap(agentAddress, agentKey),
                        Collections.singletonMap(agentAddress, agentAccountNumber),
                        Collections.singletonList(msg),
                        Collections.singletonList(agentAddress),
                        TxOuterClass.Fee.newBuilder()
                                .setGasLimit(200000L)
                                .setPayer(agentAddress)
                                .addAmount(CoinOuterClass.Coin.newBuilder()
                                        .setAmount("1")
                                        .setDenom("antx")
                                        .build())
                                .build(),
                        CHAIN_ID,
                        ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
                        Timestamps.add(Timestamps.now(), Durations.fromMinutes(5)));

        broadcastAndWaitTx(msg, broadcastTxRequest);
    }

    private void setPerpetualLeverage(
            ECKey agentKey,
            long agentAccountNumber,
            long subaccountId,
            long exchangeId,
            int leverage) throws Throwable {
        String agentAddress = CosmosUtil.ecKeyToAddress("antx", agentKey);
        MsgSetPerpetualLeverage msg = MsgSetPerpetualLeverage.newBuilder()
                .setAgentAddress(agentAddress)
                .setSubaccountId(subaccountId)
                .setExchangeId(exchangeId)
                .setLeverage(leverage)
                .build();

        ServiceOuterClass.BroadcastTxRequest broadcastTxRequest =
                CosmosUtil.buildBroadcastTxRequestUnordered(
                        Collections.singletonMap(agentAddress, agentKey),
                        Collections.singletonMap(agentAddress, agentAccountNumber),
                        Collections.singletonList(msg),
                        Collections.singletonList(agentAddress),
                        TxOuterClass.Fee.newBuilder()
                                .setGasLimit(200000L)
                                .setPayer(agentAddress)
                                .addAmount(CoinOuterClass.Coin.newBuilder()
                                        .setAmount("1")
                                        .setDenom("antx")
                                        .build())
                                .build(),
                        CHAIN_ID,
                        ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
                        Timestamps.add(Timestamps.now(), Durations.fromMinutes(5)));

        broadcastAndWaitTx(msg, broadcastTxRequest);
    }

    private long createPerpetualOrder(
            ECKey agentKey,
            long agentAccountNumber,
            long subaccountId,
            long exchangeId,
            int leverage,
            boolean isBuy,
            int priceScale,
            long priceValue,
            int sizeScale,
            long sizeValue,
            String clientOrderId) throws Throwable {
        String agentAddress = CosmosUtil.ecKeyToAddress("antx", agentKey);
        MsgCreateOrder msg = MsgCreateOrder.newBuilder()
                .setAgentAddress(agentAddress)
                .setSubaccountId(subaccountId)
                .setExchangeId(exchangeId)
                .setMarginMode(MarginMode.MARGIN_MODE_CROSS)
                .setLeverage(leverage)
                .setIsBuy(isBuy)
                .setPriceScale(priceScale)
                .setPriceValue(priceValue)
                .setSizeScale(sizeScale)
                .setSizeValue(sizeValue)
                .setClientOrderId(clientOrderId)
                .setTimeInForce(TimeInForce.TIME_IN_FORCE_GOOD_TIL_CANCEL)
                .setReduceOnly(false)
                .setExpireTime(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14))
                .setIsMarket(false)
                .build();
        ServiceOuterClass.BroadcastTxRequest broadcastTxRequest =
                CosmosUtil.buildBroadcastTxRequestUnordered(
                        Collections.singletonMap(agentAddress, agentKey),
                        Collections.singletonMap(agentAddress, agentAccountNumber),
                        Collections.singletonList(msg),
                        Collections.singletonList(agentAddress),
                        TxOuterClass.Fee.newBuilder()
                                .setGasLimit(200000L)
                                .setPayer(agentAddress)
                                .addAmount(CoinOuterClass.Coin.newBuilder()
                                        .setAmount("1")
                                        .setDenom("antx")
                                        .build())
                                .build(),
                        CHAIN_ID,
                        ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
                        Timestamps.add(Timestamps.now(), Durations.fromMinutes(5)));

        Abci.TxMsgData txMsgData = broadcastAndWaitTx(msg, broadcastTxRequest);
        MsgCreateOrderResponse response = txMsgData.getMsgResponses(0)
                .unpack(MsgCreateOrderResponse.class);
        System.out.println("["+ msg.getDescriptorForType().getFullName() + "][response]: " + PbUtil.printJsonQuietly(response));

        return response.getOrderId();
    }

    private long mustGetAccountNumber(String address) throws Throwable {
        try {
            cosmos.auth.v1beta1.QueryOuterClass.QueryAccountRequest request =
                    cosmos.auth.v1beta1.QueryOuterClass.QueryAccountRequest.newBuilder()
                            .setAddress(address)
                            .build();
            cosmos.auth.v1beta1.QueryOuterClass.QueryAccountResponse response = AUTH_QUERY_STUB.account(request);
            if (response.hasAccount()
                    && response.getAccount().is(cosmos.auth.v1beta1.Auth.BaseAccount.class)) {
                return response.getAccount().unpack(cosmos.auth.v1beta1.Auth.BaseAccount.class).getAccountNumber();
            }
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() != Status.Code.NOT_FOUND) {
                throw exception;
            }
        }

        cosmos.bank.v1beta1.Tx.MsgSend msgSendTx = cosmos.bank.v1beta1.Tx.MsgSend.newBuilder()
                .setFromAddress(ADMIN_ADDRESS)
                .setToAddress(address)
                .addAmount(CoinOuterClass.Coin.newBuilder()
                        .setAmount("10000")
                        .setDenom("antx")
                        .build())
                .build();

        ServiceOuterClass.BroadcastTxRequest broadcastTxRequest =
                CosmosUtil.buildBroadcastTxRequestUnordered(
                        Collections.singletonMap(ADMIN_ADDRESS, ADMIN_KEY),
                        Collections.singletonMap(ADMIN_ADDRESS, ADMIN_ACCOUNT_NUMBER),
                        Collections.singletonList(msgSendTx),
                        Collections.singletonList(ADMIN_ADDRESS),
                        TxOuterClass.Fee.newBuilder()
                                .setGasLimit(200000L)
                                .setPayer(ADMIN_ADDRESS)
                                .addAmount(CoinOuterClass.Coin.newBuilder()
                                        .setAmount("1")
                                        .setDenom("antx")
                                        .build())
                                .build(),
                        CHAIN_ID,
                        ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
                Timestamps.add(Timestamps.now(), Durations.fromMinutes(5)));

        broadcastAndWaitTx(msgSendTx, broadcastTxRequest);

        cosmos.auth.v1beta1.QueryOuterClass.QueryAccountRequest request2 =
                cosmos.auth.v1beta1.QueryOuterClass.QueryAccountRequest.newBuilder()
                        .setAddress(address)
                        .build();
        cosmos.auth.v1beta1.QueryOuterClass.QueryAccountResponse response2 = AUTH_QUERY_STUB.account(request2);
        if (response2.hasAccount()
                && response2.getAccount().is(cosmos.auth.v1beta1.Auth.BaseAccount.class)) {
            return response2.getAccount().unpack(cosmos.auth.v1beta1.Auth.BaseAccount.class).getAccountNumber();
        }
        throw new IllegalStateException("can not get account number");
    }

    private Abci.TxMsgData broadcastAndWaitTx(
            Message msg,
            ServiceOuterClass.BroadcastTxRequest request) throws Throwable {
        String msgName = msg.getDescriptorForType().getFullName();
        System.out.println("["+ msgName + "][msg]: " + PbUtil.printJsonQuietly(msg));

        ServiceOuterClass.BroadcastTxResponse broadcastTxResponse = TX_SERVICE_STUB.broadcastTx(request);
        if (broadcastTxResponse.getTxResponse().getCode() != 0) {
            System.out.println("["+ msgName + "][broadcast][" + broadcastTxResponse.getTxResponse().getTxhash() + "]: " + PbUtil.printJsonQuietly(broadcastTxResponse));
            throw new RuntimeException("tx response code=" + broadcastTxResponse.getTxResponse().getCode() + ", msg=" + broadcastTxResponse.getTxResponse().getRawLog());
        }

        Throwable throwable = null;
        for (int retry = 0; retry < 10; retry++) {
            if (retry > 0) {
                TimeUnit.SECONDS.sleep(2);
            }
            try {
                ServiceOuterClass.GetTxRequest getTxRequest = ServiceOuterClass.GetTxRequest.newBuilder()
                        .setHash(broadcastTxResponse.getTxResponse().getTxhash())
                        .build();
                ServiceOuterClass.GetTxResponse getTxResponse = TX_SERVICE_STUB.getTx(getTxRequest);
                System.out.println("["+ msgName + "][getTx][" + broadcastTxResponse.getTxResponse().getTxhash() + "][" + retry + "]: " + PbUtil.printJsonQuietly(getTxResponse));
                if (getTxResponse.getTxResponse().getCode() == 0) {
                    byte[] dataBytes = BaseEncoding.base16().decode(getTxResponse.getTxResponse().getData());
                    return Abci.TxMsgData.parseFrom(dataBytes);
                } else {
                    throw new RuntimeException("tx response code=" + getTxResponse.getTxResponse().getCode() + ", msg=" + getTxResponse.getTxResponse().getRawLog());
                }
            } catch (Throwable th) {
                throwable = th;
            }
        }
        throw throwable;
    }

}


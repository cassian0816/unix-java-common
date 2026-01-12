package exchange.antx.common.grpc.interceptor;

import com.google.protobuf.MessageOrBuilder;
import exchange.antx.common.error.ErrorUtil;
import exchange.antx.common.util.PbUtil;
import exchange.antx.proto.common.CommonProto;
import exchange.antx.proto.common.ErrorDetail;
import exchange.antx.proto.common.RpcMethodExtOpt;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogAndMetricsClientInterceptor implements ClientInterceptor {

    private static final AttributeKey<String> GRPC_SERVICE_KEY = AttributeKey.stringKey("grpc_service");
    private static final AttributeKey<String> GRPC_METHOD_KEY = AttributeKey.stringKey("grpc_method");
    private static final AttributeKey<String> GRPC_STATUS_KEY = AttributeKey.stringKey("grpc_status");
    private static final AttributeKey<String> ERROR_CODE_KEY = AttributeKey.stringKey("error_code");

    private static final LongCounter GRPC_CLIENT_STATUS_COUNTER = GlobalOpenTelemetry.get()
            .getMeter("antx.common.grpc.client.status")
            .counterBuilder("antx_grpc_client_status_count")
            .setDescription("antx grpc client status count by service and method")
            .build();

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        final RpcMethodExtOpt rpcMethodExtOpt;
        Object schemaDescriptorObj = method.getSchemaDescriptor();
        if (schemaDescriptorObj instanceof ProtoMethodDescriptorSupplier) {
            rpcMethodExtOpt = ((ProtoMethodDescriptorSupplier) schemaDescriptorObj)
                    .getMethodDescriptor()
                    .getOptions()
                    .getExtension(CommonProto.rpcMethodExtOpt);
        } else {
            rpcMethodExtOpt = RpcMethodExtOpt.getDefaultInstance();
        }

        final boolean isLogDetail = rpcMethodExtOpt.getIsWriteOperation() || rpcMethodExtOpt.getIsLogDetail();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (isLogDetail && log.isInfoEnabled()) {
                    log.info("[{}] start. headers={}",
                            method.getFullMethodName(), headers);
                } else if (log.isDebugEnabled()) {
                    log.debug("[{}] start. headers={}",
                            method.getFullMethodName(), headers);
                }

                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {

                    @Override
                    public void onHeaders(Metadata headers) {
                        if (isLogDetail && log.isInfoEnabled()) {
                            log.info("[{}] onHeaders. headers={}",
                                    method.getFullMethodName(), headers);
                        } else if (log.isDebugEnabled()) {
                            log.debug("[{}] onHeaders. headers={}",
                                    method.getFullMethodName(), headers);
                        }
                        super.onHeaders(headers);
                    }

                    @Override
                    public void onMessage(RespT message) {
                        if (isLogDetail && log.isInfoEnabled()) {
                            log.info("[{}] onMessage. message={}",
                                    method.getFullMethodName(),
                                    PbUtil.printJsonQuietly((MessageOrBuilder) message));
                        } else if (log.isDebugEnabled()) {
                            log.debug("[{}] onMessage. message={}",
                                    method.getFullMethodName(),
                                    PbUtil.printJsonQuietly((MessageOrBuilder) message));
                        }
                        super.onMessage(message);
                    }

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        String grpcServiceName = method.getServiceName() == null ? "UNKNOWN" : method.getServiceName();
                        String grpcMethodName = method.getBareMethodName() == null ? "UNKNOWN" : method.getBareMethodName();
                        String grpcStatus = String.format("%02d.%s", status.getCode().value(), status.getCode().name());
                        ErrorDetail errorDetail = ErrorUtil.toErrorDetail(trailers).orElse(null);
                        String errorCode = errorDetail == null ? "UNKNOWN" : errorDetail.getCode();

                        GRPC_CLIENT_STATUS_COUNTER.add(
                                1L,
                                Attributes.of(
                                        GRPC_SERVICE_KEY, grpcServiceName,
                                        GRPC_METHOD_KEY, grpcMethodName,
                                        GRPC_STATUS_KEY, grpcStatus,
                                        ERROR_CODE_KEY, errorCode));

                        if (isLogDetail && status.isOk() && log.isInfoEnabled()) {
                            log.info("[{}] onClose. statusCode={}, statusDescription={}, errorDetail={}",
                                    method.getFullMethodName(),
                                    status.getCode(),
                                    status.getDescription(),
                                    PbUtil.printJsonQuietly(errorDetail));
                        } else if (isLogDetail && !status.isOk() && log.isWarnEnabled()) {
                            log.warn("[{}] onClose. statusCode={}, statusDescription={}, errorDetail={}",
                                    method.getFullMethodName(),
                                    status.getCode(),
                                    status.getDescription(),
                                    PbUtil.printJsonQuietly(errorDetail));
                        } else if (status.isOk() && log.isDebugEnabled()) {
                            log.debug("[{}] onClose. statusCode={}, statusDescription={}, errorDetail={}",
                                    method.getFullMethodName(),
                                    status.getCode(),
                                    status.getDescription(),
                                    PbUtil.printJsonQuietly(errorDetail));
                        } else if (!status.isOk() && log.isWarnEnabled()) {
                            log.warn("[{}] onClose. statusCode={}, statusDescription={}, errorDetail={}",
                                    method.getFullMethodName(),
                                    status.getCode(),
                                    status.getDescription(),
                                    PbUtil.printJsonQuietly(errorDetail));
                        }

                        super.onClose(status, trailers);
                    }
                }, headers);
            }

            @Override
            public void sendMessage(ReqT message) {
                if (isLogDetail && log.isInfoEnabled()) {
                    log.info("[{}] sendMessage. message={}",
                            method.getFullMethodName(),
                            PbUtil.printJsonQuietly((MessageOrBuilder) message));
                } else if (log.isDebugEnabled()) {
                    log.debug("[{}] sendMessage. message={}",
                            method.getFullMethodName(),
                            PbUtil.printJsonQuietly((MessageOrBuilder) message));
                }
                super.sendMessage(message);
            }
        };
    }
}

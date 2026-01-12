package exchange.antx.common.grpc.interceptor;

import com.google.protobuf.MessageOrBuilder;
import exchange.antx.common.error.ErrorUtil;
import exchange.antx.common.util.PbUtil;
import exchange.antx.proto.common.CommonProto;
import exchange.antx.proto.common.ErrorDetail;
import exchange.antx.proto.common.RpcMethodExtOpt;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogAndMetricsServerInterceptor implements ServerInterceptor {

    private static final AttributeKey<String> GRPC_SERVICE_KEY = AttributeKey.stringKey("grpc_service");
    private static final AttributeKey<String> GRPC_METHOD_KEY = AttributeKey.stringKey("grpc_method");
    private static final AttributeKey<String> GRPC_STATUS_KEY = AttributeKey.stringKey("grpc_status");
    private static final AttributeKey<String> ERROR_CODE_KEY = AttributeKey.stringKey("error_code");

    private static final LongCounter GRPC_SERVER_STATUS_COUNTER = GlobalOpenTelemetry.get()
            .getMeter("antx.common.grpc.server.status")
            .counterBuilder("antx_grpc_server_status_count")
            .setDescription("antx grpc server status count by service and method")
            .build();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        final RpcMethodExtOpt rpcMethodExtOpt;
        Object schemaDescriptorObj = call.getMethodDescriptor().getSchemaDescriptor();
        if (schemaDescriptorObj instanceof ProtoMethodDescriptorSupplier) {
            rpcMethodExtOpt = ((ProtoMethodDescriptorSupplier) schemaDescriptorObj)
                    .getMethodDescriptor()
                    .getOptions()
                    .getExtension(CommonProto.rpcMethodExtOpt);
        } else {
            rpcMethodExtOpt = RpcMethodExtOpt.getDefaultInstance();
        }

        final boolean isLogDetail = rpcMethodExtOpt.getIsWriteOperation() || rpcMethodExtOpt.getIsLogDetail();
        if (isLogDetail && log.isInfoEnabled()) {
            log.info("[{}] startCall. headers={}",
                    call.getMethodDescriptor().getFullMethodName(),
                    headers);
        } else if (log.isDebugEnabled()) {
            log.debug("[{}] startCall. headers={}",
                    call.getMethodDescriptor().getFullMethodName(),
                    headers);
        }

        ServerCall<ReqT, RespT> serverCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {

            @Override
            public void sendMessage(RespT message) {
                if (isLogDetail && log.isInfoEnabled()) {
                    log.info("[{}] sendMessage. message={}",
                            getMethodDescriptor().getFullMethodName(),
                            PbUtil.printJsonQuietly((MessageOrBuilder) message));
                } else if (log.isDebugEnabled()) {
                    log.debug("[{}] sendMessage. message={}",
                            getMethodDescriptor().getFullMethodName(),
                            PbUtil.printJsonQuietly((MessageOrBuilder) message));
                }
                super.sendMessage(message);
            }

            @Override
            public void close(Status status, Metadata trailers) {
                MethodDescriptor<ReqT, RespT> methodDescriptor = getMethodDescriptor();
                String grpcServiceName = methodDescriptor.getServiceName() == null ? "UNKNOWN" : methodDescriptor.getServiceName();
                String grpcMethodName = methodDescriptor.getBareMethodName() == null ? "UNKNOWN" : methodDescriptor.getBareMethodName();
                String grpcStatus = String.format("%02d.%s", status.getCode().value(), status.getCode().name());
                ErrorDetail errorDetail = ErrorUtil.toErrorDetail(trailers).orElse(null);
                String errorCode = errorDetail == null ? "UNKNOWN" : errorDetail.getCode();

                GRPC_SERVER_STATUS_COUNTER.add(
                        1L,
                        Attributes.of(
                                GRPC_SERVICE_KEY, grpcServiceName,
                                GRPC_METHOD_KEY, grpcMethodName,
                                GRPC_STATUS_KEY, grpcStatus,
                                ERROR_CODE_KEY, errorCode));

                if (isLogDetail && status.isOk() && log.isInfoEnabled()) {
                    log.info("[{}] close. statusCode={}, statusDescription={}, errorDetail={}",
                            methodDescriptor.getFullMethodName(),
                            status.getCode(),
                            status.getDescription(),
                            PbUtil.printJsonQuietly(errorDetail));
                } else if (isLogDetail && !status.isOk() && log.isWarnEnabled()) {
                    log.warn("[{}] close. statusCode={}, statusDescription={}, errorDetail={}",
                            methodDescriptor.getFullMethodName(),
                            status.getCode(),
                            status.getDescription(),
                            PbUtil.printJsonQuietly(errorDetail));
                } else if (status.isOk() && log.isDebugEnabled()) {
                    log.debug("[{}] close. statusCode={}, statusDescription={}, errorDetail={}",
                            methodDescriptor.getFullMethodName(),
                            status.getCode(),
                            status.getDescription(),
                            PbUtil.printJsonQuietly(errorDetail));
                } else if (!status.isOk() && log.isWarnEnabled()) {
                    log.warn("[{}] close. statusCode={}, statusDescription={}, errorDetail={}",
                            methodDescriptor.getFullMethodName(),
                            status.getCode(),
                            status.getDescription(),
                            PbUtil.printJsonQuietly(errorDetail));
                }
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> listener = next.startCall(serverCall, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {

            @Override
            public void onMessage(ReqT message) {
                if (isLogDetail && log.isInfoEnabled()) {
                    log.info("[{}] onMessage. message={}",
                            call.getMethodDescriptor().getFullMethodName(),
                            PbUtil.printJsonQuietly((MessageOrBuilder) message));
                } else if (log.isDebugEnabled()) {
                    log.debug("[{}] onMessage. message={}",
                            call.getMethodDescriptor().getFullMethodName(),
                            PbUtil.printJsonQuietly((MessageOrBuilder) message));
                }
                super.onMessage(message);
            }
        };
    }

}

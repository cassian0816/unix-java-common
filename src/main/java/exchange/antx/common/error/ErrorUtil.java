package exchange.antx.common.error;

import com.google.common.collect.Maps;
import exchange.antx.proto.common.ErrorDetail;
import io.grpc.Metadata;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ErrorUtil {

    private static final Metadata.Key<ErrorDetail> ERROR_DETAIL_KEY = ProtoUtils.keyForProto(ErrorDetail.getDefaultInstance());

    public static StatusRuntimeException toStatusRuntimeException(ErrorKey errorKey, Map<String, Object> paramMap) {
        String errorMsg = toErrorMsg(errorKey.msgTemplate(), paramMap);
        Metadata metadata = new Metadata();
        metadata.put(
                ERROR_DETAIL_KEY,
                ErrorDetail.newBuilder()
                        .setCode(errorKey.code())
                        .putAllParam(Maps.transformValues(paramMap, String::valueOf))
                        .build());
        return errorKey.status()
                .withDescription(errorMsg)
                .asRuntimeException(metadata);
    }

    public static Optional<ErrorDetail> toErrorDetail(StatusRuntimeException e) {
        return Optional.ofNullable(e.getTrailers())
                .map(metadata -> metadata.get(ERROR_DETAIL_KEY));
    }

    public static Optional<ErrorDetail> toErrorDetail(StatusException e) {
        return Optional.ofNullable(e.getTrailers())
                .map(metadata -> metadata.get(ERROR_DETAIL_KEY));
    }

    public static Optional<ErrorDetail> toErrorDetail(Metadata metadata) {
        return Optional.ofNullable(metadata.get(ERROR_DETAIL_KEY));
    }

    private static final Pattern ERROR_PARAM_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    public static String toErrorMsg(String msgTemplate, Map<String, ?> paramMap) {
        StringBuilder sb = new StringBuilder();

        int lastIdx = 0;
        Matcher m = ERROR_PARAM_PATTERN.matcher(msgTemplate);
        while (m.find()) {
            sb.append(msgTemplate, lastIdx, m.start());

            Object paramValue = paramMap.get(m.group(1));
            sb.append(paramValue == null ? "" : paramValue);
            lastIdx = m.end();
        }
        if (lastIdx < msgTemplate.length()) {
            sb.append(msgTemplate, lastIdx, msgTemplate.length());
        }
        return sb.toString();
    }

}

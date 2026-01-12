package exchange.antx.common.error;

import io.grpc.Status;

/**
 * 错误key定义
 */
public interface ErrorKey {

    /**
     * grpc status，有固定策略转换为http错误码
     * <a href="https://github.com/grpc/grpc/blob/master/doc/http-grpc-status-mapping.md">可以参考这里</a>
     */
    Status status();

    /**
     * 业务错误码，每个业务使用错误定义时，注意要使用固定的服务前缀
     * 例如：
     * antx-trade-server: TRADE_INVALID_ACCOUNT_ID
     */
    String code();

    /**
     * 默认的错误信息模版，使用参数会替换为最终的错误信息
     */
    String msgTemplate();

}

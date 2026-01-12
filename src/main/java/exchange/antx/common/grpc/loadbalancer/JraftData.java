package exchange.antx.common.grpc.loadbalancer;

import lombok.Data;

@Data
public class JraftData {

    private final String groupId;
    private final String serverId;
    private final long leaderTerm;

}

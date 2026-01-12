package exchange.antx.common.grpc;

import io.grpc.Attributes;

public class GrpcConst {
    /* Name */
    public static final String JRAFT_GROUP_ID_NAME = "jraft.group-id";
    public static final String JRAFT_SERVER_ID_NAME = "jraft.server-id";
    public static final String JRAFT_LEADER_TERM_NAME = "jraft.leader-term";

    /* Attributes.Key */
    public static final Attributes.Key<String> JRAFT_GROUP_ID_ATTR_KEY = Attributes.Key.create(JRAFT_GROUP_ID_NAME);
    public static final Attributes.Key<String> JRAFT_SERVER_ID_ATTR_KEY = Attributes.Key.create(JRAFT_SERVER_ID_NAME);
    public static final Attributes.Key<Long> JRAFT_LEADER_TERM_ATTR_KEY = Attributes.Key.create(JRAFT_LEADER_TERM_NAME);

}

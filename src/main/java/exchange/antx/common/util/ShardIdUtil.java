package exchange.antx.common.util;

import com.google.common.base.Splitter;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class ShardIdUtil {

    private static final int SHARD_ID_MASK = 0xff;

    private static final Splitter MANAGED_SHARD_ID_SPLITTER_1 = Splitter.on(",").trimResults().omitEmptyStrings();
    private static final Splitter MANAGED_SHARD_ID_SPLITTER_2 = Splitter.on("-").trimResults().omitEmptyStrings().limit(2);

    public static SortedSet<Integer> parseManagedShardIdConfigStr(String managedShardIdConfigStr) {
        TreeSet<Integer> shardIdSet = new TreeSet<>();
        for (String shardIdStr : MANAGED_SHARD_ID_SPLITTER_1.split(managedShardIdConfigStr)) {
            List<String> shardIdStrList = MANAGED_SHARD_ID_SPLITTER_2.splitToList(shardIdStr);
            if (shardIdStrList.size() == 1) {
                shardIdSet.add(parseShardId(shardIdStrList.get(0)));
            } else if (shardIdStrList.size() == 2) {
                int start = parseShardId(shardIdStrList.get(0));
                int end = parseShardId(shardIdStrList.get(1));
                if (start > end) {
                    throw new RuntimeException("invalid shardIdStr: " + shardIdStr);
                }
                for (int i = start; i <= end; i++) {
                    shardIdSet.add(i);
                }
            } else {
                throw new RuntimeException("invalid shardIdStr: " + shardIdStr);
            }
        }
        return shardIdSet;
    }

    public static String printManagedShardIdConfigStr(SortedSet<Integer> shardIdSet) {
        StringBuilder sb = new StringBuilder();
        Integer startShardId = null;
        Integer lastShardId = null;
        for (Integer shardId : shardIdSet) {
            if (lastShardId == null) {
                startShardId = shardId;
                lastShardId = shardId;
            } else if (shardId == lastShardId + 1) {
                lastShardId = shardId;
            } else if (shardId > lastShardId + 1) {
                if (lastShardId.equals(startShardId)) {
                    sb.append(printShardId(startShardId)).append(",");
                } else if (lastShardId == startShardId + 1) {
                    sb.append(printShardId(startShardId)).append(",").append(printShardId(lastShardId)).append(",");
                } else {
                    sb.append(printShardId(startShardId)).append("-").append(printShardId(lastShardId)).append(",");
                }
                startShardId = shardId;
                lastShardId = shardId;
            } else {
                throw new RuntimeException("invalid shardIdSet: " + shardIdSet);
            }
        }
        if (startShardId != null) {
            if (lastShardId.equals(startShardId)) {
                sb.append(printShardId(startShardId));
            } else if (lastShardId == startShardId + 1) {
                sb.append(printShardId(startShardId)).append(",").append(printShardId(lastShardId));
            } else {
                sb.append(printShardId(startShardId)).append("-").append(printShardId(lastShardId));
            }
        }
        return sb.toString();
    }

    public static int parseShardId(String shardIdStr) {
        if (!shardIdStr.startsWith("0x") || shardIdStr.length() != 4) {
            throw new RuntimeException("invalid shardId format: " + shardIdStr);
        }
        Integer shardId = Ints.tryParse(shardIdStr.substring(2), 16);
        if (shardId == null || shardId < 0 || shardId > 0xff) {
            throw new RuntimeException("invalid shardId format: " + shardIdStr);
        }
        return shardId;
    }

    public static String printShardId(int shardId) {
        return String.format("0x%02x", shardId);
    }

    public static int toShardId(long accountIdOrOrderIdOrTransactionIdOrShardId) {
        return Ints.checkedCast(accountIdOrOrderIdOrTransactionIdOrShardId & SHARD_ID_MASK);
    }

    public static String toShardIdStr(long accountIdOrOrderIdOrTransactionIdOrShardId) {
        return printShardId(toShardId(accountIdOrOrderIdOrTransactionIdOrShardId));
    }

    public static int toShardId(String ethAddress, String clientAccountId) {
        return Hashing.sha256()
                .hashString(ethAddress.toLowerCase() + ":" + clientAccountId, StandardCharsets.UTF_8)
                .asInt() & SHARD_ID_MASK;
    }
}

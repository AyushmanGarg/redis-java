 import java.util.*;

public class RedisValue {
    String stringValue;       // for SET/GET
    List<String> listValue;   // for RPUSH / LPUSH / LRANGE / LPOP
    Long expiry;          // null if no expiry (timestamp in ms)
    Map<String, Map<String, String>> streamStore;

    RedisValue(String s, Long expiry) {
        this.stringValue = s;
        this.expiry = expiry;
    }

    RedisValue(List<String> list, Long expiry) {
        this.listValue = list;
        this.expiry = expiry;
    }

    RedisValue(String streamKey, Map<String,String> streamMap) {
        this.streamStore = new HashMap<String,Map<String,String>>();
        this.streamStore.put(streamKey, streamMap);
    }

    boolean isString() { return stringValue != null; }
    boolean isList()   { return listValue != null; }
    boolean isStream() { return streamStore != null; }
}
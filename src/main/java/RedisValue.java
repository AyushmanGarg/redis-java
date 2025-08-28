 import java.util.*;

public class RedisValue {
    String stringValue;       // for SET/GET
    List<String> listValue;   // for RPUSH / LPUSH / LRANGE / LPOP
    Long expiry;              // null if no expiry (timestamp in ms)

    RedisValue(String s, Long expiry) {
        this.stringValue = s;
        this.expiry = expiry;
    }

    RedisValue(List<String> list, Long expiry) {
        this.listValue = list;
        this.expiry = expiry;
    }

    boolean isString() { return stringValue != null; }
    boolean isList()   { return listValue != null; }
}
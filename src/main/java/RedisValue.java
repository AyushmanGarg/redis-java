import java.util.*;

public class RedisValue {
    String stringValue;       // for SET/GET
    List<String> listValue;   // for RPUSH / LPUSH / LRANGE / LPOP
    Long expiry;          // null if no expiry (timestamp in ms)
    TreeMap<String, Map<String, String>> streamStore;

    RedisValue(String s, Long expiry) {
        this.stringValue = s;
        this.expiry = expiry;
    }

    RedisValue(List<String> list, Long expiry) {
        this.listValue = list;
        this.expiry = expiry;
    }

    RedisValue( TreeMap<String, Map<String, String>> streamMap) {
        this.streamStore = streamMap;
    }

    boolean isString() { return stringValue != null; }
    boolean isList()   { return listValue != null; }
    boolean isStream() { return streamStore != null; }
    public class StreamIdComparator implements Comparator<String> {
        @Override
        public int compare(String id1, String id2) {
            // Split IDs into two parts: time and sequence
            String[] parts1 = id1.split("-");
            String[] parts2 = id2.split("-");
    
            long time1 = Long.parseLong(parts1[0]);
            long time2 = Long.parseLong(parts2[0]);
    
            if (time1 != time2) {
                return Long.compare(time1, time2); // compare time first
            }
    
            long seq1 = Long.parseLong(parts1[1]);
            long seq2 = Long.parseLong(parts2[1]);
    
            return Long.compare(seq1, seq2); // compare sequence if time is same
        }
    }
}
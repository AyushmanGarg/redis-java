import java.nio.channels.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class RedisClientCommandHandler {
    private Map<String, RedisValue> store;
    public Set<SocketChannel> RedisClients;
    public Boolean is_slave = false;
    public List<SocketChannel> ReplicaSockets = new ArrayList<>();

    private static class BlockedWaiter {
        final SelectionKey key;
        final Long deadlineMs; // null means block indefinitely

        // For XREAD waiters:
        final boolean isXRead;
        final List<String> xreadStreamKeys; // null if not XREAD
        final List<String> xreadIdsRaw;     // resolved ids (no "$" marker) for XREAD

        BlockedWaiter(SelectionKey key, Long deadlineMs) {
            this.key = key;
            this.deadlineMs = deadlineMs;
            this.isXRead = false;
            this.xreadStreamKeys = null;
            this.xreadIdsRaw = null;
        }

        BlockedWaiter(SelectionKey key, Long deadlineMs, List<String> xreadStreamKeys, List<String> xreadIdsRaw) {
            this.key = key;
            this.deadlineMs = deadlineMs;
            this.isXRead = true;
            this.xreadStreamKeys = xreadStreamKeys;
            this.xreadIdsRaw = xreadIdsRaw;
        }
    }

    private final Map<String, Deque<BlockedWaiter>> blocked;

    public RedisClientCommandHandler(Boolean is_slave) {
        this.store = new HashMap<>();
        this.RedisClients = new HashSet<>();
        this.blocked = new HashMap<>();
        this.is_slave = is_slave;
        this.ReplicaSockets = new ArrayList<SocketChannel>();
    }

    public List<String> parseRESP(StringBuilder sb) {
        if (sb.length() == 0)
            return null;

        if (sb.charAt(0) == '*') {
            int lineEnd = sb.indexOf("\r\n");
            if (lineEnd == -1)
                return null;

            int numElements;
            try {
                numElements = Integer.parseInt(sb.substring(1, lineEnd));
            } catch (NumberFormatException e) {
                return null;
            }

            List<String> parts = new ArrayList<>();
            int pos = lineEnd + 2;

            for (int i = 0; i < numElements; i++) {
                if (pos >= sb.length() || sb.charAt(pos) != '$')
                    return null;

                int lenEnd = sb.indexOf("\r\n", pos);
                if (lenEnd == -1)
                    return null;

                int bulkLen;
                try {
                    bulkLen = Integer.parseInt(sb.substring(pos + 1, lenEnd));
                } catch (NumberFormatException e) {
                    return null;
                }
                pos = lenEnd + 2;

                if (bulkLen < 0) {
                    parts.add(null);
                    continue;
                }

                if (pos + bulkLen + 2 > sb.length())
                    return null;

                String bulkStr = sb.substring(pos, pos + bulkLen);
                parts.add(bulkStr);

                pos += bulkLen + 2;
            }

            sb.delete(0, pos);
            System.out.println(parts);
            return parts;
        } else {
            int lineEnd = sb.indexOf("\r\n");
            if (lineEnd == -1)
                return null;
            String line = sb.substring(0, lineEnd).trim();
            sb.delete(0, lineEnd + 2);
            if (line.isEmpty())
                return Collections.emptyList();
            return Arrays.asList(line.split("\\s+"));
        }
    }

    public String handlePING() {
        return "+PONG\r\n";
    }

    public String handleECHO(List<String> cmd) {
        if (cmd.size() < 2)
            return "-ERR wrong number of arguments for 'ECHO'\r\n";
        return "$" + cmd.get(1).length() + "\r\n" + cmd.get(1) + "\r\n";
    }

    public String handleSET(List<String> cmd) {
        if (cmd.size() < 3)
            return "-ERR wrong number of arguments for 'SET'\r\n";

        String key = cmd.get(1);
        String value = cmd.get(2);
        Long expiry = null;

        if (cmd.size() >= 5 && "PX".equalsIgnoreCase(cmd.get(3))) {
            try {
                long px = Long.parseLong(cmd.get(4));
                expiry = System.currentTimeMillis() + px;
            } catch (NumberFormatException e) {
                return "-ERR invalid PX value\r\n";
            }
        }

        store.put(key, new RedisValue(value, expiry));
        return "+OK\r\n";
    }

    public String handleGET(List<String> cmd) {
        if (cmd.size() < 2)
            return "-ERR wrong number of arguments for 'GET'\r\n";

        String key = cmd.get(1);
        RedisValue rv = store.get(key);

        if (rv == null) {
            return "$-1\r\n";
        }

        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            return "$-1\r\n";
        }

        if (!rv.isString()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        String val = rv.stringValue;
        return "$" + val.length() + "\r\n" + val + "\r\n";
    }

    public String handleRPUSH(List<String> cmd) {
        if (cmd.size() < 3)
            return "-ERR wrong number of arguments for 'RPUSH'\r\n";

        String key = cmd.get(1);
        List<String> values = new ArrayList<>();
        for (int i = 2; i < cmd.size(); i++) {
            values.add(cmd.get(i));
        }

        RedisValue rv = store.get(key);
        if (rv == null) {
            List<String> list = new ArrayList<>(values);
            store.put(key, new RedisValue(list, null));
            int newSize = list.size();
            Deque<BlockedWaiter> waiters = blocked.get(key);
            while (waiters != null && !waiters.isEmpty() && !list.isEmpty()) {
                BlockedWaiter blockedWaiter = waiters.pollFirst();
                if (blockedWaiter == null)
                    break;
                String served = list.remove(0);
                respondToWaiter(blockedWaiter.key, key, served);
            }
            return ":" + newSize + "\r\n";
        }

        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            List<String> list = new ArrayList<>(values);
            store.put(key, new RedisValue(list, null));
            int newSize = list.size();
            Deque<BlockedWaiter> waiters = blocked.get(key);
            while (waiters != null && !waiters.isEmpty() && !list.isEmpty()) {
                BlockedWaiter blockedWaiter = waiters.pollFirst();
                if (blockedWaiter == null)
                    break;
                String served = list.remove(0);
                respondToWaiter(blockedWaiter.key, key, served);
            }
            return ":" + newSize + "\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        rv.listValue.addAll(values);
        int newSize = rv.listValue.size();

        Deque<BlockedWaiter> waiters = blocked.get(key);
        while (waiters != null && !waiters.isEmpty() && !rv.listValue.isEmpty()) {
            BlockedWaiter blockedWaiter = waiters.pollFirst();
            if (blockedWaiter == null)
                break;
            String served = rv.listValue.remove(0);
            respondToWaiter(blockedWaiter.key, key, served);
        }
        return ":" + newSize + "\r\n";
    }

    private void respondToWaiter(SelectionKey waiterKey, String key, String val) {
        if (waiterKey == null)
            return;
        StringBuilder out = new StringBuilder();
        out.append("*2\r\n");
        out.append("$").append(key.length()).append("\r\n");
        out.append(key).append("\r\n");
        out.append("$").append(val.length()).append("\r\n");
        out.append(val).append("\r\n");
        try {
            SocketChannel sc = (SocketChannel) waiterKey.channel();
            ByteBuffer resp = ByteBuffer.wrap(out.toString().getBytes(StandardCharsets.UTF_8));
            while (resp.hasRemaining())
                sc.write(resp);
            waiterKey.interestOps(SelectionKey.OP_READ);
        } catch (IOException e) {
            try {
                waiterKey.channel().close();
            } catch (IOException ignored) {
            }
            waiterKey.cancel();
            RedisClients.remove(waiterKey.channel());
        }
    }

    public String handleLPUSH(List<String> cmd) {
        if (cmd.size() < 3)
            return "-ERR wrong number of arguments for 'LPUSH'\r\n";

        String key = cmd.get(1);
        List<String> values = new ArrayList<>();
        for (int i = 2; i < cmd.size(); i++) {
            values.add(cmd.get(i));
        }

        RedisValue rv = store.get(key);

        if (rv == null) {
            List<String> list = new ArrayList<>();
            for (String v : values)
                list.add(0, v);
            store.put(key, new RedisValue(list, null));
            return ":" + list.size() + "\r\n";
        }

        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            List<String> list = new ArrayList<>();
            for (String v : values)
                list.add(0, v);
            store.put(key, new RedisValue(list, null));
            return ":" + list.size() + "\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        for (String v : values) {
            rv.listValue.add(0, v);
        }
        return ":" + rv.listValue.size() + "\r\n";
    }

    public String handleLLEN(List<String> cmd) {
        if (cmd.size() < 2)
            return "-ERR wrong number of arguments for 'LLEN'\r\n";
        String key = cmd.get(1);
        RedisValue rv = store.get(key);

        if (rv == null)
            return ":0\r\n";

        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            return ":0\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        return ":" + rv.listValue.size() + "\r\n";
    }

    public String handleLPOP(List<String> cmd) {
        if (cmd.size() < 2)
            return "-ERR wrong number of arguments for 'LPOP'\r\n";

        String key = cmd.get(1);
        int count = 1;
        boolean withCount = false;
        if (cmd.size() >= 3) {
            try {
                count = Integer.parseInt(cmd.get(2));
                withCount = true;
                if (count < 0)
                    return "-ERR value is not an integer or out of range\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        }

        RedisValue rv = store.get(key);
        if (rv == null) {
            if (withCount)
                return "*0\r\n";
            else
                return "$-1\r\n";
        }

        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            if (withCount)
                return "*0\r\n";
            else
                return "$-1\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        List<String> list = rv.listValue;
        if (list.isEmpty()) {
            if (withCount)
                return "*0\r\n";
            else
                return "$-1\r\n";
        }

        if (withCount) {
            int n = Math.min(count, list.size());
            StringBuilder out = new StringBuilder();
            out.append("*").append(n).append("\r\n");
            for (int i = 0; i < n; i++) {
                String v = list.remove(0);
                out.append("$").append(v.length()).append("\r\n");
                out.append(v).append("\r\n");
            }
            return out.toString();
        } else {
            String v = list.remove(0);
            return "$" + v.length() + "\r\n" + v + "\r\n";
        }
    }

    public String handleBLPOP(List<String> cmd, SelectionKey currentKey) {
        if (cmd.size() < 3)
            return "-ERR wrong number of arguments for 'BLPOP'\r\n";
        String key = cmd.get(1);
        double secs;
        try {
            secs = Double.parseDouble(cmd.get(2).trim());
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        Long deadlineMs = null;
        if (secs > 0) {
            long msLong = (long) Math.ceil(secs * 1000.0);
            deadlineMs = System.currentTimeMillis() + msLong;
        }

        RedisValue rv = store.get(key);
        if (rv != null) {
            if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
                store.remove(key);
                rv = null;
            }
        }

        if (rv != null && rv.isList() && !rv.listValue.isEmpty()) {
            String val = rv.listValue.remove(0);
            StringBuilder out = new StringBuilder();
            out.append("*2\r\n");
            out.append("$").append(key.length()).append("\r\n");
            out.append(key).append("\r\n");
            out.append("$").append(val.length()).append("\r\n");
            out.append(val).append("\r\n");
            return out.toString();
        }

        Deque<BlockedWaiter> waiters = blocked.computeIfAbsent(key, k -> new ArrayDeque<>());
        waiters.addLast(new BlockedWaiter(currentKey, deadlineMs));

        currentKey.interestOps(0);

        return null;
    }

    public void processTimeouts() {
        if (blocked.isEmpty())
            return;
        long now = System.currentTimeMillis();
        List<String> keysToCleanup = new ArrayList<>();
        for (Map.Entry<String, Deque<BlockedWaiter>> entry : blocked.entrySet()) {
            Deque<BlockedWaiter> queue = entry.getValue();
            if (queue == null || queue.isEmpty())
                continue;

            int initialSize = queue.size();
            for (int i = 0; i < initialSize; i++) {
                BlockedWaiter bw = queue.peekFirst();
                if (bw == null)
                    break;
                if (bw.deadlineMs != null && now >= bw.deadlineMs) {
                    queue.pollFirst();
                    SelectionKey sk = bw.key;
                    try {
                        SocketChannel sc = (SocketChannel) sk.channel();
                        String resp = "*-1\r\n";
                        ByteBuffer buf = ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8));
                        while (buf.hasRemaining())
                            sc.write(buf);
                        sk.interestOps(SelectionKey.OP_READ);
                    } catch (IOException e) {
                        try {
                            sk.channel().close();
                        } catch (IOException ignored) {
                        }
                        sk.cancel();
                        RedisClients.remove(sk.channel());
                    }
                    if (bw.isXRead && bw.xreadStreamKeys != null) {
                        for (String otherKey : bw.xreadStreamKeys) {
                            if (otherKey.equals(entry.getKey()))
                                continue;
                            Deque<BlockedWaiter> otherQueue = blocked.get(otherKey);
                            if (otherQueue != null) {
                                otherQueue.remove(bw);
                            }
                        }
                    }
                } else {
                    queue.addLast(queue.pollFirst());
                }
            }
            if (queue.isEmpty())
                keysToCleanup.add(entry.getKey());
        }
        for (String k : keysToCleanup)
            blocked.remove(k);
    }

    public String handleLRANGE(List<String> cmd) {
        if (cmd.size() < 4)
            return "-ERR wrong number of arguments for 'LRANGE'\r\n";

        String key = cmd.get(1);
        int start, stop;
        try {
            start = Integer.parseInt(cmd.get(2));
            stop = Integer.parseInt(cmd.get(3));
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }

        RedisValue rv = store.get(key);
        if (rv == null) {
            return "*0\r\n";
        }

        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            return "*0\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        List<String> list = rv.listValue;
        int size = list.size();

        if (start < 0)
            start = size + start;
        if (stop < 0)
            stop = size + stop;

        if (start < 0)
            start = 0;
        if (stop >= size)
            stop = size - 1;

        if (start > stop || start >= size) {
            return "*0\r\n";
        }

        int count = stop - start + 1;
        StringBuilder out = new StringBuilder();
        out.append("*").append(count).append("\r\n");
        for (int i = start; i <= stop; i++) {
            String v = list.get(i);
            out.append("$").append(v.length()).append("\r\n");
            out.append(v).append("\r\n");
        }
        return out.toString();
    }

    public String handleTYPE(List<String> cmd) {
        if (cmd.size() < 2)
            return "-ERR wrong number of arguments for 'TYPE'\r\n";

        String key = cmd.get(1);
        if (store.containsKey(key)) {
            if (store.get(key).isString())
                return "+string\r\n";
            else if (store.get(key).isStream())
                return "+stream\r\n";
            else if (store.get(key).isList())
                return "+list\r\n";
        }

        return "+none\r\n";
    }

    public String handleXADD(List<String> cmd) {
        if (cmd.size() < 5)
            return "-ERR wrong number of arguments for 'XADD'\r\n";

        String stream_key = cmd.get(1);
        String entry_id = cmd.get(2);
        if (entry_id.contains("*")) {
            entry_id = generateStreamId(stream_key, entry_id);
        }
        if (entry_id.equals("0-0")) {
            return "-ERR The ID specified in XADD must be greater than 0-0\r\n";
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 3; i < cmd.size(); i += 2) {
            String field = cmd.get(i);
            String value = cmd.get(i + 1);
            fields.put(field, value);
        }
        if (store.containsKey(stream_key)) {
            RedisValue rv = store.get(stream_key);
            if (rv.streamStore == null) {
                rv.streamStore = new TreeMap<>(new StreamIdComparator());
            }

            String last_id = rv.streamStore.isEmpty() ? "0-0" : rv.streamStore.lastKey();
            StreamIdComparator comparator = new StreamIdComparator();

            int cmp = comparator.compare(entry_id, last_id);
            if (cmp <= 0) {
                return "-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n";
            }

            rv.streamStore.put(entry_id, fields);

            Deque<BlockedWaiter> waiters = blocked.get(stream_key);
            if (waiters != null && !waiters.isEmpty()) {
                List<BlockedWaiter> snapshot = new ArrayList<>(waiters);
                for (BlockedWaiter bw : snapshot) {
                    if (bw == null) continue;
                    if (bw.isXRead) {
                        removeWaiterFromAllQueues(bw);
                        respondToXReadWaiter(bw);
                    }
                }
            }

            return "$" + entry_id.length() + "\r\n" + entry_id + "\r\n";
        } else {
            TreeMap<String, Map<String, String>> stream = new TreeMap<>(new StreamIdComparator());
            stream.put(entry_id, fields);

            RedisValue streamValue = new RedisValue(stream);
            store.put(stream_key, streamValue);

            Deque<BlockedWaiter> waiters = blocked.get(stream_key);
            if (waiters != null && !waiters.isEmpty()) {
                List<BlockedWaiter> snapshot = new ArrayList<>(waiters);
                for (BlockedWaiter bw : snapshot) {
                    if (bw == null) continue;
                    if (bw.isXRead) {
                        removeWaiterFromAllQueues(bw);
                        respondToXReadWaiter(bw);
                    }
                }
            }

            return "$" + entry_id.length() + "\r\n" + entry_id + "\r\n";
        }
    }

    class StreamIdComparator implements Comparator<String> {
        @Override
        public int compare(String id1, String id2) {
            String[] parts1 = id1.split("-");
            String[] parts2 = id2.split("-");

            long time1 = Long.parseLong(parts1[0]);
            long seq1 = Long.parseLong(parts1[1]);

            long time2 = Long.parseLong(parts2[0]);
            long seq2 = Long.parseLong(parts2[1]);

            if (time1 != time2) {
                return Long.compare(time1, time2);
            }
            return Long.compare(seq1, seq2);
        }
    }

    private String generateStreamId(String stream_key, String partial_id) {
        String[] parts = partial_id.split("-");
        long timestamp;
        long sequence = 0;

        if (parts[0].equals("*")) {
            timestamp = System.currentTimeMillis();
        } else {
            timestamp = Long.parseLong(parts[0]);
        }

        if (store.containsKey(stream_key)) {
            RedisValue rv = store.get(stream_key);
            if (rv.streamStore != null && !rv.streamStore.isEmpty()) {
                String last_id = rv.streamStore.lastKey();
                String[] last_parts = last_id.split("-");
                long last_timestamp = Long.parseLong(last_parts[0]);
                long last_sequence = Long.parseLong(last_parts[1]);

                if (timestamp <= last_timestamp) {
                    timestamp = last_timestamp;
                    sequence = last_sequence + 1;
                } else {
                    sequence = 0;
                }
            }
        }

        if (timestamp == 0 && !store.containsKey(stream_key)) {
            sequence = 1;
        }

        return timestamp + "-" + sequence;
    }

    private String normalizeRangeId(String id, boolean isStart) {
        if (id == null) return null;
        id = id.trim();
        if (id.equals("-")) {
            return "0-0";
        }
        if (id.equals("+")) {
            return Long.toString(Long.MAX_VALUE) + "-" + Long.toString(Long.MAX_VALUE);
        }
        if (id.contains("-")) {
            String[] p = id.split("-", 2);
            String left = p.length > 0 ? p[0] : "";
            String right = p.length > 1 ? p[1] : "";
            if (right == null || right.isEmpty()) {
                return isStart ? (left + "-0") : (left + "-" + Long.toString(Long.MAX_VALUE));
            }
            return id;
        } else {
            return isStart ? (id + "-0") : (id + "-" + Long.toString(Long.MAX_VALUE));
        }
    }

    public String handleXRANGE(List<String> cmd) {
        if (cmd.size() < 4)
            return "-ERR wrong number of arguments for 'XRANGE'\r\n";

        String stream_key = cmd.get(1);
        String start_id_raw = cmd.get(2);
        String end_id_raw = cmd.get(3);

        String start_id = normalizeRangeId(start_id_raw, true);
        String end_id = normalizeRangeId(end_id_raw, false);

        RedisValue rv = store.get(stream_key);
        if (rv == null)
            return "*0\r\n";

        if (!rv.isStream()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        if (rv.streamStore == null || rv.streamStore.isEmpty())
            return "*0\r\n";

        SortedMap<String, Map<String, String>> range;
        try {
            range = rv.streamStore.subMap(start_id, true, end_id, true);
        } catch (IllegalArgumentException e) {
            return "*0\r\n";
        }

        if (range.isEmpty())
            return "*0\r\n";

        StringBuilder out = new StringBuilder();
        out.append("*").append(range.size()).append("\r\n");

        for (Map.Entry<String, Map<String, String>> entry : range.entrySet()) {
            String entryId = entry.getKey();
            Map<String, String> fields = entry.getValue();

            out.append("*2\r\n");

            out.append("$").append(entryId.length()).append("\r\n");
            out.append(entryId).append("\r\n");

            int kvCount = fields.size() * 2;
            out.append("*").append(kvCount).append("\r\n");
            for (Map.Entry<String, String> kv : fields.entrySet()) {
                String f = kv.getKey();
                String v = kv.getValue();
                out.append("$").append(f.length()).append("\r\n");
                out.append(f).append("\r\n");
                out.append("$").append(v.length()).append("\r\n");
                out.append(v).append("\r\n");
            }
        }

        return out.toString();
    }

    /**
     * Blocking-capable XREAD (supports: XREAD [BLOCK <ms>] STREAMS <k1>.. <kN> <id1>.. <idN>).
     * Resolves "$" into the current stream max id at registration time so waiters only
     * receive entries added after the command was issued.
     */
    public String handleXREAD(List<String> cmd, SelectionKey currentKey) {
        if (cmd.size() < 3)
            return "-ERR wrong number of arguments for 'XREAD'\r\n";

        int idx = 1;
        boolean isBlocking = false;
        long blockMs = 0;
        Long deadlineMs = null;

        if (idx < cmd.size() && "BLOCK".equalsIgnoreCase(cmd.get(idx))) {
            if (idx + 1 >= cmd.size())
                return "-ERR syntax error\r\n";
            try {
                blockMs = Long.parseLong(cmd.get(idx + 1));
                isBlocking = true;
                if (blockMs > 0) {
                    deadlineMs = System.currentTimeMillis() + blockMs;
                } else {
                    deadlineMs = null;
                }
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
            idx += 2;
        }

        if (idx >= cmd.size() || !"STREAMS".equalsIgnoreCase(cmd.get(idx)))
            return "-ERR syntax error\r\n";
        idx++;

        int remaining = cmd.size() - idx;
        if (remaining < 2)
            return "-ERR wrong number of arguments for 'XREAD'\r\n";
        if (remaining % 2 != 0)
            return "-ERR syntax error\r\n";

        int numStreams = remaining / 2;
        List<String> streamKeys = new ArrayList<>(numStreams);
        List<String> idsRawCmd = new ArrayList<>(numStreams);

        for (int i = 0; i < numStreams; i++) {
            streamKeys.add(cmd.get(idx + i));
        }
        for (int i = 0; i < numStreams; i++) {
            idsRawCmd.add(cmd.get(idx + numStreams + i));
        }

        // Resolve "$" marker now if present into the current max id for each stream.
        List<String> resolvedIds = new ArrayList<>(numStreams);
        for (int i = 0; i < numStreams; i++) {
            String streamKey = streamKeys.get(i);
            String idRaw = idsRawCmd.get(i);
            if ("$".equals(idRaw)) {
                RedisValue rv = store.get(streamKey);
                if (rv != null && rv.isStream() && rv.streamStore != null && !rv.streamStore.isEmpty()) {
                    resolvedIds.add(rv.streamStore.lastKey());
                } else {
                    resolvedIds.add("0-0");
                }
            } else {
                resolvedIds.add(idRaw);
            }
        }

        // For each stream, collect entries with ID > provided ID (exclusive)
        List<Map.Entry<String, SortedMap<String, Map<String, String>>>> results = new ArrayList<>();
        for (int i = 0; i < numStreams; i++) {
            String streamKey = streamKeys.get(i);
            String idRaw = resolvedIds.get(i);
            String startId = normalizeRangeId(idRaw, true);

            RedisValue rv = store.get(streamKey);
            if (rv == null || !rv.isStream() || rv.streamStore == null || rv.streamStore.isEmpty()) {
                continue;
            }

            SortedMap<String, Map<String, String>> tail;
            try {
                tail = rv.streamStore.tailMap(startId, false);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (tail != null && !tail.isEmpty()) {
                results.add(new AbstractMap.SimpleEntry<>(streamKey, tail));
            }
        }

        if (!results.isEmpty()) {
            StringBuilder out = new StringBuilder();
            out.append("*").append(results.size()).append("\r\n");

            for (Map.Entry<String, SortedMap<String, Map<String, String>>> streamEntry : results) {
                String streamKey = streamEntry.getKey();
                SortedMap<String, Map<String, String>> entries = streamEntry.getValue();

                out.append("*2\r\n");

                out.append("$").append(streamKey.length()).append("\r\n");
                out.append(streamKey).append("\r\n");

                out.append("*").append(entries.size()).append("\r\n");

                for (Map.Entry<String, Map<String, String>> e : entries.entrySet()) {
                    String entryId = e.getKey();
                    Map<String, String> fields = e.getValue();

                    out.append("*2\r\n");

                    out.append("$").append(entryId.length()).append("\r\n");
                    out.append(entryId).append("\r\n");

                    int kvCount = fields.size() * 2;
                    out.append("*").append(kvCount).append("\r\n");
                    for (Map.Entry<String, String> kv : fields.entrySet()) {
                        String f = kv.getKey();
                        String v = kv.getValue();
                        out.append("$").append(f.length()).append("\r\n");
                        out.append(f).append("\r\n");
                        out.append("$").append(v.length()).append("\r\n");
                        out.append(v).append("\r\n");
                    }
                }
            }

            return out.toString();
        }

        if (!isBlocking) {
            return "*0\r\n";
        }

        // Register waiter using resolvedIds so "$" is no longer present at notify time.
        BlockedWaiter waiter = new BlockedWaiter(currentKey, deadlineMs, streamKeys, resolvedIds);

        for (String s : streamKeys) {
            Deque<BlockedWaiter> q = blocked.computeIfAbsent(s, k -> new ArrayDeque<>());
            q.addLast(waiter);
        }

        currentKey.interestOps(0);

        return null;
    }

    private void removeWaiterFromAllQueues(BlockedWaiter waiter) {
        if (waiter == null)
            return;
        if (!waiter.isXRead)
            return;
        if (waiter.xreadStreamKeys == null)
            return;
        for (String k : waiter.xreadStreamKeys) {
            Deque<BlockedWaiter> q = blocked.get(k);
            if (q != null) {
                q.remove(waiter);
                if (q.isEmpty())
                    blocked.remove(k);
            }
        }
    }

    private void respondToXReadWaiter(BlockedWaiter waiter) {
        if (waiter == null || !waiter.isXRead)
            return;
        SelectionKey sk = waiter.key;
        if (sk == null) {
            return;
        }
        List<String> streamKeys = waiter.xreadStreamKeys;
        List<String> idsRaw = waiter.xreadIdsRaw; // already resolved (no "$")
        List<Map.Entry<String, SortedMap<String, Map<String, String>>>> results = new ArrayList<>();

        for (int i = 0; i < streamKeys.size(); i++) {
            String streamKey = streamKeys.get(i);
            String idRaw = idsRaw.get(i);
            String startId = normalizeRangeId(idRaw, true);

            RedisValue rv = store.get(streamKey);
            if (rv == null || !rv.isStream() || rv.streamStore == null || rv.streamStore.isEmpty())
                continue;

            SortedMap<String, Map<String, String>> tail;
            try {
                tail = rv.streamStore.tailMap(startId, false);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (tail != null && !tail.isEmpty()) {
                results.add(new AbstractMap.SimpleEntry<>(streamKey, tail));
            }
        }

        String resp;
        if (results.isEmpty()) {
            resp = "$-1\r\n";
        } else {
            StringBuilder out = new StringBuilder();
            out.append("*").append(results.size()).append("\r\n");

            for (Map.Entry<String, SortedMap<String, Map<String, String>>> streamEntry : results) {
                String streamKey = streamEntry.getKey();
                SortedMap<String, Map<String, String>> entries = streamEntry.getValue();

                out.append("*2\r\n");

                out.append("$").append(streamKey.length()).append("\r\n");
                out.append(streamKey).append("\r\n");

                out.append("*").append(entries.size()).append("\r\n");

                for (Map.Entry<String, Map<String, String>> e : entries.entrySet()) {
                    String entryId = e.getKey();
                    Map<String, String> fields = e.getValue();

                    out.append("*2\r\n");

                    out.append("$").append(entryId.length()).append("\r\n");
                    out.append(entryId).append("\r\n");

                    int kvCount = fields.size() * 2;
                    out.append("*").append(kvCount).append("\r\n");
                    for (Map.Entry<String, String> kv : fields.entrySet()) {
                        String f = kv.getKey();
                        String v = kv.getValue();
                        out.append("$").append(f.length()).append("\r\n");
                        out.append(f).append("\r\n");
                        out.append("$").append(v.length()).append("\r\n");
                        out.append(v).append("\r\n");
                    }
                }
            }

            resp = out.toString();
        }

        try {
            SocketChannel sc = (SocketChannel) sk.channel();
            ByteBuffer buf = ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining())
                sc.write(buf);
            sk.interestOps(SelectionKey.OP_READ);
        } catch (IOException e) {
            try {
                sk.channel().close();
            } catch (IOException ignored) {
            }
            sk.cancel();
            RedisClients.remove(sk.channel());
        }
    }

    public String handleINFO() {
        StringBuilder info = new StringBuilder();

        if (is_slave) {
            info.append("role:slave\r\n");
        } else {
            info.append("role:master\r\n");
            info.append("master_replid:8371b4fb1155b71f4a04d3e1bc3e18c4a990aeeb\r\n");
            info.append("master_repl_offset:0\r\n");
        }
    
        String data = info.toString();
        return "$" + data.length() + "\r\n" + data + "\r\n";
    }

    public String handleREPLCONF(List<String> cmd) {
        System.out.println(cmd);
        
        return "+OK\r\n";
    }

    public StringBytesPair handlePSYNC() {
        String str = "+FULLRESYNC 8371b4fb1155b71f4a04d3e1bc3e18c4a990aeeb 0\r\n";

        // +FULLRESYNC 8371b4fb1155b71f4a04d3e1bc3e18c4a990aeeb 0\r\n
        String base64_representation_rdb = "UkVESVMwMDEx+glyZWRpcy12ZXIFNy4yLjD6CnJlZGlzLWJpdHPAQPoFY3RpbWXCbQi8ZfoIdXNlZC1tZW3CsMQQAPoIYW9mLWJhc2XAAP/wbjv+wP9aog==";
        byte[] rdbBytes = Base64.getDecoder().decode(base64_representation_rdb);
        // String rdbString = new String(rdbBytes, StandardCharsets.UTF_8);
        // $<length_of_file>\r\n
        // slave.getOutputStream().write(("$"+hex_representation_rdb.length()+"\r\n"+hex_representation_rdb).getBytes());
        // slave.getOutputStream().flush();
        

        StringBytesPair reply = new StringBytesPair(str, rdbBytes);
        return reply;
    }

}
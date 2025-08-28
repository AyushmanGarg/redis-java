import java.nio.channels.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;

public class RedisClientCommandHandler {
    private Map<String, RedisValue> store;
    public Set<SocketChannel> RedisClients;
    private static class BlockedWaiter {
        final SelectionKey key;
        final Long deadlineMs; // null means block indefinitely

        BlockedWaiter(SelectionKey key, Long deadlineMs) {
            this.key = key;
            this.deadlineMs = deadlineMs;
        }
    }

    private final Map<String, Deque<BlockedWaiter>> blocked;

    public RedisClientCommandHandler() {
        this.store = new HashMap<>();
        this.RedisClients = new HashSet<>();
        this.blocked = new HashMap<>();
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
                    // NULL bulk string ($-1), represent as null
                    parts.add(null);
                    continue;
                }

                if (pos + bulkLen + 2 > sb.length())
                    return null; // not all bytes arrived yet

                String bulkStr = sb.substring(pos, pos + bulkLen);
                parts.add(bulkStr);

                pos += bulkLen + 2; // skip bulk data and trailing \r\n
            }

            sb.delete(0, pos); // remove parsed command
            System.out.println(parts);
            return parts;
        } else {
            // Inline command (like "PING\r\n")
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

        // Handle optional PX (case-insensitive)
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

        // Check expiry
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

        // Append all values to the list first, compute new size to return
        RedisValue rv = store.get(key);
        if (rv == null) {
            List<String> list = new ArrayList<>(values);
            store.put(key, new RedisValue(list, null));
            int newSize = list.size();
            // After appending, serve any blocked waiters by popping from left
            Deque<BlockedWaiter> waiters = blocked.get(key);
            while (waiters != null && !waiters.isEmpty() && !list.isEmpty()) {
                BlockedWaiter blockedWaiter = waiters.pollFirst();
                if (blockedWaiter == null) break;
                String served = list.remove(0);
                respondToWaiter(blockedWaiter.key, key, served);
            }
            return ":" + newSize + "\r\n";
        }

        // check expiry before using
        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            List<String> list = new ArrayList<>(values);
            store.put(key, new RedisValue(list, null));
            int newSize = list.size();
            Deque<BlockedWaiter> waiters = blocked.get(key);
            while (waiters != null && !waiters.isEmpty() && !list.isEmpty()) {
                BlockedWaiter blockedWaiter = waiters.pollFirst();
                if (blockedWaiter == null) break;
                String served = list.remove(0);
                respondToWaiter(blockedWaiter.key, key, served);
            }
            return ":" + newSize + "\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        // Existing list: append and compute new size
        rv.listValue.addAll(values);
        int newSize = rv.listValue.size();

        // After appending, serve any blocked waiters by popping from left
        Deque<BlockedWaiter> waiters = blocked.get(key);
        while (waiters != null && !waiters.isEmpty() && !rv.listValue.isEmpty()) {
            BlockedWaiter blockedWaiter = waiters.pollFirst();
            if (blockedWaiter == null) break;
            String served = rv.listValue.remove(0);
            respondToWaiter(blockedWaiter.key, key, served);
        }
        return ":" + newSize + "\r\n";
    }

    private void respondToWaiter(SelectionKey waiterKey, String key, String val) {
        if (waiterKey == null) return;
        StringBuilder out = new StringBuilder();
        out.append("*2\r\n");
        out.append("$").append(key.length()).append("\r\n");
        out.append(key).append("\r\n");
        out.append("$").append(val.length()).append("\r\n");
        out.append(val).append("\r\n");
        try {
            SocketChannel sc = (SocketChannel) waiterKey.channel();
            ByteBuffer resp = ByteBuffer.wrap(out.toString().getBytes(StandardCharsets.UTF_8));
            while (resp.hasRemaining()) sc.write(resp);
            waiterKey.interestOps(SelectionKey.OP_READ);
        } catch (IOException e) {
            try { waiterKey.channel().close(); } catch (IOException ignored) {}
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
            // create new list, LPUSH inserts left-to-right at head,
            // so iterate values and add at 0 in order to get last element first
            List<String> list = new ArrayList<>();
            for (String v : values)
                list.add(0, v);
            store.put(key, new RedisValue(list, null));
            return ":" + list.size() + "\r\n";
        }

        // check expiry before using
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

        // Prepend each value in order so that LPUSH a b c => [c,b,a]
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

        // expiry check
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
        // LPOP key [count]
        if (cmd.size() < 2)
            return "-ERR wrong number of arguments for 'LPOP'\r\n";

        String key = cmd.get(1);
        int count = 1; // default
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
            // key missing
            if (withCount)
                return "*0\r\n"; // empty array
            else
                return "$-1\r\n"; // null bulk
        }

        // expiry check
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
                String v = list.remove(0); // pop from left
                out.append("$").append(v.length()).append("\r\n");
                out.append(v).append("\r\n");
            }
            return out.toString();
        } else {
            // single element -> return bulk string
            String v = list.remove(0);
            return "$" + v.length() + "\r\n" + v + "\r\n";
        }
    }

    public String handleBLPOP(List<String> cmd, SelectionKey currentKey) {
        // BLPOP key timeout (timeout is seconds). In tests timeout always 0 (block
        // indefinitely)
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
        // if list exists and has elements -> behave like LPOP and return immediately as
        // array [key, value]
        if (rv != null) {
            // expiry check
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

        // Otherwise, no element now -> block this client indefinitely (tests use
        // timeout 0)
        Deque<BlockedWaiter> waiters = blocked.computeIfAbsent(key, k -> new ArrayDeque<>());
        waiters.addLast(new BlockedWaiter(currentKey, deadlineMs));

        // disable read interest for this key so we don't try to read more from a
        // blocked client
        currentKey.interestOps(0);

        // return null to indicate no immediate response (client is blocked)
        return null;
    }

    public void processTimeouts() {
        if (blocked.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> keysToCleanup = new ArrayList<>();
        for (Map.Entry<String, Deque<BlockedWaiter>> entry : blocked.entrySet()) {
            Deque<BlockedWaiter> queue = entry.getValue();
            if (queue == null || queue.isEmpty()) continue;

            int initialSize = queue.size();
            for (int i = 0; i < initialSize; i++) {
                BlockedWaiter bw = queue.peekFirst();
                if (bw == null) break;
                // Only time out those with deadlines; leave indefinite ones in place
                if (bw.deadlineMs != null && now >= bw.deadlineMs) {
                    queue.pollFirst();
                    SelectionKey sk = bw.key;
                    try {
                        SocketChannel sc = (SocketChannel) sk.channel();
                        String resp = "$-1\r\n"; // BLPOP timeout => null bulk in RESP2
                        ByteBuffer buf = ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8));
                        while (buf.hasRemaining()) sc.write(buf);
                        sk.interestOps(SelectionKey.OP_READ);
                    } catch (IOException e) {
                        try { sk.channel().close(); } catch (IOException ignored) {}
                        sk.cancel();
                        RedisClients.remove(sk.channel());
                    }
                } else {
                    // Not timed out (either no deadline or deadline not reached). Move to back
                    // to ensure fair processing and allow checking subsequent items.
                    queue.addLast(queue.pollFirst());
                }
            }
            if (queue.isEmpty()) keysToCleanup.add(entry.getKey());
        }
        for (String k : keysToCleanup) blocked.remove(k);
    }

    public String handleLRANGE(List<String> cmd) {
        // LRANGE key start stop
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
            // non-existing list => empty array
            return "*0\r\n";
        }

        // check expiry
        if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
            store.remove(key);
            return "*0\r\n";
        }

        if (!rv.isList()) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        List<String> list = rv.listValue;
        int size = list.size();

        // convert negative indexes
        if (start < 0)
            start = size + start;
        if (stop < 0)
            stop = size + stop;

        // clamp
        if (start < 0)
            start = 0;
        if (stop >= size)
            stop = size - 1;

        if (start > stop || start >= size) {
            return "*0\r\n";
        }

        // build RESP array of elements from start..stop inclusive
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
        if(cmd.size()<2)
            return "-ERR wrong number of arguments for 'TYPE'\r\n";

        String key = cmd.get(1);
        if(store.containsKey(key)) 
            return "+string\r\n";

        return "+none\r\n";
    }
}
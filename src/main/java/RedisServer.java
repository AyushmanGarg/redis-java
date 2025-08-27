import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RedisServer {
    private ServerSocketChannel RedisServer;
    private Set<SocketChannel> RedisClients = new HashSet<>();
    private Selector RedisServerSelector;

    // Value container that can hold either a string or a list, plus optional expiry
    private static class RedisValue {
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

    private Map<String, RedisValue> store = new HashMap<>();

    public RedisServer(int port) {
        try {
            this.RedisServerSelector = Selector.open();
            this.RedisServer = ServerSocketChannel.open();
            this.RedisServer.bind(new InetSocketAddress(port));
            this.RedisServer.configureBlocking(false);
            this.RedisServer.register(RedisServerSelector, SelectionKey.OP_ACCEPT);
            System.out.println("Redis Single Threaded server listening on " + port);
        } catch (Exception e) {
            System.err.println("Redis Server Setup error: " + e.getMessage());
        }
    }

    public void handleAccept(SelectionKey key) {
        try {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel client = ssc.accept();
            client.configureBlocking(false);
            client.register(RedisServerSelector, SelectionKey.OP_READ, new StringBuilder());
            RedisClients.add(client);
            System.out.println("Accepted: " + client.getRemoteAddress() +
                    " (total clients: " + RedisClients.size() + ")");
        } catch (Exception e) {
            System.err.println("Redis Server handle client error:" + e.getMessage());
        }
    }

    public void handleRead(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        StringBuilder sb = (StringBuilder) key.attachment();
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        try {
            int bytesRead = client.read(buffer);
            if (bytesRead == -1) {
                RedisClients.remove(client);
                client.close();
                return;
            }

            if (bytesRead > 0) {
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                String incoming = new String(bytes, StandardCharsets.UTF_8);
                sb.append(incoming); // accumulate input
                buffer.clear();

                // Try to parse commands
                while (true) {
                    List<String> command = parseRESP(sb);
                    if (command == null)
                        break; // not enough data yet

                    String reply = executeCommand(command);
                    ByteBuffer response = ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8));
                    while (response.hasRemaining()) {
                        client.write(response);
                    }
                }
            }
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
            RedisClients.remove(client);
            System.err.println("Redis Server read error: " + e.getMessage());
        }
    }

    // Parse RESP from the buffer, return null if incomplete
    private List<String> parseRESP(StringBuilder sb) {
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
                return null; // malformed header; caller may choose to handle differently
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
            return parts;
        } else {
            // Inline command (like "PING\r\n")
            int lineEnd = sb.indexOf("\r\n");
            if (lineEnd == -1)
                return null;
            String line = sb.substring(0, lineEnd).trim();
            sb.delete(0, lineEnd + 2);
            if (line.isEmpty()) return Collections.emptyList();
            return Arrays.asList(line.split("\\s+"));
        }
    }

    // Execute supported commands
    private String executeCommand(List<String> cmd) {
        if (cmd.isEmpty())
            return "-ERR empty command\r\n";

        String op = cmd.get(0).toUpperCase();

        switch (op) {
            case "PING":
                return "+PONG\r\n";

            case "ECHO":
                if (cmd.size() < 2)
                    return "-ERR wrong number of arguments for 'ECHO'\r\n";
                return "$" + cmd.get(1).length() + "\r\n" + cmd.get(1) + "\r\n";

            case "SET": {
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

            case "GET": {
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

            case "RPUSH": {
                if (cmd.size() < 3)
                    return "-ERR wrong number of arguments for 'RPUSH'\r\n";

                String key = cmd.get(1);
                List<String> values = new ArrayList<>();
                for (int i = 2; i < cmd.size(); i++) {
                    values.add(cmd.get(i));
                }

                RedisValue rv = store.get(key);

                if (rv == null) {
                    // create new list
                    List<String> list = new ArrayList<>(values);
                    store.put(key, new RedisValue(list, null));
                    return ":" + list.size() + "\r\n";
                }

                // check expiry before using
                if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
                    // expired, treat as non-existing
                    store.remove(key);
                    List<String> list = new ArrayList<>(values);
                    store.put(key, new RedisValue(list, null));
                    return ":" + list.size() + "\r\n";
                }

                if (!rv.isList()) {
                    return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
                }

                rv.listValue.addAll(values);
                return ":" + rv.listValue.size() + "\r\n";
            }

            case "LPUSH": {
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
                    for (String v : values) list.add(0, v);
                    store.put(key, new RedisValue(list, null));
                    return ":" + list.size() + "\r\n";
                }

                // check expiry before using
                if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
                    store.remove(key);
                    List<String> list = new ArrayList<>();
                    for (String v : values) list.add(0, v);
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

            case "LLEN": {
                if (cmd.size() < 2) return "-ERR wrong number of arguments for 'LLEN'\r\n";
                String key = cmd.get(1);
                RedisValue rv = store.get(key);

                if (rv == null) return ":0\r\n";

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

            case "LPOP": {
                // LPOP key [count]
                if (cmd.size() < 2) return "-ERR wrong number of arguments for 'LPOP'\r\n";

                String key = cmd.get(1);
                int count = 1; // default
                boolean withCount = false;
                if (cmd.size() >= 3) {
                    try {
                        count = Integer.parseInt(cmd.get(2));
                        withCount = true;
                        if (count < 0) return "-ERR value is not an integer or out of range\r\n";
                    } catch (NumberFormatException e) {
                        return "-ERR value is not an integer or out of range\r\n";
                    }
                }

                RedisValue rv = store.get(key);
                if (rv == null) {
                    // key missing
                    if (withCount) return "*0\r\n"; // empty array
                    else return "$-1\r\n"; // null bulk
                }

                // expiry check
                if (rv.expiry != null && System.currentTimeMillis() > rv.expiry) {
                    store.remove(key);
                    if (withCount) return "*0\r\n";
                    else return "$-1\r\n";
                }

                if (!rv.isList()) {
                    return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
                }

                List<String> list = rv.listValue;
                if (list.isEmpty()) {
                    if (withCount) return "*0\r\n";
                    else return "$-1\r\n";
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
                    // if list becomes empty you may keep empty list or remove key; we keep empty list
                    return "$" + v.length() + "\r\n" + v + "\r\n";
                }
            }

            case "LRANGE": {
                // LRANGE key start stop
                if (cmd.size() < 4) return "-ERR wrong number of arguments for 'LRANGE'\r\n";

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
                if (start < 0) start = size + start;
                if (stop < 0)  stop  = size + stop;

                // clamp
                if (start < 0) start = 0;
                if (stop >= size) stop = size - 1;

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

            default:
                return "-ERR unknown command '" + cmd.get(0) + "'\r\n";
        }
    }

    public void start() {
        try {
            while (true) {
                RedisServerSelector.select();
                Iterator<SelectionKey> it = RedisServerSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (key.isAcceptable())
                        handleAccept(key);
                    if (key.isReadable())
                        handleRead(key);
                }
            }
        } catch (IOException e) {
            System.err.println("Redis Server Start error: " + e.getMessage());
        }
    }
}





// must delete the content from string builder after processing
// need of bytebuffer and stringbuilder
// NIO in detail
// How sockets communiucate with cli and how data flows
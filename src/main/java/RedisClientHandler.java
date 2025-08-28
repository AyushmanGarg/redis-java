import java.nio.channels.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;

public class RedisClientHandler {
    private Selector RedisServerSelector;
    RedisClientCommandHandler cmdHandler;

    public RedisClientHandler(Selector RedisServerSelector) {
        this.RedisServerSelector = RedisServerSelector;
        this.cmdHandler = new RedisClientCommandHandler();
    }

    public void handleAccept(SelectionKey key) {
        try {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel client = ssc.accept();
            client.configureBlocking(false);
            client.register(RedisServerSelector, SelectionKey.OP_READ, new StringBuilder());
            cmdHandler.RedisClients.add(client);
            System.out.println("Accepted: " + client.getRemoteAddress() +
                    " (total clients: " + cmdHandler.RedisClients.size() + ")");
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
                cmdHandler.RedisClients.remove(client);
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
                    List<String> command = cmdHandler.parseRESP(sb);
                    if (command == null)
                        break; // not enough data yet

                    String reply = executeCommand(command, key);
                    if (reply != null) {
                        ByteBuffer response = ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8));
                        while (response.hasRemaining()) {
                            client.write(response);
                        }
                    }
                }
            }
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
            cmdHandler.RedisClients.remove(client);
            System.err.println("Redis Server read error: " + e.getMessage());
        }
    }

    // Execute supported commands
    private String executeCommand(List<String> cmd, SelectionKey currentKey) {
        if (cmd.isEmpty())
            return "-ERR empty command\r\n";

        String op = cmd.get(0).toUpperCase();

        switch (op) {
            case "PING":
                return cmdHandler.handlePING();

            case "ECHO":
                return cmdHandler.handleECHO(cmd);

            case "SET":
                return cmdHandler.handleSET(cmd);

            case "GET":
                return cmdHandler.handleGET(cmd);

            case "RPUSH":
                return cmdHandler.handleRPUSH(cmd);

            case "LPUSH":
                return cmdHandler.handleLPUSH(cmd);

            case "LLEN":
                return cmdHandler.handleLLEN(cmd);

            case "LPOP":
                return cmdHandler.handleLPOP(cmd);

            case "BLPOP":
                return cmdHandler.handleBLPOP(cmd, currentKey);

            case "LRANGE":
                return cmdHandler.handleLRANGE(cmd);
            case "TYPE":
                return cmdHandler.handleTYPE(cmd);

            default:
                return "-ERR unknown command '" + cmd.get(0) + "'\r\n";
        }
    }
}

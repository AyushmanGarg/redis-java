import java.nio.channels.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;

public class RedisClientHandler {
    private Selector RedisServerSelector;
    RedisClientCommandHandler cmdHandler;
    Boolean is_slave = false;

    public RedisClientHandler(Selector RedisServerSelector, Boolean is_slave) {
        this.RedisServerSelector = RedisServerSelector;
        this.cmdHandler = new RedisClientCommandHandler(is_slave);
        this.is_slave = is_slave;
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

                    StringBytesPair reply = executeCommand(command, key);

                    if (reply.getBytes() == null && reply.getString()!=null) {
                        ByteBuffer response = ByteBuffer.wrap(reply.getString().getBytes(StandardCharsets.UTF_8));
                        while (response.hasRemaining()) {
                            client.write(response);
                        }
                    } else if(reply.getString()!=null) {
                        ByteBuffer response = ByteBuffer.wrap(reply.getString().getBytes(StandardCharsets.UTF_8));
                        while (response.hasRemaining()) {
                            client.write(response);
                        }

                        // 1. Send RESP bulk string header
                        String bulk_resp_header = "$" + reply.getBytes().length + "\r\n";
                        client.write(ByteBuffer.wrap(bulk_resp_header.getBytes(StandardCharsets.UTF_8)));

                        // 2. Send the actual binary data
                        client.write(ByteBuffer.wrap(reply.getBytes()));

                        // 3. Send RESP trailer
                        // client.write(ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.UTF_8)));

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
    private StringBytesPair executeCommand(List<String> cmd, SelectionKey currentKey) {
        if (cmd.isEmpty())
            return new StringBytesPair("-ERR empty command\r\n", null);

        String op = cmd.get(0).toUpperCase();

        switch (op) {
            case "PING":
                return new StringBytesPair(cmdHandler.handlePING(), null);

            case "ECHO":
                return new StringBytesPair(cmdHandler.handleECHO(cmd), null);

            case "SET":
            return new StringBytesPair(cmdHandler.handleSET(cmd), null);

            case "GET":
                return new StringBytesPair(cmdHandler.handleGET(cmd), null);

            case "RPUSH":
                return new StringBytesPair(cmdHandler.handleRPUSH(cmd), null);

            case "LPUSH":
                return new StringBytesPair(cmdHandler.handleLPUSH(cmd), null);

            case "LLEN":
                return new StringBytesPair(cmdHandler.handleLLEN(cmd), null);

            case "LPOP":
                return new StringBytesPair(cmdHandler.handleLPOP(cmd), null);

            case "BLPOP":
                return new StringBytesPair(cmdHandler.handleBLPOP(cmd, currentKey), null);

            case "LRANGE":
                return new StringBytesPair(cmdHandler.handleLRANGE(cmd), null);

            case "TYPE":
                return new StringBytesPair(cmdHandler.handleTYPE(cmd), null);
            
            case "XADD":
                return new StringBytesPair(cmdHandler.handleXADD(cmd), null);
            
            case "XRANGE":
                return new StringBytesPair(cmdHandler.handleXRANGE(cmd), null);
            
            case "XREAD":
                return new StringBytesPair(cmdHandler.handleXREAD(cmd, currentKey), null);

            case "INFO":
                return new StringBytesPair(cmdHandler.handleINFO(), null);
            
            case "REPLCONF":
                return new StringBytesPair(cmdHandler.handleREPLCONF(), null);
            
            case "PSYNC":
                return cmdHandler.handlePSYNC();

            default:
            return new StringBytesPair("-ERR unknown command\r\n", null);
        }
    }
}
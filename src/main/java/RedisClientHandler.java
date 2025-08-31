import java.nio.channels.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.net.Socket;

public class RedisClientHandler {
    private Selector RedisServerSelector;
    RedisClientCommandHandler cmdHandler;
    Boolean is_slave = false;
    // List<Socket> ReplicaSockets;

    public RedisClientHandler(Selector RedisServerSelector, Boolean is_slave) {
        this.RedisServerSelector = RedisServerSelector;
        this.cmdHandler = new RedisClientCommandHandler(is_slave);
        this.is_slave = is_slave;
        // if(is_slave) ReplicaSockets = new List<Socket>();
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

                while (true) {
                    List<String> command = cmdHandler.parseRESP(sb);
                    if (command == null)
                        break; // not enough data yet

                    StringBytesPair reply = executeCommand(command, key);

                    // === Send response back to client (same as before) ===
                    if (reply.getBytes() == null && reply.getString() != null) {
                        ByteBuffer response = ByteBuffer.wrap(reply.getString().getBytes(StandardCharsets.UTF_8));
                        while (response.hasRemaining())
                            client.write(response);
                    } else if (reply.getString() != null) {
                        ByteBuffer response = ByteBuffer.wrap(reply.getString().getBytes(StandardCharsets.UTF_8));
                        while (response.hasRemaining())
                            client.write(response);

                        String bulk_resp_header = "$" + reply.getBytes().length + "\r\n";
                        client.write(ByteBuffer.wrap(bulk_resp_header.getBytes(StandardCharsets.UTF_8)));
                        client.write(ByteBuffer.wrap(reply.getBytes()));
                    }

                    // === Replication handling starts here ===
                    if (!this.is_slave) { // Only propagate if this server is master
                        boolean fromReplica = cmdHandler.ReplicaSockets.contains(client);
                        if (!fromReplica) {
                            String op = command.get(0).toUpperCase();
                            if (op.equals("SET") || op.equals("DEL") || op.equals("RPUSH") ||
                                    op.equals("LPUSH") || op.equals("LPOP") || op.equals("XADD")) {

                                // Build RESP array for original command
                                StringBuilder sbuf = new StringBuilder();
                                sbuf.append("*").append(command.size()).append("\r\n");
                                for (String arg : command) {
                                    byte[] argBytes = arg.getBytes(StandardCharsets.UTF_8);
                                    sbuf.append("$").append(argBytes.length).append("\r\n");
                                    sbuf.append(arg).append("\r\n");
                                }
                                byte[] payload = sbuf.toString().getBytes(StandardCharsets.UTF_8);

                                // Send to ALL replicas
                                List<SocketChannel> deadReplicas = new ArrayList<>();
                                for (SocketChannel replica : cmdHandler.ReplicaSockets) {
                                    try {
                                        ByteBuffer out = ByteBuffer.wrap(payload);
                                        while (out.hasRemaining())
                                            replica.write(out);
                                    } catch (IOException e) {
                                        deadReplicas.add(replica);
                                    }
                                }
                                // Remove replicas that disconnected
                                cmdHandler.ReplicaSockets.removeAll(deadReplicas);
                            }
                        }
                    }

                    // === If this client is a replica performing PSYNC, track it ===
                    if ("PSYNC".equalsIgnoreCase(command.get(0)) && !this.is_slave) {
                        if (!cmdHandler.ReplicaSockets.contains(client)) {
                            cmdHandler.ReplicaSockets.add(client);
                            System.out.println("Replica registered: " + client);
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
                return new StringBytesPair(cmdHandler.handleREPLCONF(cmd), null);

            case "PSYNC":
                return cmdHandler.handlePSYNC();

            default:
                return new StringBytesPair("-ERR unknown command\r\n", null);
        }
    }
}
import java.io.BufferedReader;
import java.io.OutputStream;

public class RedisClientCommandHandler {
    private final OutputStream outputStream;
    private final BufferedReader inputStream;
    public RedisClientCommandHandler(OutputStream outputStream, BufferedReader inputStream) {
        this.outputStream = outputStream;
        this.inputStream = inputStream;
    }

    public void handlePING() {
        try {
            outputStream.write("+PONG\r\n".getBytes());
            outputStream.flush();
        } catch (Exception e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    public void handleECHO() {
        try {
            inputStream.readLine();
            outputStream.write(("+" + inputStream.readLine() + "\r\n").getBytes());
            outputStream.flush();
        } catch (Exception e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
}



// public class SingleThreadPingServer {
//     public static void main(String[] args) throws IOException {
//         // int port = args.length > 0 ? Integer.parseInt(args[0]) : 6573;
//         // Selector selector = Selector.open();
//         // ServerSocketChannel server = ServerSocketChannel.open();
//         server.bind(new InetSocketAddress(port));
//         server.configureBlocking(false);
//         server.register(selector, SelectionKey.OP_ACCEPT);

//         while (true) {
//             selector.select();
//             Iterator<SelectionKey> it = selector.selectedKeys().iterator();
//             while (it.hasNext()) {
//                 SelectionKey key = it.next(); it.remove();
//                 try {
//                     if (key.isAcceptable()) {
//                         SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
//                         if (client != null) {
//                             client.configureBlocking(false);
//                             SelectionKey k = client.register(selector, SelectionKey.OP_READ);
//                             k.attach(new Connection());
//                         }
//                     } else if (key.isReadable()) {
//                         SocketChannel ch = (SocketChannel) key.channel();
//                         Connection c = (Connection) key.attachment();
//                         ByteBuffer buf = ByteBuffer.allocate(4096);
//                         int r = ch.read(buf);
//                         if (r == -1) { closeKey(key); continue; }
//                         if (r > 0) {
//                             buf.flip();
//                             c.in.append(StandardCharsets.UTF_8.decode(buf).toString());
//                             int idx;
//                             while ((idx = indexOfNewline(c.in)) >= 0) {
//                                 String line = c.in.substring(0, idx).replaceAll("\r$", "");
//                                 c.in.delete(0, idx + 1);
//                                 String reply = "ping".equalsIgnoreCase(line.trim()) ? "RECEIVED\r\n" : "UNKNOWN\r\n";
//                                 c.out.add(ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8)));
//                                 key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
//                             }
//                         }
//                     } else if (key.isWritable()) {
//                         SocketChannel ch = (SocketChannel) key.channel();
//                         Connection c = (Connection) key.attachment();
//                         while (!c.out.isEmpty()) {
//                             ByteBuffer b = c.out.peek();
//                             ch.write(b);
//                             if (b.hasRemaining()) break;
//                             c.out.poll();
//                         }
//                         if (c.out.isEmpty()) key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
//                     }
//                 } catch (IOException ex) {
//                     closeKey(key);
//                 }
//             }
//         }
//     }

//     static int indexOfNewline(StringBuilder sb) {
//         for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == '\n') return i;
//         return -1;
//     }

//     static void closeKey(SelectionKey key) {
//         try { if (key != null) { key.cancel(); Channel ch = key.channel(); if (ch != null) ch.close(); } } catch (IOException ignored) {}
//     }

//     static class Connection {
//         final StringBuilder in = new StringBuilder();
//         final Queue<ByteBuffer> out = new ArrayDeque<>();
//     }
// }
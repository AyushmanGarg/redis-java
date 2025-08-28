import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.*;

public class RedisServer {
    private ServerSocketChannel RedisServer;
    private Selector RedisServerSelector;
    RedisClientHandler redisClientHandler;

    public RedisServer(int port) {
        try {
            this.RedisServerSelector = Selector.open();
            this.RedisServer = ServerSocketChannel.open();
            this.RedisServer.bind(new InetSocketAddress(port));
            this.RedisServer.configureBlocking(false);
            this.RedisServer.register(RedisServerSelector, SelectionKey.OP_ACCEPT);
            this.redisClientHandler = new RedisClientHandler(RedisServerSelector);
            System.out.println("Redis Single Threaded server listening on " + port);
        } catch (Exception e) {
            System.err.println("Redis Server Setup error: " + e.getMessage());
        }
    }

    public void start() {
        try {
            while (true) {
                // Wake up periodically to process BLPOP timeouts
                RedisServerSelector.select(50);
                Iterator<SelectionKey> it = RedisServerSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (key.isAcceptable())
                        redisClientHandler.handleAccept(key);
                    if (key.isReadable())
                        redisClientHandler.handleRead(key);
                }
                // After handling IO, process any BLPOP timeouts
                if (redisClientHandler != null && redisClientHandler.cmdHandler != null) {
                    redisClientHandler.cmdHandler.processTimeouts();
                }
            }
        } catch (IOException e) {
            System.err.println("Redis Server Start error: " + e.getMessage());
        }
    }
}


// Selection Key
// lazy removal of elements
// https://hannrul.medium.com/launch-a-client-and-http-server-connection-via-terminal-1ccceea9c4fa
// redis system design
// how redis works and its features
// must delete the content from string builder after processing
// need of bytebuffer and stringbuilder
// NIO in detail
// How socketAdress
// what all can be registered to selector by client and server
// why string builder needed and why bytebuffer is used
// how selector selects
// why flip not needed in stringbuilder
// understand switch and case
// in BLPOP how is selector sending logs to correct client is it via key?
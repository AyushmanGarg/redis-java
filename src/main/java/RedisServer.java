import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.*;

public class RedisServer {
    private ServerSocketChannel RedisServer;
    private Selector RedisServerSelector;
    RedisClientHandler redisClientHandler;
    public Boolean is_slave;
    RedisReplicaHandler RedisReplicaCmdHandler;

    public RedisServer(int port, Boolean is_slave) {
        try {
            this.RedisServerSelector = Selector.open();
            this.RedisServer = ServerSocketChannel.open();
            this.RedisServer.bind(new InetSocketAddress(port));
            this.RedisServer.configureBlocking(false);
            this.RedisServer.register(RedisServerSelector, SelectionKey.OP_ACCEPT);
            this.redisClientHandler = new RedisClientHandler(RedisServerSelector, is_slave);
            System.out.println("Redis Single Threaded server listening on " + port);
            this.is_slave = is_slave;
            if(!this.is_slave) RedisReplicaCmdHandler = new RedisReplicaHandler();
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
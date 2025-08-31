import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.*;

public class RedisReplicaHandler {
    public List<Integer> ReplicaPorts;
    public void addReplicaPort(Integer replica_port) {
         this.ReplicaPorts.add(replica_port);
    }
}

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RedisReplicaManagementService {
    private static final List<Socket> replicas = new ArrayList<>();
    
    public static void addReplica(Socket replicaSocket) {
        System.out.println("Adding replica: " + replicaSocket);
        replicas.add(replicaSocket);
    }
    
    public static void removeReplica(Socket replicaSocket) {
        System.out.println("Removing replica: " + replicaSocket);
        replicas.remove(replicaSocket);
    }
    
    public static void sendCommandToReplicas(String command) {
        if (replicas.isEmpty()) {
            return; // No replicas to send to
        }
        
        System.out.println("Sending command to " + replicas.size() + " replicas: " + command);
        
        // Create a copy of the list to avoid concurrent modification issues
        List<Socket> replicasCopy = new ArrayList<>(replicas);
        
        for (Socket replica : replicasCopy) {
            try {
                if (replica.isConnected() && !replica.isClosed()) {
                    replica.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
                    replica.getOutputStream().flush();
                } else {
                    // Remove disconnected replicas
                    removeReplica(replica);
                }
            } catch (IOException e) {
                System.err.println("Failed to send command to replica: " + e.getMessage());
                // Remove failed replicas
                removeReplica(replica);
            }
        }
    }
    
    public static int getReplicaCount() {
        return replicas.size();
    }
    
    public static List<Socket> getReplicas() {
        return new ArrayList<>(replicas); // Return a copy for safety
    }
    
    public static void clearReplicas() {
        replicas.clear();
        System.out.println("All replicas cleared");
    }
}

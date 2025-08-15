import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class RedisServer {
    private int port;
    private ServerSocket serverSocket;

    public RedisServer(int port) {
        this.port = port;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            System.out.println("Redis Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New Redis client connected: " + clientSocket.getPort());
                new Thread(new RedisClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Redis Server error: " + e.getMessage());
            stop();
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing Redis Server socket: " + e.getMessage());
        }
    }
}
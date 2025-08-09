import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class Main {
    public static void main(String[] args) {
        ServerSocket serverSocket = null;
        // Socket clientSocket = null;
        int port = 6379;

        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            while (true) {
              Socket clientSocket = serverSocket.accept();
              System.out.println("New client connected");
              new Thread(() -> handleClient(clientSocket))
              .start(); 
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
            System.exit(-1);
        }
    }

    public static void handleClient(Socket clientSocket) {
      try (clientSocket;
          OutputStream outputStream = clientSocket.getOutputStream();
          BufferedReader in = new BufferedReader(
          new InputStreamReader(clientSocket.getInputStream()))){
            
            while(true) {
              if(in.readLine() == null) {
                break;
              }
              in.readLine();
              String line = in.readLine();
              System.out.println("Last line: " + line);

              outputStream.write("+PONG\r\n".getBytes());
              System.out.println("Wrote pong");
            }

      } catch (Exception e) {
        System.out.println("IOException: " + e.getMessage());
      }
    }

}

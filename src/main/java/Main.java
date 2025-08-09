import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;

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
            HashMap<String, String> map = new HashMap<>();
            while(true) {
              if(in.readLine() == null) {
                break;
              }
              in.readLine();
              String line = in.readLine();
              System.out.println("Last line: " + line);
              if(line.equalsIgnoreCase("ping")) {
                outputStream.write("+PONG\r\n".getBytes());
                outputStream.flush();
              } else if(line.equalsIgnoreCase("echo")) {
                String numBytes = in.readLine();
                outputStream.write(("+"+in.readLine()+"\r\n").getBytes());
                System.out.println("Last line: " );
                outputStream.flush();
              } else if(line.equalsIgnoreCase("SET")) {
                // System.out.println("SET");
                String key_bytes  = in.readLine();
                String key = in.readLine();
                String value_bytes = in.readLine();
                String value = in.readLine();
                System.out.println(key + " " + value);
                map.put(key, value);
                outputStream.write(("OK" + "\r\n").getBytes());
              } else if(line.equalsIgnoreCase("GET")) {
                System.out.println("GET" );
                in.readLine();
                String key = in.readLine();
                byte[] bytes = (map.get(key)).getBytes();
                outputStream.write(("$"+bytes+"\r\n"+map.get(key)+"\r\n").getBytes());
              }

            }

      } catch (Exception e) {
        System.out.println("IOException: " + e.getMessage());
      }
    }

}

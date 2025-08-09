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
                outputStream.write(("+"+in.readLine()+"\r\n").getBytes());
                System.out.println("Last line: " );
                outputStream.flush();
              } else if(line.equalsIgnoreCase("SET")) {
                String key = in.readLine();
                String value = in.readLine();
                System.out.println(key + " " + value);
                map.put(key, value);
                outputStream.write(("+"+"OK" + "\r\n").getBytes());
              } else if(line.equalsIgnoreCase("GET")) {
                System.out.println("GET" );
                String key = in.readLine();
                key = in.readLine();
                String value = map.get(key);
                System.out.println(value+ " "+ key);
                // if(value!= null) {
                  byte[] bytes = (map.get(key)).getBytes();
                  System.out.println("GET" + bytes);
                  outputStream.write(("$"+bytes.length+"\r\n"+map.get(key)+"\r\n").getBytes());
                // } else {
                  // outputStream.write("$-1\r\n".getBytes());
                // }
                
              }

            }

      } catch (Exception e) {
        System.out.println("IOException: " + e.getMessage());
      }
    }

}

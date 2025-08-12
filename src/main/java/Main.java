import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.*;

public class Main {
  public static void main(String[] args) {
    ServerSocket serverSocket = null;
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
            new InputStreamReader(clientSocket.getInputStream()))) {
      HashMap<String, String> map = new HashMap<>();
      HashMap<String, Long> expiry_map = new HashMap<>();
      HashMap<String, List<String>> list_Storage = new HashMap<>();
      while (true) {
        if (in.readLine() == null) {
          break;
        }
        in.readLine();
        String line = in.readLine();
        if (line.equalsIgnoreCase("ping")) {
          outputStream.write("+PONG\r\n".getBytes());
          outputStream.flush();
        } else if (line.equalsIgnoreCase("echo")) {
          in.readLine();
          outputStream.write(("+" + in.readLine() + "\r\n").getBytes());
          outputStream.flush();
        } else if (line.equalsIgnoreCase("SET")) {
          in.readLine();
          String key = in.readLine();
          in.readLine();
          String value = in.readLine();
          String maybeLen = in.ready() ? in.readLine() : null;
          if (maybeLen == null) {
            map.put(key, value);
            outputStream.write(("+" + "OK" + "\r\n").getBytes());
          } else if (in.readLine().equalsIgnoreCase("px")) {
            in.readLine();
            String time = in.readLine();
            Long expry_time = System.currentTimeMillis() + Long.valueOf(time);
            expiry_map.put(key, expry_time);
            map.put(key, value);
            outputStream.write(("+" + "OK" + "\r\n").getBytes());
          }
        } else if (line.equalsIgnoreCase("GET")) {
          System.out.println("GET");
          in.readLine();
          String key = in.readLine();
          if (map.get(key) != null && expiry_map.get(key) == null) {
            byte[] bytes = (map.get(key)).getBytes();
            outputStream.write(("$" + bytes.length + "\r\n" + map.get(key) + "\r\n").getBytes());
          } else if (map.get(key) != null && System.currentTimeMillis() < expiry_map.get(key)) {
            byte[] bytes = (map.get(key)).getBytes();
            outputStream.write(("$" + bytes.length + "\r\n" + map.get(key) + "\r\n").getBytes());
          } else if (map.get(key) != null && System.currentTimeMillis() >= expiry_map.get(key)) {
            map.remove(key);
            expiry_map.remove(key);
            outputStream.write("$-1\r\n".getBytes());
          } else {
            outputStream.write("$-1\r\n".getBytes());
          }
        } else if(line.equalsIgnoreCase("RPUSH")) {
          in.readLine();
          String key = in.readLine();
          in.readLine();
          if(list_Storage.containsKey(key)) {
            while(in.ready()) {
              in.readLine();
              list_Storage.get(key).add(in.readLine());
            }
            outputStream.write((":"+ list_Storage.get(key).size() + "\r\n").getBytes());
          } else {
            list_Storage.put(key, new ArrayList<>(Arrays.asList(in.readLine())));
            while(in.ready()) {
              in.readLine();
              list_Storage.get(key).add(in.readLine());
            }
            outputStream.write((":"+ list_Storage.get(key).size() + "\r\n").getBytes());
          }
        }

      }

    } catch (Exception e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

}

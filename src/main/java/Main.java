import java.net.Socket;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;

public class Main {

  private static String readLineFrom(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      sb.append((char) c);
      if (c == '\n')
        break; 
    }
    return sb.toString();
  }

  public static void main(String[] args) throws IOException { 
    System.out.println(Arrays.toString(args));
    // [--port, 6380, --replicaof, localhost 6379]
    int port = 6379; // Default Redis port
    if (args.length >= 2) {
      port = Integer.parseInt(args[1]);
    }

    boolean is_slave = false;
    if (args.length > 2 && args[2].equals("--replicaof")) {
      is_slave = true;
    }

    if (is_slave) {
      String[] address = args[3].split(" ");
      System.out.println(Arrays.toString(address));
      String hostAddr = address[0];
      int portAddr = Integer.parseInt(address[1]);

      

      try (Socket slave = new Socket(hostAddr, portAddr)) {
        InputStream in = slave.getInputStream();
        slave.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes());
        slave.getOutputStream().flush();
        readLineFrom(in);
        // *3\r\n$8\r\nREPLCONF\r\n$14\r\nlistening-port\r\n$4\r\n6380\r\n
        slave.getOutputStream().write(("*3\r\n$8\r\nREPLCONF\r\n$14\r\nlistening-port\r\n$4\r\n" + port + "\r\n").getBytes());
        slave.getOutputStream().flush();
        readLineFrom(in);
        slave.getOutputStream().write("*3\r\n$8\r\nREPLCONF\r\n$4\r\ncapa\r\n$6\r\npsync2\r\n".getBytes());
        slave.getOutputStream().flush();
        readLineFrom(in);
        // *3\r\n$5\r\nPSYNC\r\n$1\r\n?\r\n$2\r\n-1\r\n
        slave.getOutputStream().write("*3\r\n$5\r\nPSYNC\r\n$1\r\n?\r\n$2\r\n-1\r\n".getBytes());
        slave.getOutputStream().flush();
        readLineFrom(in);
        // slave.close();
      }
    }

    RedisServer redisServer = new RedisServer(port, is_slave);
    redisServer.start();
  }
}
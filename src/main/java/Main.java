import java.net.Socket;
import java.io.IOException;
import java.util.Arrays; // ✅ keep this

public class Main {
  public static void main(String[] args) throws IOException { // ✅ declare throws
    System.out.println(Arrays.toString(args));

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
      String hostAddr = address[0];
      int portAddr = Integer.parseInt(address[1]);

      try (Socket slave = new Socket(hostAddr, portAddr)) { // ✅ auto-close
        slave.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes());
        slave.getOutputStream().flush();
      }
    }

    RedisServer redisServer = new RedisServer(port, is_slave);
    redisServer.start();
  }
}

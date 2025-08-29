import java.util.Arrays;

public class Main {
  public static void main(String[] args) {
      System.out.println(Arrays.toString(args));
      Integer port = 6379; // Default Redis port
      if(args.length>=2) {
        port = Integer.parseInt(args[1]);
      }
      Boolean is_slave = false;
      if(args.length>2) {
        if(args[2].equals("--replicaof")) is_slave = true;
      }
      RedisServer redisServer = new RedisServer(port, is_slave);
      redisServer.start();
  }
}
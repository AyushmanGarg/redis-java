public class Main {
  public static void main(String[] args) {
      Integer port = 6379; // Default Redis port
      if(args.length>=2) {
        port = Integer.parseInt(args[1]);
      }
      RedisServer redisServer = new RedisServer(port);
      redisServer.start();
  }
}
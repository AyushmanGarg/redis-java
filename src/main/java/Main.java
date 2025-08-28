public class Main {
  public static void main(String[] args) {
      int port = 6379; // Default Redis port
      RedisServer redisServer = new RedisServer(port);
      redisServer.start();
  }
}
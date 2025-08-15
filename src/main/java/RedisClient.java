import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class RedisClient implements Runnable {
    private final Socket clientSocket;
    public RedisClient(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    public void handleClientCommands(OutputStream outputStream, BufferedReader inputStream, RedisClientCommandHandler cmdHandler) {
        try {
            inputStream.readLine();
            String cmd = inputStream.readLine();
            if(cmd.equalsIgnoreCase("PING")) cmdHandler.handlePING();
            else if (cmd.equalsIgnoreCase("echo")) cmdHandler.handleECHO();;
        } catch (Exception e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    public void handleClient() {
        try (clientSocket;
            OutputStream outputStream = clientSocket.getOutputStream();
            BufferedReader inputStream = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))){
                RedisClientCommandHandler cmdHandler = new RedisClientCommandHandler(outputStream, inputStream);
                while (true) {
                    // closes connection when client is disconnected
                    // if (inputStream.readLine() == null) {
                    //     break; 
                    // }
                    handleClientCommands(outputStream, inputStream, cmdHandler);
                }
        } catch (Exception e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        handleClient();
    }
}







//       HashMap<String, String> map = new HashMap<>();
//       HashMap<String, Long> expiry_map = new HashMap<>();
//       HashMap<String, List<String>> list_Storage = new HashMap<>();
//       while (true) {
//         
//          else if (line.equalsIgnoreCase("SET")) {
//           in.readLine();
//           String key = in.readLine();
//           in.readLine();
//           String value = in.readLine();
//           String maybeLen = in.ready() ? in.readLine() : null;
//           if (maybeLen == null) {
//             map.put(key, value);
//             outputStream.write(("+" + "OK" + "\r\n").getBytes());
//           } else if (in.readLine().equalsIgnoreCase("px")) {
//             in.readLine();
//             String time = in.readLine();
//             Long expry_time = System.currentTimeMillis() + Long.valueOf(time);
//             expiry_map.put(key, expry_time);
//             map.put(key, value);
//             outputStream.write(("+" + "OK" + "\r\n").getBytes());
//           }
//         } else if (line.equalsIgnoreCase("GET")) {
//           System.out.println("GET");
//           in.readLine();
//           String key = in.readLine();
//           if (map.get(key) != null && expiry_map.get(key) == null) {
//             byte[] bytes = (map.get(key)).getBytes();
//             outputStream.write(("$" + bytes.length + "\r\n" + map.get(key) + "\r\n").getBytes());
//           } else if (map.get(key) != null && System.currentTimeMillis() < expiry_map.get(key)) {
//             byte[] bytes = (map.get(key)).getBytes();
//             outputStream.write(("$" + bytes.length + "\r\n" + map.get(key) + "\r\n").getBytes());
//           } else if (map.get(key) != null && System.currentTimeMillis() >= expiry_map.get(key)) {
//             map.remove(key);
//             expiry_map.remove(key);
//             outputStream.write("$-1\r\n".getBytes());
//           } else {
//             outputStream.write("$-1\r\n".getBytes());
//           }
//         } else if (line.equalsIgnoreCase("RPUSH")) {
//           in.readLine();
//           String key = in.readLine();
//           if (list_Storage.containsKey(key)) {
//             while (in.ready()) {
//               in.readLine();
//               list_Storage.get(key).add(in.readLine());
//             }
//             outputStream.write((":" + list_Storage.get(key).size() + "\r\n").getBytes());
//           } else {
//             in.readLine();
//             list_Storage.put(key, new ArrayList<>(Arrays.asList(in.readLine())));
//             while (in.ready()) {
//               in.readLine();
//               list_Storage.get(key).add(in.readLine());
//             }
//             outputStream.write((":" + list_Storage.get(key).size() + "\r\n").getBytes());
//           }
//         } else if (line.equalsIgnoreCase("LRANGE")) {
//           in.readLine();
//           String key = in.readLine();
//           in.readLine();
//           String strtidx = in.readLine();
//           Integer strt_idx = Integer.valueOf(strtidx);
//           in.readLine();
//           String endidx = in.readLine();
//           Integer end_idx = Integer.valueOf(endidx);
//           if (list_Storage.containsKey(key)) {
//             Integer n = list_Storage.get(key).size();
//             if (strt_idx < 0 || end_idx < 0) {
//               if (strt_idx <= -1 * n)
//                 strt_idx = 0;
//               if (end_idx <= -1 * n)
//                 end_idx = 0;
//               strt_idx += n;
//               end_idx += n;
//               strt_idx = strt_idx % n;
//               end_idx = end_idx % n;
//               if (strt_idx >= n || strt_idx > end_idx) {
//                 outputStream.write("*0\r\n".getBytes());
//               } else {
//                 Integer idx = strt_idx;
//                 Integer mx = Math.min(n, end_idx);
//                 Integer mn = Math.max(strt_idx, 0);
//                 if (n < end_idx) {
//                   outputStream.write(("*" + (mx - mn) + "\r\n").getBytes());
//                 } else {
//                   outputStream.write(("*" + (mx - mn + 1) + "\r\n").getBytes());
//                 }
//                 while (idx < n && idx <= end_idx) {
//                   byte[] bytes = list_Storage.get(key).get(idx).getBytes();
//                   outputStream.write(("$" + bytes.length + "\r\n").getBytes());
//                   outputStream.write((list_Storage.get(key).get(idx) + "\r\n").getBytes());
//                   idx++;
//                 }
//               }
//             } else {
//               if (strt_idx >= n || strt_idx > end_idx) {
//                 outputStream.write("*0\r\n".getBytes());
//               } else {
//                 Integer idx = strt_idx;
//                 Integer mx = Math.min(n, end_idx);
//                 Integer mn = Math.max(strt_idx, 0);
//                 if (n < end_idx) {
//                   outputStream.write(("*" + (mx - mn) + "\r\n").getBytes());
//                 } else {
//                   outputStream.write(("*" + (mx - mn + 1) + "\r\n").getBytes());
//                 }
//                 while (idx < n && idx <= end_idx) {
//                   byte[] bytes = list_Storage.get(key).get(idx).getBytes();
//                   outputStream.write(("$" + bytes.length + "\r\n").getBytes());
//                   outputStream.write((list_Storage.get(key).get(idx) + "\r\n").getBytes());
//                   idx++;
//                 }
//               }
//             }
//           } else {
//             outputStream.write("*0\r\n".getBytes());
//           }
//         } else if (line.equalsIgnoreCase("LPUSH")) {
//           in.readLine();
//           String key = in.readLine();
//           List<String> reverse_list = new ArrayList<>();
//           while (in.ready()) {
//             in.readLine();
//             reverse_list.add(in.readLine());
//           }
//           Collections.reverse(reverse_list);
//           if (list_Storage.containsKey(key)) {
//             list_Storage.get(key).addAll(0, reverse_list);
//             outputStream.write((":" + list_Storage.get(key).size() + "\r\n").getBytes());
//           } else {
//             list_Storage.put(key, new ArrayList<>());
//             list_Storage.get(key).addAll(0, reverse_list);
//             outputStream.write((":" + list_Storage.get(key).size() + "\r\n").getBytes());
//           }
//         } else if(line.equalsIgnoreCase("LLEN")) {
//           in.readLine();
//           String key = in.readLine();
//           if(!list_Storage.containsKey(key)) {
//             outputStream.write(":0\r\n".getBytes());
//           } else {
//             outputStream.write((":" + list_Storage.get(key).size() + "\r\n").getBytes());
//           }
//         } else if(line.equalsIgnoreCase("LPOP")) {
//           in.readLine();
//           String key = in.readLine();
//           if(in.ready()) {
//             in.readLine();
//             String num = in.readLine();
//             Integer nums = Integer.valueOf(num);
//             outputStream.write(("*" + (nums) + "\r\n").getBytes());
//             while(list_Storage.size()>0 && nums>0) {
//               String value = list_Storage.get(key).get(0);
//               list_Storage.get(key).remove(0);
//               byte[] bytes = (value).getBytes();
//               outputStream.write(("$" + bytes.length + "\r\n" + value + "\r\n").getBytes());
//               nums--;
//             }
//           } else {
//             String value = list_Storage.get(key).get(0);
//             list_Storage.get(key).remove(0);
//             byte[] bytes = (value).getBytes();
//             outputStream.write(("$" + bytes.length + "\r\n" + value + "\r\n").getBytes());
//           }
//         } 
//       }

//     } catch (Exception e) {
//       System.out.println("IOException: " + e.getMessage());
//     }
//   }

// }

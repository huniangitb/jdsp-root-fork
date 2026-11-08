import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Raw JSON client: sends every line of a request file (or every argument) to the daemon. */
public class RawJson {
    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8).trim();
    }

    public static void main(String[] args) throws Exception {
        String port = read("/data/data/james.dsp/files/jdsp.port");
        String token = read("/data/data/james.dsp/files/jdsp.token");
        Socket s = new Socket("127.0.0.1", Integer.parseInt(port));
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        System.out.println("HELLO  " + call(out, in, "{\"op\":\"hello\",\"token\":\"" + token + "\"}"));
        List<String> lines = new java.util.ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ("@".equals(args[i]) && i + 1 < args.length) {
                i++;
                for (String line : Files.readAllLines(Paths.get(args[i]), StandardCharsets.UTF_8)) {
                    if (line.trim().length() > 0) {
                        lines.add(line.trim());
                    }
                }
            } else {
                lines.add(args[i]);
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            System.out.println("REQ    " + lines.get(i));
            System.out.println("REPLY  " + call(out, in, lines.get(i)));
        }
        out.close();
        in.close();
        s.close();
    }

    static String call(Writer out, BufferedReader in, String line) throws IOException {
        out.write(line);
        out.write("\n");
        out.flush();
        return in.readLine();
    }
}

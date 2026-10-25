package me.timschneeberger.rootlessjamesdsp.p5;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 传输 B（最后兜底）：root shell 调 jdspcli。
 *
 * 只在 App 已被授予 root（KernelSU/Magisk 授权）时可用。每次调用要拉起一个
 * app_process，固定开销约 0.2~0.5s，只适合低频/兜底，不适合滑条实时量。
 *
 * 命令形如：
 *   su -c 'CLASSPATH=<dex> app_process /system/bin com.jdsp.cli.Main --json set <s> <p> --hex <hex>'
 *
 * dex 路径按候选列表探测；也可以用 setDexPath() 显式指定。
 */
public final class ShellTransport implements JdspTransport {

    private static final String[] DEX_CANDIDATES = new String[] {
            "/data/adb/modules/ainur_jamesdsp/jdspcli.dex",
            "/data/adb/jdspd/jdspcli.dex",
            "/data/local/tmp/jdspcli.dex",
            "/data/adb/modules_update/ainur_jamesdsp/jdspcli.dex",
    };

    private String dexPath;
    private boolean available;

    public ShellTransport() {
        this.dexPath = firstExisting();
    }

    public ShellTransport(String dexPath) {
        this.dexPath = dexPath;
    }

    public void setDexPath(String path) {
        this.dexPath = path;
        this.available = false;
    }

    private static String firstExisting() {
        for (String candidate : DEX_CANDIDATES) {
            if (new java.io.File(candidate).isFile()) return candidate;
        }
        return DEX_CANDIDATES[2];
    }

    @Override
    public String name() {
        return "shell(su,jdspcli=" + dexPath + ")";
    }

    @Override
    public boolean isConnected() {
        return available;
    }

    @Override
    public boolean connect() {
        if (!new java.io.File(dexPath).isFile()) {
            available = false;
            return false;
        }
        available = ping() >= 0;
        return available;
    }

    @Override
    public void close() {
        available = false;
    }

    /** 执行一次 jdspcli，返回 {exitCode, stdout}。su 不可用返回 null。 */
    public int[] exec(List<String> args, StringBuilder stdoutOut) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(dexPath)
           .append(" app_process /system/bin com.jdsp.cli.Main --json");
        for (String a : args) {
            cmd.append(' ').append(shellQuote(a));
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd.toString() });
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
                if (++guard > 4096) break;
            }
            int code = process.waitFor();
            if (stdoutOut != null) stdoutOut.append(out);
            return new int[] { code, out.length() };
        } catch (Throwable t) {
            if (stdoutOut != null) stdoutOut.append("error: ").append(t);
            return null;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public int ping() {
        StringBuilder out = new StringBuilder();
        int[] r = exec(java.util.Collections.singletonList("ping"), out);
        if (r == null) return -1;
        return r[0] == 0 ? 0 : -1;
    }

    @Override
    public int setParam(int sessionId, int paramId, byte[] value) {
        List<String> args = new ArrayList<String>();
        args.add("set");
        args.add(String.valueOf(sessionId));
        args.add(String.valueOf(paramId));
        args.add("--hex");
        args.add(JdspCodec.hex(value));
        int[] r = exec(args, null);
        if (r == null) return -1;
        return r[0] == 0 ? 0 : -2;
    }

    @Override
    public byte[] getParam(int sessionId, int paramId, int vsize) {
        List<String> args = new ArrayList<String>();
        args.add("get");
        args.add(String.valueOf(sessionId));
        args.add(String.valueOf(paramId));
        args.add(String.valueOf(vsize));
        StringBuilder out = new StringBuilder();
        int[] r = exec(args, out);
        if (r == null || r[0] != 0) return null;
        String text = out.toString();
        int idx = text.indexOf("\"hex\"");
        if (idx < 0) return null;
        int colon = text.indexOf(':', idx);
        int quote = text.indexOf('"', colon + 1);
        int end = text.indexOf('"', quote + 1);
        if (colon < 0 || quote < 0 || end < 0) return null;
        return JdspCodec.unhex(text.substring(quote + 1, end));
    }

    @Override
    public int attach(int sessionId) {
        List<String> args = new ArrayList<String>();
        args.add("attach");
        args.add(String.valueOf(sessionId));
        int[] r = exec(args, null);
        return r == null ? -1 : (r[0] == 0 ? 0 : -2);
    }

    @Override
    public int[] listAttachedSessions() {
        return null;  // B 通道不实现会话列表（低频兜底，保留占位）
    }

    @Override
    public int detach(int sessionId) {
        List<String> args = new ArrayList<String>();
        args.add("detach");
        args.add(String.valueOf(sessionId));
        int[] r = exec(args, null);
        return r == null ? -1 : (r[0] == 0 ? 0 : -2);
    }

    @Override
    public int setEnabled(boolean enabled) {
        // jdspcli 的命令名是 "enabled"（不是 setEnabled）
        List<String> args = new ArrayList<String>();
        args.add("enabled");
        args.add(enabled ? "on" : "off");
        int[] r = exec(args, null);
        return r == null ? -1 : (r[0] == 0 ? 0 : -2);
    }

    @Override
    public Boolean getEnabled() {
        StringBuilder out = new StringBuilder();
        int[] r = exec(java.util.Collections.singletonList("enabled"), out);
        if (r == null || r[0] != 0) return null;
        String text = out.toString().trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("off") || lower.contains("false") || lower.startsWith("0")) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    @Override
    public String dumpConfig() {
        // jdspcli 的命令名是 "dump-config"（带连字符）。
        StringBuilder out = new StringBuilder();
        int[] r = exec(java.util.Collections.singletonList("dump-config"), out);
        if (r == null || r[0] != 0) return null;
        String text = out.toString().trim();
        return text.isEmpty() ? null : text;
    }

    @Override
    public int commit(int sessionId) {
        int[] r = exec(java.util.Collections.singletonList("commit"), null);
        return r == null ? -1 : (r[0] == 0 ? 0 : -2);
    }
}

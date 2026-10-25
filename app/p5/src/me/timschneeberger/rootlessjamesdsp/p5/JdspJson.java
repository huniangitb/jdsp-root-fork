package me.timschneeberger.rootlessjamesdsp.p5;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 解析器（纯 Java，零依赖）。
 *
 * 为什么不直接用 org.json：org.json 只在 Android 上有，宿主单测跑不了；
 * 而"读后端 dumpConfig"是本轮的关键新逻辑，必须能在宿主上验证。
 * 这个解析器只覆盖后端 config.json 用到的子集：
 * 对象 / 数组 / 字符串 / 数字 / true / false / null。
 */
public final class JdspJson {

    private JdspJson() {}

    /** 解析失败时返回 null（调用方按"读不到"处理）。 */
    public static Object parse(String text) {
        if (text == null) return null;
        try {
            Parser p = new Parser(text);
            p.skipWhitespace();
            Object v = p.readValue();
            p.skipWhitespace();
            if (!p.atEnd()) return null;
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取对象里的某个键；不是对象或没有这个键返回 null。 */
    public static Object get(Object root, String key) {
        if (!(root instanceof Map)) return null;
        return ((Map<?, ?>) root).get(key);
    }

    /** 取对象里的某个键并转成字符串；取不到返回 null。 */
    public static String getString(Object root, String key) {
        Object v = get(root, key);
        if (v == null) return null;
        return String.valueOf(v);
    }

    /** 取对象里的某个键并转成 int；取不到返回 def。 */
    public static int getInt(Object root, String key, int def) {
        Object v = get(root, key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    /** 取对象里的某个键并转成 boolean；取不到返回 def。 */
    public static boolean getBoolean(Object root, String key, boolean def) {
        Object v = get(root, key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(s) || "on".equals(s) || "yes".equals(s) || "1".equals(s)) return true;
            if ("false".equals(s) || "off".equals(s) || "no".equals(s) || "0".equals(s)) return false;
        }
        return def;
    }

    // ---------------------------------------------------------------- 解析器

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (i >= s.length()) throw new IllegalStateException("eof");
            char c = s.charAt(i);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) throw new IllegalStateException("expected " + word);
            i += word.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            i++; // '{'
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalStateException("expected ':'");
                i++;
                Object value = readValue();
                out.put(key, value);
                skipWhitespace();
                if (i >= s.length()) throw new IllegalStateException("eof in object");
                char c = s.charAt(i++);
                if (c == '}') return out;
                if (c != ',') throw new IllegalStateException("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> out = new ArrayList<Object>();
            i++; // '['
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                out.add(readValue());
                skipWhitespace();
                if (i >= s.length()) throw new IllegalStateException("eof in array");
                char c = s.charAt(i++);
                if (c == ']') return out;
                if (c != ',') throw new IllegalStateException("expected ',' or ']'");
            }
        }

        private String readString() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalStateException("expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalStateException("eof in string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw new IllegalStateException("eof in escape");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalStateException("bad \\u");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
        }

        private Object readNumber() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') i++;
                else break;
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalStateException("expected number");
            try {
                if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                    return Long.valueOf(Long.parseLong(num));
                }
                return Double.valueOf(Double.parseDouble(num));
            } catch (Throwable t) {
                return Double.valueOf(0d);
            }
        }
    }
}

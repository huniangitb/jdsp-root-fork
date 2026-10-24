package com.jdsp.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader/writer for the jdspd unix-socket protocol.
 *
 * Hand-rolled on purpose: org.json is an Android stub on the host JVM, and the
 * protocol only needs a small subset. No Android types here, so the socket
 * transport is host-testable.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) throws CliException {
        if (text == null) {
            throw new CliException(Exit.UNAVAILABLE, "empty JSON response");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new CliException(Exit.UNAVAILABLE,
                    "invalid JSON: trailing characters at offset " + parser.pos());
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    public static String string(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        return value instanceof String ? (String) value : null;
    }

    public static int intValue(Map<String, Object> object, String key, int fallback) {
        Object value = object == null ? null : object.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public static boolean booleanValue(Map<String, Object> object, String key, boolean fallback) {
        Object value = object == null ? null : object.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    // ------------------------------------------------------------------
    // writer
    // ------------------------------------------------------------------

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append(Format.jsonString((String) value));
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(value.toString());
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Object entry : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) entry;
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(Format.jsonString(String.valueOf(e.getKey())));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append(Format.jsonString(value.toString()));
        }
    }

    // ------------------------------------------------------------------
    // parser
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        int pos() {
            return pos;
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() throws CliException {
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() throws CliException {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++;
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of object");
            }
            if (text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("expected object key");
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw error("expected ':' after object key");
                }
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("unexpected end of object");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> parseArray() throws CliException {
            List<Object> list = new ArrayList<Object>();
            pos++;
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of array");
            }
            if (text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("unexpected end of array");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw error("expected ',' or ']'");
            }
        }

        private String parseString() throws CliException {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw error("bad unicode escape");
                        }
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("bad unicode escape: " + hex);
                        }
                        break;
                    default:
                        throw error("bad escape: \\" + escaped);
                }
            }
        }

        private Object parseNumber() throws CliException {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            if (token.isEmpty()) {
                throw error("unexpected character");
            }
            try {
                if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                    return Double.valueOf(token);
                }
                return Long.valueOf(token);
            } catch (NumberFormatException e) {
                throw error("bad number: " + token);
            }
        }

        private void expect(String literal) throws CliException {
            if (!text.startsWith(literal, pos)) {
                throw error("expected " + literal);
            }
            pos += literal.length();
        }

        CliException error(String message) {
            return new CliException(Exit.UNAVAILABLE,
                    "invalid JSON at offset " + pos + ": " + message);
        }
    }
}

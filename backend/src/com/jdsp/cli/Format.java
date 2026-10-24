package com.jdsp.cli;

import java.util.List;
import java.util.Locale;

/** Table and JSON rendering for jdspcli output (pure Java, host testable). */
public final class Format {
    private Format() {}

    private static final String[] SESSION_HEADERS = {
            "session", "uid", "pkg", "attached", "enabled", "params", "effectId", "policy"
    };

    public static String policyName(int mode) {
        switch (mode) {
            case 0:
                return "follow";
            case 1:
                return "on";
            case 2:
                return "exclude";
            default:
                return "unknown(" + mode + ")";
        }
    }

    public static String modeName(int mode) {
        switch (mode) {
            case 0:
                return "auto";
            case 1:
                return "backend";
            default:
                return "unknown(" + mode + ")";
        }
    }

    public static String sessionsTable(List<SessionRow> sessions) {
        int count = sessions == null ? 0 : sessions.size();
        String[][] rows = new String[count][];
        for (int i = 0; i < count; i++) {
            SessionRow s = sessions.get(i);
            rows[i] = new String[] {
                    Integer.toString(s.sessionId()),
                    Integer.toString(s.uid()),
                    s.pkg() == null ? "" : s.pkg(),
                    s.attached() ? "1" : "0",
                    s.enabled() ? "1" : "0",
                    Integer.toString(s.paramCount()),
                    Integer.toString(s.effectId()),
                    policyName(s.policyMode())
            };
        }
        int[] width = new int[SESSION_HEADERS.length];
        for (int c = 0; c < width.length; c++) {
            width[c] = SESSION_HEADERS[c].length();
        }
        for (String[] row : rows) {
            for (int c = 0; c < width.length; c++) {
                width[c] = Math.max(width[c], row[c].length());
            }
        }
        StringBuilder sb = new StringBuilder();
        appendRow(sb, SESSION_HEADERS, width);
        for (String[] row : rows) {
            appendRow(sb, row, width);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String[] values, int[] width) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < values.length; c++) {
            if (c > 0) {
                line.append(' ');
            }
            line.append(values[c]);
            if (c < values.length - 1) {
                for (int p = values[c].length(); p < width[c]; p++) {
                    line.append(' ');
                }
            }
        }
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        sb.append(line, 0, end).append('\n');
    }

    public static String sessionsJson(List<SessionRow> sessions) {
        StringBuilder sb = new StringBuilder("[");
        int count = sessions == null ? 0 : sessions.size();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sessionJson(sessions.get(i)));
        }
        return sb.append(']').toString();
    }

    public static String sessionJson(SessionRow s) {
        return "{"
                + "\"session\":" + s.sessionId() + ","
                + "\"uid\":" + s.uid() + ","
                + "\"pkg\":" + jsonString(s.pkg() == null ? "" : s.pkg()) + ","
                + "\"attached\":" + s.attached() + ","
                + "\"enabled\":" + s.enabled() + ","
                + "\"params\":" + s.paramCount() + ","
                + "\"effectId\":" + s.effectId() + ","
                + "\"policy\":" + jsonString(policyName(s.policyMode())) + ","
                + "\"policyMode\":" + s.policyMode()
                + "}";
    }

    public static String policyJson(String pkg, int mode) {
        return "{\"pkg\":" + jsonString(pkg)
                + ",\"policy\":" + jsonString(policyName(mode))
                + ",\"policyMode\":" + mode + "}";
    }

    public static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}

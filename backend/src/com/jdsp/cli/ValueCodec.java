package com.jdsp.cli;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encodes jdspcli values into the byte layout the engine expects.
 *
 * Default values are little-endian float32 (the App and tools/JdspCtl.java use
 * the same layout). --int is int32 LE, --short is int16 LE; --hex/--raw pass
 * exact bytes through.
 */
public final class ValueCodec {
    private ValueCodec() {}

    /**
     * Encodes the value part of a set/bench command.
     *
     * @param cmd             parsed command
     * @param firstValueIndex index of the first positional value inside
     *                        ParsedCommand.args() (2 for set/bench, where
     *                        session and param come first)
     */
    public static byte[] encode(ParsedCommand cmd, int firstValueIndex) throws CliException {
        if (firstValueIndex < 0 || firstValueIndex > cmd.argCount()) {
            throw new CliException(Exit.USAGE, "internal: bad value index " + firstValueIndex);
        }
        int modes = 0;
        if (cmd.hex() != null) {
            modes++;
        }
        if (cmd.intValue() != null) {
            modes++;
        }
        if (cmd.shortValue() != null) {
            modes++;
        }
        if (cmd.raw()) {
            modes++;
        }
        if (modes > 1) {
            throw new CliException(Exit.USAGE,
                    "value options are mutually exclusive: --hex, --int, --short, --raw");
        }

        if (cmd.hex() != null) {
            if (cmd.argCount() > firstValueIndex) {
                throw new CliException(Exit.USAGE,
                        "--hex carries the value; do not pass positional values");
            }
            return parseHex(cmd.hex(), "--hex");
        }
        if (cmd.raw()) {
            if (cmd.argCount() <= firstValueIndex) {
                throw new CliException(Exit.USAGE, "--raw needs at least one hex chunk");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = firstValueIndex; i < cmd.argCount(); i++) {
                byte[] chunk = parseHex(cmd.arg(i), "--raw chunk #" + (i - firstValueIndex + 1));
                out.write(chunk, 0, chunk.length);
            }
            return out.toByteArray();
        }
        if (cmd.intValue() != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeInt(out, ParsedCommand.parseInt(cmd.intValue(), "--int"));
            for (int i = firstValueIndex; i < cmd.argCount(); i++) {
                writeInt(out, ParsedCommand.parseInt(cmd.arg(i),
                        "value #" + (i - firstValueIndex + 1)));
            }
            return out.toByteArray();
        }
        if (cmd.shortValue() != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeShort(out, parseShort(cmd.shortValue(), "--short"));
            for (int i = firstValueIndex; i < cmd.argCount(); i++) {
                writeShort(out, parseShort(cmd.arg(i),
                        "value #" + (i - firstValueIndex + 1)));
            }
            return out.toByteArray();
        }

        if (cmd.argCount() <= firstValueIndex) {
            throw new CliException(Exit.USAGE,
                    "set needs at least one value (or --hex/--int/--short/--raw)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = firstValueIndex; i < cmd.argCount(); i++) {
            String text = cmd.arg(i);
            float value;
            try {
                value = Float.parseFloat(text.trim());
            } catch (NumberFormatException e) {
                throw new CliException(Exit.USAGE, "value #" + (i - firstValueIndex + 1)
                        + " is not a number: '" + text + "'");
            }
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new CliException(Exit.USAGE, "value #" + (i - firstValueIndex + 1)
                        + " must be finite: '" + text + "'");
            }
            writeFloat(out, value);
        }
        return out.toByteArray();
    }

    /** Parses a hex string, tolerating spaces, commas, colons, underscores and 0x prefixes. */
    public static byte[] parseHex(String text, String what) throws CliException {
        if (text == null) {
            throw new CliException(Exit.USAGE, what + " needs a hex string");
        }
        StringBuilder digits = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ':'
                    || c == '_' || c == '-') {
                i++;
                continue;
            }
            if (c == '0' && i + 1 < text.length()
                    && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
                i += 2;
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                throw new CliException(Exit.USAGE,
                        what + " contains a non-hex character: '" + c + "'");
            }
            digits.append(c);
            i++;
        }
        if (digits.length() == 0) {
            throw new CliException(Exit.USAGE, what + " is empty");
        }
        if ((digits.length() & 1) != 0) {
            throw new CliException(Exit.USAGE,
                    what + " has an odd number of hex digits: " + digits.length());
        }
        byte[] out = new byte[digits.length() / 2];
        for (int j = 0; j < out.length; j++) {
            out[j] = (byte) Integer.parseInt(digits.substring(j * 2, j * 2 + 2), 16);
        }
        return out;
    }

    /** Lower-case compact hex, e.g. 0000803f. */
    public static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(digits[(b >>> 4) & 0x0f]);
            sb.append(digits[b & 0x0f]);
        }
        return sb.toString();
    }

    private static int parseShort(String text, String what) throws CliException {
        int value = ParsedCommand.parseInt(text, what);
        if (value < -32768 || value > 32767) {
            throw new CliException(Exit.USAGE,
                    what + " must fit in int16 (-32768..32767), got " + value);
        }
        return value;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value).array();
        out.write(bytes, 0, bytes.length);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        byte[] bytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) value).array();
        out.write(bytes, 0, bytes.length);
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(value).array();
        out.write(bytes, 0, bytes.length);
    }
}

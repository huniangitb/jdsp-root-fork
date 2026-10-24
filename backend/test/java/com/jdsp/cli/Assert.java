package com.jdsp.cli;

/** Minimal assertion helpers: the host tests use plain Java, no JUnit. */
public final class Assert {
    private Assert() {}

    public static void eq(Object expected, Object actual) {
        eq(expected, actual, null);
    }

    public static void eq(Object expected, Object actual, String message) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(prefix(message) + "expected <" + expected + "> but was <"
                    + actual + ">");
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void contains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("expected to find <" + needle + "> in <" + haystack + ">");
        }
    }

    public static void notContains(String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError("did not expect <" + needle + "> in <" + haystack + ">");
        }
    }

    public static void hex(byte[] actual, String expectedHex) {
        String actualHex = ValueCodec.toHex(actual);
        if (!expectedHex.equals(actualHex)) {
            throw new AssertionError("expected hex <" + expectedHex + "> but was <"
                    + actualHex + ">");
        }
    }

    /** Runs a body that must fail with a CliException carrying exactly this exit code. */
    public static CliException cliCode(int expectedCode, Body body) {
        try {
            body.run();
        } catch (CliException e) {
            if (e.exitCode() != expectedCode) {
                throw new AssertionError("expected exit code " + expectedCode + " but got "
                        + e.exitCode() + " (" + e.getMessage() + ")");
            }
            return e;
        } catch (Exception e) {
            throw new AssertionError("expected CliException(" + expectedCode
                    + ") but got " + e, e);
        }
        throw new AssertionError("expected CliException(" + expectedCode
                + ") but nothing was thrown");
    }

    private static String prefix(String message) {
        return message == null || message.isEmpty() ? "" : message + ": ";
    }
}

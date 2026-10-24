package com.jdsp.cli;

import java.io.PrintStream;

/** Tiny test registry with PASS/FAIL lines and a summary. */
public final class Suite {
    private final PrintStream out;
    private int passed;
    private int failed;

    public Suite(PrintStream out) {
        this.out = out;
    }

    public void test(String name, Body body) {
        try {
            body.run();
            passed++;
            out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            out.println("FAIL " + name + ": " + t);
        }
    }

    public int report() {
        out.println();
        out.println("== jdspcli host tests: " + passed + " passed, " + failed + " failed ==");
        out.flush();
        return failed == 0 ? 0 : 1;
    }
}

package com.jdsp.common;

/**
 * The diagnostic / debug parameter range: <b>30000-32999</b>.
 *
 * <p>These ids are not DSP settings. They are the engine's test facilities, and they must never
 * become part of the persistent configuration:
 *
 * <ul>
 *   <li>30023-30028 - mix fidelity check (0008): broken / runs counters, arm / reset / state /
 *       last bad sample count;</li>
 *   <li>32001-32002 - capture tap (0007): the 192 byte config and the 112 byte status.</li>
 * </ul>
 *
 * <p>Why this matters (found on the device, v2.1.5): a single {@code jdspcli set 30025 --int 1}
 * was written into {@code config.json}, replayed on every daemon restart as a 27th parameter and
 * so polluted the 26 parameter baseline that {@code jdspcli baseline} and the acceptance runs
 * compare against. Values are runtime state (counters, an armed flag, a ring buffer path), not
 * user settings: they are always recomputed by the engine, so storing them is never useful.
 *
 * <p>Consequence in {@code ConfigStore}: a diagnostic id is never written, never replayed and
 * never appears in {@code dump-config}; a config file that already contains one is cleaned on
 * load. Writes still reach the engine - only the persistence is skipped.
 */
public final class DiagnosticParams {
    /** First id of the diagnostic range (inclusive). */
    public static final int RANGE_START = 30000;
    /** Last id of the diagnostic range (inclusive). */
    public static final int RANGE_END = 32999;

    private DiagnosticParams() {}

    /** True for 30000-32999: forward to the engine, never store, never replay. */
    public static boolean isDiagnostic(int paramId) {
        return paramId >= RANGE_START && paramId <= RANGE_END;
    }
}

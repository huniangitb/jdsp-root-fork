package com.jdsp.common;

/**
 * The global master switch as a small state machine.
 *
 * <p>Two problems are solved here:
 * <ul>
 *   <li><b>Echo storms</b>: a client that keeps writing the value the backend already has (an old
 *       App mirroring the backend state) must not cause any side effect at all. A request for
 *       the current value is a pure no-op: no framework call, no config write, no watcher poke.</li>
 *   <li><b>Oscillation</b>: a client that alternates between on and off would make the daemon
 *       flip the effect instances back and forth; every flip reaches the audio HAL and a stream
 *       of them was measured to crash system_server. Transitions are therefore rate limited to
 *       one per {@link #minIntervalMs}, so a burst collapses into a single transition (measured:
 *       one transition per second instead of ~28 framework calls per second).</li>
 * </ul>
 *
 * <p>Semantics:
 * <ul>
 *   <li>requesting the value the backend already has: {@link #NONE}, no side effect. Such a
 *       request is an echo and carries no new intent, so it never cancels a pending transition
 *       (the pending transition is the user's explicit intent and must survive an echo);</li>
 *   <li>requesting "off" is <b>never</b> deferred: unmounting (detaching the effect instances)
 *       must be instantaneous, so an explicit off always applies at once;</li>
 *   <li>requesting "on" is rate limited: applied at once when the interval has expired,
 *       otherwise {@link #DEFER}. The watcher applies the deferred transition when the deadline
 *       passes.</li>
 * </ul>
 *
 * <p>Because the backend only ever has two states, a pending transition always points at "on"
 * (the only rate limited direction).
 *
 * <p>Pure Java so the behaviour is unit tested on the host.
 */
public final class EnableSwitch {
    /** Nothing has to be done. */
    public static final int NONE = 0;
    /** The new value has to be applied right now. */
    public static final int APPLY_NOW = 1;
    /** The new value was accepted but is rate limited; it is applied on a later poll. */
    public static final int DEFER = 2;

    /**
     * Default rate limit for transitions (ms).
     *
     * <p>Was 1000 ms; every "on" then waited up to a full second before the first instance was
     * even created, which the user reported as "the switch takes seconds".  300 ms still caps
     * the transition rate at ~3.3/s (the measured crash needed ~28 framework calls per second)
     * while keeping the whole on-transition inside the 1 s budget.
     */
    public static final long DEFAULT_MIN_INTERVAL_MS = 300L;

    private boolean applied = true;
    /** True while the transition to the opposite state waits for the rate limit. */
    private boolean pending;
    private long pendingDeadline;
    /** 0 = no transition happened yet. */
    private long lastTransitionAt;
    private final long minIntervalMs;

    public EnableSwitch() {
        this(DEFAULT_MIN_INTERVAL_MS);
    }

    public EnableSwitch(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /** The state the daemon currently has in effect. */
    public boolean applied() {
        return applied;
    }

    /** True while a transition waits for the rate limit to expire. */
    public boolean pending() {
        return pending;
    }

    /**
     * Handles one request.
     *
     * @param target the requested state
     * @param now    current time in milliseconds
     * @return {@link #NONE}, {@link #APPLY_NOW} or {@link #DEFER}
     */
    public int request(boolean target, long now) {
        if (target == applied) {
            // The backend already has this state: this request carries no new intent (it is
            // an echo of the current state), so it is a pure no-op. A pending transition
            // survives: it is the user's explicit intent and an echo must not drop it.
            return NONE;
        }
        if (target && lastTransitionAt != 0L && now - lastTransitionAt < minIntervalMs) {
            // Rate limited, but only in the "on" direction: turning on mounts the effect
            // instances again (create + attach + parameter replay), so a burst of "on"
            // requests collapses into one transition. Turning off is never deferred: the
            // user wants the instances out of the chain now.
            pending = true;
            pendingDeadline = now + minIntervalMs;
            return DEFER;
        }
        return APPLY_NOW;
    }

    /**
     * Applies a deferred transition when its deadline has passed. Called by the watcher.
     *
     * @param now current time in milliseconds
     * @return the value to apply, or null when there is nothing to do
     */
    public Boolean poll(long now) {
        if (!pending) {
            return null;
        }
        if (now < pendingDeadline) {
            return null;
        }
        pending = false;
        return Boolean.valueOf(!applied);
    }

    /**
     * Records that a transition was applied (called by the daemon after applying it).
     *
     * <p>Also used at startup to align the switch with the loaded configuration; passing
     * {@code now = 0} means "no transition happened yet", so the next transition is not rate
     * limited.
     */
    public void transitionApplied(boolean value, long now) {
        applied = value;
        lastTransitionAt = now;
        pending = false;
    }
}

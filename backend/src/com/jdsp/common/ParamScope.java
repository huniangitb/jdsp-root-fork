package com.jdsp.common;

/**
 * Where a parameter value is stored: globally, or as an override for one app.
 *
 * <p>This is the single rule used by every caller (setParam write path, getParam read path and
 * the replay on attach), so a value written by {@code set} is always found again by {@code get}.
 *
 * <p>Pure Java on purpose: it can be unit tested on the host without Android.
 */
public final class ParamScope {
    /** Global parameter: applies to every session. */
    public static final int GLOBAL = 0;
    /** Per app parameter: stored under perAppParams[pkg]. */
    public static final int PER_APP = 1;

    private ParamScope() {
    }

    /**
     * Decides the scope of a parameter of {@code pkg}.
     *
     * <ol>
     *   <li>no package (unknown owner) -&gt; {@link #GLOBAL};</li>
     *   <li>the app already has per app parameters, or it has an explicit policy entry (the user
     *       configured something for that app) -&gt; {@link #PER_APP};</li>
     *   <li>otherwise -&gt; {@link #GLOBAL}.</li>
     * </ol>
     *
     * @param pkg             package of the session, may be null or empty
     * @param hasPerAppParams whether the app already owns per app parameters
     * @param hasPolicyEntry  whether the app has an explicit policy entry
     */
    public static int decide(String pkg, boolean hasPerAppParams, boolean hasPolicyEntry) {
        if (pkg == null || pkg.length() == 0) {
            return GLOBAL;
        }
        if (hasPerAppParams || hasPolicyEntry) {
            return PER_APP;
        }
        return GLOBAL;
    }

    /** Human readable name, used in logs and stats. */
    public static String name(int scope) {
        return scope == PER_APP ? "per-app" : "global";
    }
}

package com.jdsp.common;

/**
 * Reply arithmetic for one {@code GET_PARAM} (the value half of {@code jdspcli get}).
 *
 * <p>The engine reports how many bytes it wrote: the DSP parameters return 0
 * (the classic behaviour, the caller just reads the buffer it passed in) while the diagnostic
 * parameters return the real size - 4 for the mix check counters (30023/30024) and 112 for the
 * tap status (32002). A negative value is a failure ({@code -EINVAL} / no value at all).
 *
 * <p>v2.1.5 only accepted {@code rc == 0} and treated every positive value as a failure, so
 * {@code jdspcli get} returned "no data" for 30023-30028 and 32001/32002 even though the engine
 * had answered ({@code W/jdspd: getParam ... param=32002 rc=112}). Here both 0 and any positive
 * value are success; negative stays failure. Kept in a pure Java class so the host suite can
 * cover the three cases without an Android runtime.
 */
public final class ParamReply {
    private ParamReply() {}

    /** The engine answered (it wrote {@code rc} bytes, or 0 when it reports no size). */
    public static boolean isSuccess(int rc) {
        return rc >= 0;
    }

    /**
     * How many bytes of the reply buffer are the value.
     *
     * @param rc          the engine return code (bytes written, 0, or negative on failure)
     * @param replyLength size of the buffer that was handed to the engine
     * @return the value length, or -1 when {@code rc < 0} (failure: no value)
     */
    public static int successLength(int rc, int replyLength) {
        if (rc < 0) {
            return -1;
        }
        if (rc == 0) {
            // No size reported: keep the pre-existing behaviour and hand back the whole buffer
            // (the classic DSP parameters write into it without reporting a length).
            return replyLength;
        }
        return rc < replyLength ? rc : replyLength;
    }
}

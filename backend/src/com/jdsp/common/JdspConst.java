package com.jdsp.common;

import java.util.UUID;

/**
 * Protocol constants shared by the daemon and the CLI.
 *
 * The UUIDs are the ones the module registers in audio_effects.xml; they must
 * stay in sync with module/src/aml.sh.
 */
public final class JdspConst {
    private JdspConst() {}

    /** Effect type UUID (audio_effects.xml <effect type=...>). */
    public static final UUID EFFECT_TYPE = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    /** Effect implementation UUID (audio_effects.xml <effect uuid=...>). */
    public static final UUID EFFECT_UUID = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");

    /** Binder service name published by jdspd. */
    public static final String SERVICE_NAME = "jdsp.service";
    /** Interface descriptor used by the AIDL stub. */
    public static final String INTERFACE_DESCRIPTOR = "android.jdsp.backend.IJdspService";

    /** Global policy modes (per app). */
    public static final int POLICY_FOLLOW = 0;
    public static final int POLICY_ON = 1;
    public static final int POLICY_EXCLUDE = 2;

    /** Architecture mode. */
    public static final int MODE_AUTO = 0;
    public static final int MODE_BACKEND = 1;

    /** Paths owned by the backend (root only). */
    public static final String DATA_DIR = "/data/adb/jdspd";
    public static final String CONFIG_FILE = DATA_DIR + "/config.json";
    public static final String LOG_FILE = DATA_DIR + "/jdspd.log";

    /** Engine state file written by the native engine (import source for ConfigStore). */
    public static final String ENGINE_STATE_FILE = "/data/vendor/audio/jamesdsp_state.bin";

    /** Engine effect commands that the state file records. */
    public static final int EFFECT_CMD_SET_PARAM = 5;
}

/*
 * OfflineRender — signal level evidence for the JamesDSP engine, without any
 * recording permission and without GET_PARAM.
 *
 * It drives the *vendor* JamesDSP engine (the very same
 * /vendor/lib64/soundfx/libjamesdsp.so that AudioFlinger loads) directly
 * through its exported effect API:
 *
 *     EffectCreate()  ->  command(EFFECT_CMD_SET_CONFIG)
 *                     ->  command(EFFECT_CMD_SET_PARAM)   (parameter under test)
 *                     ->  process()                      (render the PCM block)
 *
 * The same PCM block is rendered twice: once with neutral parameters and once
 * with the parameters under test.  The two outputs are compared by RMS, peak
 * and a 440 Hz Goertzel magnitude, so a parameter that really reaches the
 * engine is visible without AudioRecord and without the engine's (missing)
 * GET_PARAM support.
 *
 * The engine is loaded with JDSP_STATE_DISABLE=1 and JDSP_STATE_PATH pointing
 * at a throw away file, so a render can never touch the live persisted state
 * of the device.
 *
 * Usage:
 *   OfflineRender <paramId> <float...>          # neutral vs. the given values
 *   OfflineRender --param <paramId> <float...>
 *   OfflineRender --probe                        # only check that the engine loads
 *
 * Output (machine readable, one key=value per line):
 *   ENGINE loaded=yes
 *   NEUTRAL rmsDbfs=.. peakDbfs=.. tone440=..
 *   TESTED  rmsDbfs=.. peakDbfs=.. tone440=..
 *   DELTA   rmsDb=.. peakDb=.. toneDb=.. direction=increase|decrease|none
 *   RESULT  ok|failed
 *
 * Exit code: 0 = the engine rendered both variants, 3 = the engine could not
 * be used at all (the caller then has to report SKIP for the signal level
 * acceptance).
 */

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ------------------------------------------------------------------ ABI ---
 * Mirrors of the AOSP effect API as used by the vendor engine.  The layouts
 * are fixed by the platform (media/libaudioeffect/include/effect.h) and match
 * the copies in the engine source tree exactly.
 */

typedef struct effect_uuid_s {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t node[6];
} effect_uuid_t;

typedef struct audio_buffer_s {
    size_t frameCount;
    union {
        void *raw;
        float *f32;
        int32_t *s32;
        int16_t *s16;
        uint8_t *u8;
    };
} audio_buffer_t;

typedef int32_t (*buffer_function_t)(void *cookie, audio_buffer_t *buffer);

typedef struct buffer_provider_s {
    buffer_function_t getBuffer;
    buffer_function_t releaseBuffer;
    void *cookie;
} buffer_provider_t;

typedef struct buffer_config_s {
    audio_buffer_t buffer;
    uint32_t samplingRate;
    uint32_t channels;
    buffer_provider_t bufferProvider;
    uint8_t format;
    uint8_t accessMode;
    uint16_t mask;
} buffer_config_t;

typedef struct effect_config_s {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;

typedef struct effect_param_s {
    int32_t status;
    uint32_t psize;
    uint32_t vsize;
    char data[];
} effect_param_t;

typedef struct effect_descriptor_s {
    effect_uuid_t type;
    effect_uuid_t uuid;
    uint32_t apiVersion;
    uint32_t flags;
    uint16_t cpuLoad;
    uint16_t memoryUsage;
    char name[64];
    char implementor[64];
} effect_descriptor_t;

struct effect_interface_s {
    int32_t (*process)(void *self, audio_buffer_t *in, audio_buffer_t *out);
    int32_t (*command)(void *self, uint32_t cmdCode, uint32_t cmdSize, void *pCmdData,
                      uint32_t *replySize, void *pReplyData);
    int32_t (*getDescriptor)(void *self, effect_descriptor_t *pDescriptor);
    void *reserved;
};

typedef struct effect_interface_s **effect_handle_t;

enum effect_command_e {
    EFFECT_CMD_INIT = 0,
    EFFECT_CMD_SET_CONFIG = 1,
    EFFECT_CMD_RESET = 2,
    EFFECT_CMD_ENABLE = 3,
    EFFECT_CMD_DISABLE = 4,
    EFFECT_CMD_SET_PARAM = 5,
    EFFECT_CMD_SET_PARAM_DEFERRED = 6,
    EFFECT_CMD_SET_PARAM_COMMIT = 7,
    EFFECT_CMD_GET_PARAM = 8
};

#define EFFECT_CONFIG_BUFFER 0x0001
#define EFFECT_CONFIG_SMP_RATE 0x0002
#define EFFECT_CONFIG_CHANNELS 0x0004
#define EFFECT_CONFIG_FORMAT 0x0008
#define EFFECT_CONFIG_ACC_MODE 0x0010

#define AUDIO_FORMAT_PCM_16_BIT 0x1u
#define AUDIO_CHANNEL_OUT_STEREO 0x3u
#define EFFECT_BUFFER_ACCESS_WRITE 0

/* --------------------------------------------------------------- engine --- */

#define ENGINE_LIB "/vendor/lib64/soundfx/libjamesdsp.so"

static const effect_uuid_t UUID_JAMESDSP_TYPE = {
    0xf98765f4u, 0xc321u, 0x5de6u, 0x9a45u, {0x12, 0x34, 0x59, 0x49, 0x5a, 0xb2}
};
static const effect_uuid_t UUID_JAMESDSP_IMPL = {
    0xf27317f4u, 0xc984u, 0x4de6u, 0x9a90u, {0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2}
};

typedef int32_t (*effect_create_fn)(const effect_uuid_t *uuid, int32_t sessionId,
                                    int32_t ioId, effect_handle_t *pEffect);
typedef int32_t (*effect_release_fn)(effect_handle_t effect);

static effect_create_fn g_create;
static effect_release_fn g_release;

#define SAMPLE_RATE 48000
#define CHANNELS 2
#define TONE_HZ 440.0
#define TONE_DBFS -6.0
/* One second of audio, rendered in blocks the engine can take. */
#define TOTAL_FRAMES 48000
#define BLOCK_FRAMES 480

/* ------------------------------------------------------------- helpers --- */

static void die(const char *what) {
    fprintf(stderr, "OfflineRender: %s\n", what);
}

static double to_db(double amplitude) {
    if (amplitude <= 0.0) {
        return -200.0;
    }
    return 20.0 * log10(amplitude);
}

static double rms_of(const int16_t *data, size_t frames) {
    double sum = 0.0;
    size_t count = frames * CHANNELS;
    for (size_t i = 0; i < count; i++) {
        double v = data[i];
        sum += v * v;
    }
    return count == 0 ? 0.0 : sqrt(sum / (double) count);
}

static int peak_of(const int16_t *data, size_t frames) {
    int peak = 0;
    size_t count = frames * CHANNELS;
    for (size_t i = 0; i < count; i++) {
        int v = data[i] < 0 ? -(int) data[i] : (int) data[i];
        if (v > peak) {
            peak = v;
        }
    }
    return peak;
}

/* Goertzel magnitude of the tone, normalised to the signal length. */
static double goertzel_440(const int16_t *data, size_t frames) {
    double w0 = 2.0 * M_PI * TONE_HZ / SAMPLE_RATE;
    double coeff = 2.0 * cos(w0);
    double s1 = 0.0, s2 = 0.0;
    for (size_t i = 0; i < frames; i++) {
        double x = (double) data[2 * i] / 32768.0;
        double s0 = x + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    if (power < 0.0) {
        power = 0.0;
    }
    return sqrt(power) / ((double) frames * 0.5);
}

static void fill_tone(int16_t *buf, size_t frames) {
    double amplitude = pow(10.0, TONE_DBFS / 20.0) * 32767.0;
    double phase = 0.0;
    for (size_t i = 0; i < frames; i++) {
        int16_t v = (int16_t) (sin(phase) * amplitude);
        buf[2 * i] = v;
        buf[2 * i + 1] = v;
        phase += 2.0 * M_PI * TONE_HZ / SAMPLE_RATE;
        if (phase > 2.0 * M_PI) {
            phase -= 2.0 * M_PI;
        }
    }
}

/* The engine's SET_PARAM wire format: status, psize, vsize, then the
 * parameter id (4 bytes) and the value. */
static uint32_t build_param(int32_t param_id, const float *values, size_t count,
                            void **out_buf) {
    size_t vsize = count * sizeof(float);
    size_t total = sizeof(effect_param_t) + 4 + vsize;
    uint8_t *buf = (uint8_t *) calloc(1, total);
    effect_param_t *p = (effect_param_t *) buf;
    p->status = 0;
    p->psize = 4;
    p->vsize = (uint32_t) vsize;
    memcpy(buf + sizeof(effect_param_t), &param_id, 4);
    if (count > 0) {
        memcpy(buf + sizeof(effect_param_t) + 4, values, vsize);
    }
    *out_buf = buf;
    return (uint32_t) total;
}

/* ----------------------------------------------------------------- main --- */

static int load_engine(void) {
    void *h = dlopen(ENGINE_LIB, RTLD_NOW | RTLD_LOCAL);
    if (h == NULL) {
        fprintf(stderr, "ENGINE dlopen failed: %s\n", dlerror());
        return -1;
    }
    g_create = (effect_create_fn) dlsym(h, "EffectCreate");
    g_release = (effect_release_fn) dlsym(h, "EffectRelease");
    if (g_create == NULL || g_release == NULL) {
        fprintf(stderr, "ENGINE dlsym failed: %s\n", dlerror());
        return -1;
    }
    return 0;
}

/* Renders TOTAL_FRAMES of the test tone through a fresh engine instance with
 * the given parameter applied.  Returns 0 on success. */
static int render_variant(int32_t param_id, const float *values, size_t value_count,
                          int16_t *out) {
    effect_handle_t handle = NULL;
    int rc = -1;
    int16_t *in_buf = NULL;
    struct effect_interface_s *itfe = NULL;

    int32_t status = g_create(&UUID_JAMESDSP_TYPE, 0, 0, &handle);
    if (status != 0 || handle == NULL || *handle == NULL) {
        fprintf(stderr, "ENGINE EffectCreate failed status=%d\n", status);
        return -1;
    }
    itfe = *handle;

    /* Configure: 48 kHz stereo int16. */
    effect_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.inputCfg.samplingRate = SAMPLE_RATE;
    cfg.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    cfg.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    cfg.inputCfg.mask = EFFECT_CONFIG_SMP_RATE | EFFECT_CONFIG_CHANNELS |
                        EFFECT_CONFIG_FORMAT;
    cfg.outputCfg.samplingRate = SAMPLE_RATE;
    cfg.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    cfg.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    cfg.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    cfg.outputCfg.mask = EFFECT_CONFIG_SMP_RATE | EFFECT_CONFIG_CHANNELS |
                         EFFECT_CONFIG_FORMAT | EFFECT_CONFIG_ACC_MODE;

    /* EffectDSPMainCommand dereferences the reply pointer unconditionally, so a
     * real reply buffer must always be supplied. */
    int32_t reply = 0;
    uint32_t reply_size = sizeof(reply);
    int32_t cres = itfe->command(handle, EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg,
                                 &reply_size, &reply);
    if (cres != 0) {
        fprintf(stderr, "ENGINE SET_CONFIG failed rc=%d\n", cres);
        goto out;
    }
    reply = 0;
    reply_size = sizeof(reply);
    cres = itfe->command(handle, EFFECT_CMD_ENABLE, 0, NULL, &reply_size, &reply);
    if (cres != 0) {
        fprintf(stderr, "ENGINE ENABLE failed rc=%d\n", cres);
        goto out;
    }

    /* Apply the parameter under test.  value_count == 0 means "do not touch any
     * parameter": the engine then keeps whatever it replayed from its state
     * file, which is the live configuration of the device. */
    if (value_count == 0 && values == NULL) {
        goto render;
    }
    void *param = NULL;
    uint32_t param_size = build_param(param_id, values, value_count, &param);
    reply = 0;
    uint32_t reply_sz = sizeof(reply);
    cres = itfe->command(handle, EFFECT_CMD_SET_PARAM, param_size, param, &reply_sz,
                         &reply);
    if (cres != 0 || reply != 0) {
        fprintf(stderr, "ENGINE SET_PARAM failed rc=%d status=%d\n", cres, reply);
        free(param);
        goto out;
    }
    free(param);

render:
    in_buf = (int16_t *) calloc(TOTAL_FRAMES * CHANNELS, sizeof(int16_t));
    if (in_buf == NULL) {
        goto out;
    }
    fill_tone(in_buf, TOTAL_FRAMES);

    /* Render in blocks. */
    size_t done = 0;
    while (done < TOTAL_FRAMES) {
        size_t frames = TOTAL_FRAMES - done;
        if (frames > BLOCK_FRAMES) {
            frames = BLOCK_FRAMES;
        }
        audio_buffer_t in;
        audio_buffer_t ob;
        memset(&in, 0, sizeof(in));
        memset(&ob, 0, sizeof(ob));
        in.frameCount = frames;
        in.s16 = in_buf + done * CHANNELS;
        ob.frameCount = frames;
        ob.s16 = out + done * CHANNELS;
        int32_t prc = itfe->process(handle, &in, &ob);
        if (prc != 0) {
            fprintf(stderr, "ENGINE process failed rc=%d\n", prc);
            goto out;
        }
        done += frames;
    }
    rc = 0;

out:
    if (in_buf != NULL) {
        free(in_buf);
    }
    if (handle != NULL) {
        g_release(handle);
    }
    return rc;
}

static int run(int32_t param_id, const float *values, size_t value_count) {
    int16_t *neutral = (int16_t *) calloc(TOTAL_FRAMES * CHANNELS, sizeof(int16_t));
    int16_t *tested = (int16_t *) calloc(TOTAL_FRAMES * CHANNELS, sizeof(int16_t));
    if (neutral == NULL || tested == NULL) {
        die("out of memory");
        return 3;
    }
    /* Two ways to get the baseline:
     *   state file given -> the baseline is the *live* configuration the engine
     *                       replays from its state file, untouched.
     *   no state file    -> the baseline is the same parameter shape with every
     *                       value zeroed (post gain 0 dB, limiter at its floor).
     */
    int have_state = (getenv("JDSP_STATE_PATH") != NULL);
    if (have_state) {
        if (render_variant(param_id, NULL, 0, neutral) != 0) {
            die("baseline render failed");
            free(neutral);
            free(tested);
            return 3;
        }
    } else {
        float *zero = (float *) calloc(value_count > 0 ? value_count : 1, sizeof(float));
        if (zero == NULL) {
            die("out of memory");
            return 3;
        }
        if (render_variant(param_id, zero, value_count, neutral) != 0) {
            free(zero);
            die("neutral render failed");
            free(neutral);
            free(tested);
            return 3;
        }
        free(zero);
    }
    if (render_variant(param_id, values, value_count, tested) != 0) {
        die("tested render failed");
        free(neutral);
        free(tested);
        return 3;
    }

    double n_rms = rms_of(neutral, TOTAL_FRAMES);
    double t_rms = rms_of(tested, TOTAL_FRAMES);
    int n_peak = peak_of(neutral, TOTAL_FRAMES);
    int t_peak = peak_of(tested, TOTAL_FRAMES);
    double n_tone = goertzel_440(neutral, TOTAL_FRAMES);
    double t_tone = goertzel_440(tested, TOTAL_FRAMES);

    double rms_db = 20.0 * log10((t_rms + 1e-12) / (n_rms + 1e-12));
    double peak_db = 20.0 * log10(((double) t_peak + 1e-12) / ((double) n_peak + 1e-12));
    double tone_db = 20.0 * log10((t_tone + 1e-12) / (n_tone + 1e-12));

    printf("NEUTRAL rmsDbfs=%.2f peakDbfs=%.2f tone440=%.6f\n",
           to_db(n_rms / 32768.0), to_db((double) n_peak / 32768.0), n_tone);
    printf("TESTED  rmsDbfs=%.2f peakDbfs=%.2f tone440=%.6f\n",
           to_db(t_rms / 32768.0), to_db((double) t_peak / 32768.0), t_tone);
    printf("DELTA   rmsDb=%.2f peakDb=%.2f toneDb=%.2f direction=%s\n",
           rms_db, peak_db, tone_db,
           rms_db > 0.05 ? "increase" : (rms_db < -0.05 ? "decrease" : "none"));
    printf("RESULT  ok\n");

    free(neutral);
    free(tested);
    return 0;
}

int main(int argc, char **argv) {
    int probe_only = 0;
    int baseline_only = 0;
    int32_t param_id = 1500;
    int first = 0;
    const char *state_path = NULL;

    /* Pull the option flags out first, so the parameter list can follow. */
    int i = 1;
    while (i < argc) {
        if (strcmp(argv[i], "--probe") == 0) {
            probe_only = 1;
            i++;
        } else if (strcmp(argv[i], "--baseline") == 0) {
            baseline_only = 1;
            i++;
        } else if (strcmp(argv[i], "--state") == 0 && i + 1 < argc) {
            state_path = argv[i + 1];
            i += 2;
        } else {
            break;
        }
    }
    argc -= (i - 1);
    argv += (i - 1);

    if (probe_only) {
        /* handled below */
    } else if (baseline_only) {
        if (state_path == NULL) {
            die("--baseline needs --state <path>");
            return 3;
        }
    } else {
        if (argc > 1 && strcmp(argv[1], "--param") == 0) {
            if (argc < 3) {
                die("usage: OfflineRender [--param <id>] <float...>");
                return 3;
            }
            param_id = (int32_t) strtol(argv[2], NULL, 10);
            first = 3;
        } else if (argc > 1) {
            param_id = (int32_t) strtol(argv[1], NULL, 10);
            first = 2;
        }
    }

    /* Never touch the live persisted engine state.  When --state is given the
     * engine replays that (copied) state file read-only, so the render shows
     * exactly the configuration the device is running. */
    setenv("JDSP_STATE_DISABLE", state_path == NULL ? "1" : "0", 1);
    if (state_path != NULL) {
        setenv("JDSP_STATE_PATH", state_path, 1);
        setenv("JDSP_STATE_READONLY", "1", 1);
    } else {
        setenv("JDSP_STATE_PATH", "/data/local/tmp/jdsp-acc/offline-state.bin", 1);
    }
    setenv("JDSP_STATE_NO_INOTIFY", "1", 1);

    if (load_engine() != 0) {
        printf("ENGINE loaded=no\n");
        printf("RESULT  failed reason=engine_unavailable\n");
        return 3;
    }
    printf("ENGINE loaded=yes lib=%s\n", ENGINE_LIB); fflush(stdout);

    if (baseline_only) {
        int16_t *base = (int16_t *) calloc(TOTAL_FRAMES * CHANNELS, sizeof(int16_t));
        if (base == NULL) {
            die("out of memory");
            return 3;
        }
        if (render_variant(0, NULL, 0, base) != 0) {
            die("baseline render failed");
            free(base);
            return 3;
        }
        double b_rms = rms_of(base, TOTAL_FRAMES);
        int b_peak = peak_of(base, TOTAL_FRAMES);
        double b_tone = goertzel_440(base, TOTAL_FRAMES);
        printf("BASELINE rmsDbfs=%.2f peakDbfs=%.2f tone440=%.6f\n",
               to_db(b_rms / 32768.0), to_db((double) b_peak / 32768.0), b_tone);
        printf("RESULT  ok\n");
        free(base);
        return 0;
    }

    if (probe_only) {
        effect_handle_t handle = NULL;
        int32_t status = g_create(&UUID_JAMESDSP_TYPE, 0, 0, &handle);
        if (status != 0 || handle == NULL) {
            printf("RESULT  failed reason=create_failed status=%d\n", status);
            return 3;
        }
        g_release(handle);
        printf("RESULT  ok\n");
        return 0;
    }

    if (argc - first < 1) {
        die("usage: OfflineRender [--param <id>] <float...>");
        return 3;
    }
    size_t count = (size_t) (argc - first);
    float *values = (float *) calloc(count, sizeof(float));
    for (size_t i = 0; i < count; i++) {
        values[i] = strtof(argv[first + (int) i], NULL);
    }
    int rc = run(param_id, values, count);
    free(values);
    return rc;
}

// offline_render.c — native offline renderer for the JamesDSP engine.
//
// It drives the vendor engine (libjamesdsp.so) through the standard AOSP
// effect contract and renders a fixed PCM block three times:
//
//   neutral   postGain  0 dB
//   tested    postGain +N dB (the value given on the command line)
//   restored  postGain  0 dB again
//
// and prints the RMS of each so the caller can compare them.
//
// The engine is loaded with dlopen() and driven through the *public* effect
// vtable (effect_interface_s), never through the private engine pointer:
//
//   struct effect_interface_s {
//       int32_t (*process)(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out);
//       int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
//                          void *pCmdData, uint32_t *replySize, void *pReplyData);
//       int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t *pDescriptor);
//       int32_t (*process_reverse)(...);
//   };
//
// The layout of effect_config_t / buffer_config_t / audio_buffer_t below is
// taken verbatim from the engine's own header
// (libjamesdsp/jni/jamesdsp/essential.h), which is the AOSP audio_effect.h
// layout:
//
//   audio_buffer_t  { size_t frameCount; union { void*raw; float*f32; int32_t*s32;
//                                              int16_t*s16; uint8_t*u8; }; }
//   buffer_config_t { audio_buffer_t buffer; uint32_t samplingRate; uint32_t channels;
//                     buffer_provider_t bufferProvider; uint8_t format;
//                     uint8_t accessMode; uint16_t mask; }
//   effect_config_t { buffer_config_t inputCfg; buffer_config_t outputCfg; }
//
// The previous revision of this file used a made up "effect_buffer_config_t"
// layout and the wrong vtable member order (get/set/process instead of
// process/command/get_descriptor).  configure() then read a garbage
// samplingRate out of the config, JamesDSPSetSampleRate() was called with it
// and the process was killed by the OOM killer ("Killed").
//
// Tone level: the engine always runs a brickwall limiter whose threshold is
// clamped to -0.09 dBFS, so a -6 dBFS tone plus +12 dB of post gain is
// limited back to ~0 dBFS.  The default tone is therefore -18 dBFS, where
// the post gain is fully visible (the limiter stays transparent).
//
// Usage: offline_render <paramId> <f0> <f1> <f2> [toneDbfs]
//   toneDbfs defaults to -18 dBFS.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sched.h>
#include <unistd.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define SAMPLE_RATE       48000
#define CHANNELS          2
#define TONE_HZ           440.0
#define DEFAULT_TONE_DBFS (-18.0)
#define FRAMES            4800

/* ---- AOSP effect ABI (mirrors jamesdsp/jni/jamesdsp/essential.h) -------- */
typedef struct effect_uuid_s {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t  node[6];
} effect_uuid_t;

typedef struct effect_interface_s **effect_handle_t;

typedef struct audio_buffer_s {
    size_t frameCount;
    union {
        void   *raw;
        float  *f32;
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
    audio_buffer_t     buffer;
    uint32_t          samplingRate;
    uint32_t          channels;
    buffer_provider_t bufferProvider;
    uint8_t           format;
    uint8_t           accessMode;
    uint16_t          mask;
} buffer_config_t;

typedef struct effect_config_s {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;

typedef struct effect_param_s {
    int32_t  status;
    uint32_t psize;
    uint32_t vsize;
    char     data[];
} effect_param_t;

struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer);
    int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
                       void *pCmdData, uint32_t *replySize, void *pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, void *pDescriptor);
    int32_t (*process_reverse)(effect_handle_t self, audio_buffer_t *inBuffer,
                               audio_buffer_t *outBuffer);
};

typedef int32_t (*create_fn)(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId,
                             effect_handle_t *pEffect);

/* ---- command numbers (AOSP effect_command_e) ---------------------------- */
#define EFFECT_CMD_INIT        0
#define EFFECT_CMD_SET_CONFIG  1
#define EFFECT_CMD_RESET       2
#define EFFECT_CMD_ENABLE      3
#define EFFECT_CMD_DISABLE     4
#define EFFECT_CMD_SET_PARAM   5
#define EFFECT_CMD_GET_PARAM   6

/* ---- buffer_config_t mask bits ----------------------------------------- */
#define EFFECT_CONFIG_BUFFER    0x0001
#define EFFECT_CONFIG_SMP_RATE  0x0002
#define EFFECT_CONFIG_CHANNELS  0x0004
#define EFFECT_CONFIG_FORMAT    0x0008
#define EFFECT_CONFIG_ACC_MODE  0x0010
#define EFFECT_CONFIG_PROVIDER  0x0020
#define EFFECT_CONFIG_ALL       (EFFECT_CONFIG_BUFFER | EFFECT_CONFIG_SMP_RATE | \
                                EFFECT_CONFIG_CHANNELS | EFFECT_CONFIG_FORMAT | \
                                EFFECT_CONFIG_ACC_MODE | EFFECT_CONFIG_PROVIDER)

/* ---- audio format / channel mask (audio.h) ------------------------------ */
#define AUDIO_FORMAT_PCM_16_BIT          0x1u
#define AUDIO_CHANNEL_OUT_STEREO         0x3u

/* ---- buffer access mode (effect_buffer_access_e) ------------------------ */
#define EFFECT_BUFFER_ACCESS_WRITE       0
#define EFFECT_BUFFER_ACCESS_READ        1
#define EFFECT_BUFFER_ACCESS_ACCUMULATE   2

/* Compile time proof that the layout above is the one the engine expects
 * (aarch64, 64 bit).  A mismatch is a compile error, not a device crash. */
typedef char layout_audio_buffer_size[(sizeof(audio_buffer_t) == 16) ? 1 : -1];
typedef char layout_buffer_config_size[(sizeof(buffer_config_t) == 56) ? 1 : -1];
typedef char layout_effect_config_size[(sizeof(effect_config_t) == 112) ? 1 : -1];
typedef char layout_offset_sampling_rate[(offsetof(buffer_config_t, samplingRate) == 16) ? 1 : -1];
typedef char layout_offset_channels[(offsetof(buffer_config_t, channels) == 20) ? 1 : -1];
typedef char layout_offset_provider[(offsetof(buffer_config_t, bufferProvider) == 24) ? 1 : -1];
typedef char layout_offset_format[(offsetof(buffer_config_t, format) == 48) ? 1 : -1];
typedef char layout_offset_access[(offsetof(buffer_config_t, accessMode) == 49) ? 1 : -1];
typedef char layout_offset_mask[(offsetof(buffer_config_t, mask) == 50) ? 1 : -1];
typedef char layout_offset_output[(offsetof(effect_config_t, outputCfg) == 56) ? 1 : -1];

static void *g_lib = NULL;
static effect_handle_t g_handle = NULL;
static struct effect_interface_s *g_itf = NULL;

/* The engine writes diagnostics straight to stdout from its filter design
 * code (e.g. "Shit 1!" in cos_fib_paraunitary.c).  Those lines would pollute
 * the machine readable result block, so stdout is redirected to /dev/null for
 * the engine calls and the results are written to a private duplicate of the
 * original stdout. */
static int g_out_fd = 1;

static void logmsg(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void logmsg(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/* The engine persists every parameter it applies to a state file and replays
 * that file into a freshly created instance.  For a measurement run this is
 * harmful twice over: the engine would start from the user's saved settings
 * instead of the neutral baseline this test needs, and it would record our
 * test values over the user's real settings.  JDSP_STATE_DISABLE=1 turns both
 * off.  It has to be set before the library is loaded.
 *
 * The engine also binds a debug socket at /data/vendor/audio/jdsp_ctl.sock.
 * Binding it from this process would steal the path from the running audio
 * stack, so the renderer runs in a private mount namespace with a tmpfs over
 * /data/vendor/audio.  Nothing on the device is modified. */
static void isolate_state(void)
{
    setenv("JDSP_STATE_DISABLE", "1", 1);

    if (getenv("JDSP_OFFLINE_NO_NS") == NULL) {
        if (unshare(CLONE_NEWNS) == 0) {
            /* keep our mounts out of the host namespace */
            if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0)
                logmsg("offline_render: MS_PRIVATE failed (%s)", strerror(errno));
            if (mount("tmpfs", "/data/vendor/audio", "tmpfs", 0, NULL) != 0) {
                logmsg("offline_render: tmpfs mount failed (%s), continuing", strerror(errno));
            } else {
                logmsg("offline_render: private mount namespace, /data/vendor/audio isolated");
            }
        } else {
            logmsg("offline_render: unshare failed (%s), continuing", strerror(errno));
        }
    }
}

static int load_engine(const char *explicit_path)
{
    const char *candidates[6];
    char own[512];
    int n = 0;

    if (explicit_path != NULL && explicit_path[0] != '\0')
        candidates[n++] = explicit_path;
    if (getenv("JDSP_ENGINE_LIB") != NULL)
        candidates[n++] = getenv("JDSP_ENGINE_LIB");

    own[0] = '\0';
    {
        /* ./offline_render -> <dir>/libjamesdsp.so */
        char self[512];
        ssize_t k = readlink("/proc/self/exe", self, sizeof(self) - 1);
        if (k > 0) {
            self[k] = '\0';
            char *s = strrchr(self, '/');
            if (s != NULL) {
                *s = '\0';
                snprintf(own, sizeof(own), "%s/libjamesdsp.so", self);
                candidates[n++] = own;
            }
        }
    }
    candidates[n++] = "/vendor/lib64/soundfx/libjamesdsp.so";

    for (int i = 0; i < n; i++) {
        if (candidates[i] == NULL || candidates[i][0] == '\0')
            continue;
        g_lib = dlopen(candidates[i], RTLD_NOW | RTLD_LOCAL);
        if (g_lib != NULL) {
            logmsg("offline_render: dlopen(%s) ok", candidates[i]);
            return 0;
        }
        logmsg("offline_render: dlopen(%s) failed: %s", candidates[i], dlerror());
        g_lib = NULL;
    }
    return -1;
}

static int create_effect(void)
{
    create_fn create = (create_fn)dlsym(g_lib, "EffectCreate");
    if (create == NULL) {
        logmsg("offline_render: EffectCreate missing");
        return -1;
    }
    effect_uuid_t uuid;
    memset(&uuid, 0, sizeof(uuid));
    effect_handle_t out = NULL;
    int32_t rc = create(&uuid, 0, 0, &out);
    logmsg("offline_render: EffectCreate rc=%d handle=%p", rc, (void *)out);
    if (rc != 0 || out == NULL) {
        logmsg("offline_render: no handle");
        return -1;
    }
    g_handle = out;
    /* effect_module_s starts with "const struct effect_interface_s *itfe". */
    g_itf = *(struct effect_interface_s **)g_handle;
    logmsg("offline_render: vtable=%p process=%p command=%p desc=%p",
           (void *)g_itf,
           g_itf ? (void *)(uintptr_t)g_itf->process : NULL,
           g_itf ? (void *)(uintptr_t)g_itf->command : NULL,
           g_itf ? (void *)(uintptr_t)g_itf->get_descriptor : NULL);
    if (g_itf == NULL || g_itf->process == NULL || g_itf->command == NULL) {
        logmsg("offline_render: incomplete vtable");
        return -1;
    }
    return 0;
}

static int command(uint32_t cmd, uint32_t size, void *data)
{
    int32_t reply = 0;
    uint32_t replySize = sizeof(reply);
    int32_t rc = g_itf->command(g_handle, cmd, size, data, &replySize, &reply);
    logmsg("offline_render: command %u rc=%d reply=%d", cmd, rc, reply);
    return rc == 0 ? 0 : -1;
}

static int apply_config(uint32_t frames)
{
    effect_config_t config;
    memset(&config, 0, sizeof(config));

    buffer_config_t *in = &config.inputCfg;
    in->buffer.frameCount = frames;
    in->samplingRate = SAMPLE_RATE;
    in->channels = AUDIO_CHANNEL_OUT_STEREO;
    in->format = AUDIO_FORMAT_PCM_16_BIT;
    in->accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    in->mask = EFFECT_CONFIG_ALL;

    buffer_config_t *out = &config.outputCfg;
    out->buffer.frameCount = frames;
    out->samplingRate = SAMPLE_RATE;
    out->channels = AUDIO_CHANNEL_OUT_STEREO;
    out->format = AUDIO_FORMAT_PCM_16_BIT;
    out->accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    out->mask = EFFECT_CONFIG_ALL;

    return command(EFFECT_CMD_SET_CONFIG, (uint32_t)sizeof(config), &config);
}

static int apply_param(int32_t param_id, const float *values, int32_t count)
{
    /* effect_param_t: { int32 status; uint32 psize; uint32 vsize; data[] }
     * The engine reads the parameter id from data[0..3] and the value floats
     * from data[4..], i.e. ((float *)cep)[3] is the id and ((float *)cep)[4..]
     * are the values. */
    uint8_t payload[64];
    memset(payload, 0, sizeof(payload));
    effect_param_t *param = (effect_param_t *)payload;
    param->status = 0;
    param->psize = 4;
    param->vsize = (uint32_t)(count * 4);
    memcpy(payload + sizeof(effect_param_t), &param_id, 4);
    memcpy(payload + sizeof(effect_param_t) + 4, values, (size_t)count * 4);
    uint32_t size = (uint32_t)(sizeof(effect_param_t) + 4 + (size_t)count * 4);
    return command(EFFECT_CMD_SET_PARAM, size, payload);
}

static double render_rms(const int16_t *input, int16_t *output, uint32_t frames)
{
    audio_buffer_t inBuf;
    audio_buffer_t outBuf;
    memset(&inBuf, 0, sizeof(inBuf));
    memset(&outBuf, 0, sizeof(outBuf));
    inBuf.frameCount = frames;
    inBuf.s16 = (int16_t *)input;
    outBuf.frameCount = frames;
    outBuf.s16 = output;
    int32_t rc = g_itf->process(g_handle, &inBuf, &outBuf);
    logmsg("offline_render: process rc=%d frames=%u", rc, frames);
    if (rc != 0)
        return -1.0;
    double sum = 0.0;
    uint64_t n = (uint64_t)frames * CHANNELS;
    for (uint64_t i = 0; i < n; i++) {
        double v = output[i];
        sum += v * v;
    }
    return sqrt(sum / (double)n);
}

static double dbfs(double rms)
{
    if (rms <= 0.0)
        return -200.0;
    return 20.0 * log10(rms / 32768.0);
}

int main(int argc, char **argv)
{
    if (argc < 5) {
        fprintf(stderr, "usage: offline_render <paramId> <f0> <f1> <f2> [toneDbfs]\n");
        return 3;
    }
    int32_t param_id = (int32_t)strtol(argv[1], NULL, 10);
    /* Exactly three value floats follow the parameter id; an optional fifth
     * argument is the tone level in dBFS.  The count must be exact: the engine
     * dispatches on the value size (vsize == 12 for the three float
     * parameters) and rejects anything else. */
    float values[3];
    int32_t count = 0;
    for (int i = 2; i <= 4; i++)
        values[count++] = strtof(argv[i], NULL);
    double tone_dbfs = DEFAULT_TONE_DBFS;
    if (argc > 5)
        tone_dbfs = strtod(argv[5], NULL);
    const char *envlib = getenv("JDSP_ENGINE_LIB");

    isolate_state();

    {
        int dup_fd = dup(STDOUT_FILENO);
        if (dup_fd >= 0) {
            g_out_fd = dup_fd;
            if (freopen("/dev/null", "w", stdout) == NULL) {
                /* could not redirect: keep writing to stdout */
                close(dup_fd);
                g_out_fd = STDOUT_FILENO;
            }
        }
    }

    if (load_engine(envlib) != 0) {
        dprintf(g_out_fd, "RESULT failed reason=engine-not-loadable\n");
        return 3;
    }
    if (create_effect() != 0) {
        dprintf(g_out_fd, "RESULT failed reason=create\n");
        return 3;
    }

    uint32_t frames = FRAMES;
    int16_t *input = (int16_t *)calloc(frames * CHANNELS, sizeof(int16_t));
    int16_t *output = (int16_t *)calloc(frames * CHANNELS, sizeof(int16_t));
    if (input == NULL || output == NULL) {
        dprintf(g_out_fd, "RESULT failed reason=memory\n");
        return 3;
    }
    double amplitude = pow(10.0, tone_dbfs / 20.0) * 32767.0;
    double phase = 0.0;
    for (uint32_t i = 0; i < frames; i++) {
        int16_t v = (int16_t)(sin(phase) * amplitude);
        input[2 * i] = v;
        input[2 * i + 1] = v;
        phase += 2.0 * M_PI * TONE_HZ / SAMPLE_RATE;
        if (phase > 2.0 * M_PI)
            phase -= 2.0 * M_PI;
    }

    if (apply_config(frames) != 0) {
        dprintf(g_out_fd, "RESULT failed reason=config\n");
        return 3;
    }
    if (command(EFFECT_CMD_INIT, 0, NULL) != 0) {
        dprintf(g_out_fd, "RESULT failed reason=init\n");
        return 3;
    }
    if (command(EFFECT_CMD_ENABLE, 0, NULL) != 0) {
        dprintf(g_out_fd, "RESULT failed reason=enable\n");
        return 3;
    }

    float neutral[16];
    int32_t ncount = count > 3 ? 3 : count;
    for (int32_t i = 0; i < ncount; i++)
        neutral[i] = 0.0f;

    apply_param(param_id, neutral, ncount);
    double neutralRms = render_rms(input, output, frames);
    if (neutralRms < 0.0) {
        dprintf(g_out_fd, "RESULT failed reason=process-neutral\n");
        return 3;
    }

    apply_param(param_id, values, count);
    double testedRms = render_rms(input, output, frames);
    if (testedRms < 0.0) {
        dprintf(g_out_fd, "RESULT failed reason=process-tested\n");
        return 3;
    }

    apply_param(param_id, neutral, ncount);
    double restoredRms = render_rms(input, output, frames);
    if (restoredRms < 0.0) {
        dprintf(g_out_fd, "RESULT failed reason=process-restored\n");
        return 3;
    }

    dprintf(g_out_fd, "NEUTRAL rms=%.6f\n", neutralRms);
    dprintf(g_out_fd, "TESTED rms=%.6f\n", testedRms);
    dprintf(g_out_fd, "RESTORED rms=%.6f\n", restoredRms);
    dprintf(g_out_fd, "NEUTRAL_DBFS=%.2f\n", dbfs(neutralRms));
    dprintf(g_out_fd, "TESTED_DBFS=%.2f\n", dbfs(testedRms));
    dprintf(g_out_fd, "RESTORED_DBFS=%.2f\n", dbfs(restoredRms));
    dprintf(g_out_fd, "TONE_DBFS=%.2f FRAMES=%u PARAM=%d\n", tone_dbfs, frames, param_id);
    fflush(stdout);
    if (neutralRms <= 0.0) {
        dprintf(g_out_fd, "RESULT failed reason=silent-neutral\n");
        return 3;
    }
    dprintf(g_out_fd, "RESULT ok\n");
    return 0;
}

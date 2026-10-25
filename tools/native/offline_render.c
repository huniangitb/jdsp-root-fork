// offline_render.c — native offline renderer for the JamesDSP engine.
//
// It drives the engine through the standard AOSP effect contract:
//   int32_t EffectCreate(const effect_uuid_t *uuid, int32_t session,
//                        int32_t io, effect_handle_t *pHandle);
//   effect_handle_t points to effect_interface_t, whose members are
//     get / set / process / release / get_descriptor,
//   and whose commands use the AOSP numbers
//     INIT = 0, SET_CONFIG = 1, RESET = 2, ENABLE = 3, DISABLE = 4,
//     SET_PARAM = 5, GET_PARAM = 6.
//   SET_PARAM carries effect_param_t { int32 status; uint32 psize;
//   uint32 vsize; uint8 data[] } with psize = 4 (the parameter id) and
//   vsize = 12 (three float32: limiter threshold / release / postGain).
//
// The program renders the same fixed 440 Hz / -6 dBFS block three times:
//   neutral   (postGain  0 dB)
//   tested    (postGain +12 dB, the value given on the command line)
//   restored  (postGain  0 dB again)
// and prints the RMS of each so the caller can compare them.
//
// Usage: offline_render <paramId> <float...>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define SAMPLE_RATE 48000
#define CHANNELS 2
#define TONE_HZ 440.0
#define TONE_DBFS (-6.0)

typedef void *effect_handle_t;
typedef int32_t effect_uuid_t;

typedef struct effect_buffer_config_s {
    uint32_t bufferCount;
    uint32_t frameCount;
    uint32_t channelMask;
    uint32_t sampleRate;
    uint32_t format;
    uint32_t dataOffset;
    uint32_t frameSize;
} effect_buffer_config_t;

typedef struct effect_config_s {
    effect_buffer_config_t inputBuffer;
    effect_buffer_config_t outputBuffer;
    int32_t accessMode;
    int32_t mixRate;
    uint32_t channelMixCount;
    uint32_t channelMixerChannelCount;
    int32_t channelMixerFlags;
    int32_t volume;
    uint32_t scalarCount;
    int32_t *scalars;
    int32_t effectClient;
    int32_t playEnable;
    int32_t auth2_0;
    int32_t threadId;
    int32_t priority;
    int32_t state;
} effect_config_t;

typedef struct audio_buffer_s {
    uint32_t structSize;
    void *data;
    uint32_t offset;
    uint32_t size;
} audio_buffer_t;

typedef struct effect_param_s {
    int32_t status;
    uint32_t psize;
    uint32_t vsize;
    uint8_t data[0];
} effect_param_t;

#define EFFECT_CMD_INIT 0
#define EFFECT_CMD_SET_CONFIG 1
#define EFFECT_CMD_RESET 2
#define EFFECT_CMD_ENABLE 3
#define EFFECT_CMD_DISABLE 4
#define EFFECT_CMD_SET_PARAM 5
#define EFFECT_CMD_GET_PARAM 6

#define AUDIO_FORMAT_PCM_16BIT 1
#define EFFECT_BUFFER_ACCESS_RW 1
#define EFFECT_TYPE_CONFIG 0
#define EFFECT_TYPE_PARAM 1
#define EFFECT_TYPE_ENABLE 2
#define EFFECT_TYPE_DISABLE 3

struct effect_interface_s;
typedef struct effect_interface_s {
    int32_t (*get)(effect_handle_t, int32_t type, int32_t *pValue, void *pData);
    int32_t (*set)(effect_handle_t, int32_t type, int32_t *pValue, void *pData);
    int32_t (*process)(effect_handle_t, int32_t *pOffset, int32_t *pNumFrames,
                       effect_config_t *pConfig, audio_buffer_t *pBuffer);
    int32_t (*release)(effect_handle_t);
    int32_t (*get_descriptor)(effect_handle_t, uint32_t pSize, void *pDescriptor);
} effect_interface_t;

typedef int32_t (*create_fn)(const effect_uuid_t *uuid, int32_t session, int32_t io,
                             effect_handle_t *pHandle);
typedef int32_t (*engine_command_fn)(void *engine, uint32_t cmd, uint32_t size,
                                    void *data, uint32_t *replySize, void *reply);
typedef int32_t (*engine_process_fn)(void *engine, int32_t *offset, int32_t *frames,
                                    int32_t *config, void *buffer);
/* the handle is a pointer to a structure whose first member is the vtable */

static void *g_lib = NULL;
static effect_handle_t g_handle = 0;
static effect_interface_t *g_itf = NULL;
static void *g_engine = NULL;
static engine_command_fn g_command = NULL;
static engine_process_fn g_process = NULL;

static int load_engine(const char *path) {
    g_lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (g_lib == NULL) {
        fprintf(stderr, "offline_render: dlopen(%s) failed: %s\n", path, dlerror());
        return -1;
    }
    return 0;
}

static int create_effect(void) {
    create_fn create = (create_fn)dlsym(g_lib, "EffectCreate");
    if (create == NULL) {
        fprintf(stderr, "offline_render: EffectCreate missing\n");
        return -1;
    }
    effect_uuid_t uuid[16];
    const uint8_t raw[16] = {
        0xf4, 0x17, 0x73, 0xf2, 0x84, 0xc9, 0x4d, 0xe6,
        0x9a, 0x90, 0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2};
    memcpy(uuid, raw, sizeof(raw));
    effect_handle_t out = 0;
    int32_t rc = create(uuid, 0, 0, &out);
    fprintf(stderr, "offline_render: EffectCreate rc=%d out=%p\n", rc, out);
    if (out != 0) {
        g_handle = out;
    } else if ((uintptr_t)rc > 0x1000) {
        /* the vendor build returns the handle in the return value */
        g_handle = (effect_handle_t)(uintptr_t)rc;
    }
    if (g_handle == NULL) {
        fprintf(stderr, "offline_render: no handle\n");
        return -1;
    }
    g_command = (engine_command_fn)dlsym(g_lib, "EffectDSPMainCommand");
    g_process = (engine_process_fn)dlsym(g_lib, "EffectDSPMainProcess");
    if (g_command == NULL || g_process == NULL) {
        fprintf(stderr, "offline_render: EffectDSPMainCommand/Process missing\n");
        return -1;
    }
    g_engine = *(void **)((char *)g_handle + 0x08);
    fprintf(stderr, "offline_render: engine=%p\n", g_engine);
    g_itf = *(effect_interface_t **)g_handle;
    fprintf(stderr, "offline_render: handle=%p vtable=%p\n", g_handle, (void *)g_itf);
    if (g_itf == NULL) {
        fprintf(stderr, "offline_render: no interface vtable\n");
        return -1;
    }
    fprintf(stderr, "offline_render: get=%p set=%p process=%p release=%p desc=%p\n",
            (void *)g_itf->get, (void *)g_itf->set, (void *)g_itf->process,
            (void *)g_itf->release, (void *)g_itf->get_descriptor);
    return 0;
}

static int apply_config(uint32_t frames) {
    effect_config_t config;
    memset(&config, 0, sizeof(config));
    config.inputBuffer.bufferCount = 1;
    config.inputBuffer.frameCount = frames;
    config.inputBuffer.channelMask = 0x3; /* stereo */
    config.inputBuffer.sampleRate = SAMPLE_RATE;
    config.inputBuffer.format = AUDIO_FORMAT_PCM_16BIT;
    config.inputBuffer.dataOffset = 0;
    config.inputBuffer.frameSize = 0;
    config.outputBuffer = config.inputBuffer;
    config.accessMode = EFFECT_BUFFER_ACCESS_RW;
    config.mixRate = SAMPLE_RATE;
    config.channelMixCount = CHANNELS;
    config.channelMixerChannelCount = CHANNELS;
    config.volume = 0x10000; /* unity in the 16.16 scale */

    int32_t reply = 0;
    uint32_t replySize = sizeof(reply);
    int32_t rc = g_command(g_engine, EFFECT_CMD_SET_CONFIG, sizeof(config), &config,
                           &replySize, &reply);
    fprintf(stderr, "offline_render: set config rc=%d\n", rc);
    if (rc != 0) {
        return -1;
    }
    rc = g_command(g_engine, EFFECT_CMD_INIT, sizeof(config), &config, &replySize, &reply);
    fprintf(stderr, "offline_render: init rc=%d\n", rc);
    int32_t enable = 1;
    rc = g_command(g_engine, EFFECT_CMD_ENABLE, sizeof(enable), &enable, &replySize, &reply);
    fprintf(stderr, "offline_render: enable rc=%d\n", rc);
    return rc == 0 ? 0 : -1;
}

static int apply_param(int32_t param_id, const float *values, int32_t count) {
    uint32_t psize = 4;
    uint32_t vsize = (uint32_t)(count * 4);
    uint8_t payload[64];
    memset(payload, 0, sizeof(payload));
    effect_param_t *param = (effect_param_t *)payload;
    param->status = 0;
    param->psize = psize;
    param->vsize = vsize;
    memcpy(payload + sizeof(effect_param_t), &param_id, 4);
    memcpy(payload + sizeof(effect_param_t) + psize, values, vsize);
    int32_t reply = 0;
    uint32_t replySize = sizeof(reply);
    int32_t rc = g_command(g_engine, EFFECT_CMD_SET_PARAM, (uint32_t)sizeof(effect_param_t) + psize + vsize,
                           payload, &replySize, &reply);
    fprintf(stderr, "offline_render: set param %d rc=%d\n", param_id, rc);
    return rc == 0 ? 0 : -1;
}

static double render_rms(const int16_t *input, int16_t *output, uint32_t frames) {
    audio_buffer_t inBuf;
    audio_buffer_t outBuf;
    memset(&inBuf, 0, sizeof(inBuf));
    memset(&outBuf, 0, sizeof(outBuf));
    inBuf.structSize = sizeof(inBuf);
    inBuf.data = (void *)input;
    inBuf.size = frames * CHANNELS * 2;
    outBuf.structSize = sizeof(outBuf);
    outBuf.data = (void *)output;
    outBuf.size = frames * CHANNELS * 2;
    int32_t offset = 0;
    int32_t nframes = (int32_t)frames;
    effect_config_t config;
    memset(&config, 0, sizeof(config));
    int32_t rc = g_process(g_engine, &offset, &nframes, (int32_t *)&config, &inBuf);
    fprintf(stderr, "offline_render: process rc=%d offset=%d nframes=%d\n", rc, offset, nframes);
    if (rc != 0) {
        return -1.0;
    }
    double sum = 0.0;
    uint64_t n = (uint64_t)frames * CHANNELS;
    for (uint64_t i = 0; i < n; i++) {
        double v = output[i];
        sum += v * v;
    }
    return sqrt(sum / (double)n);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: offline_render <paramId> <float...>\n");
        return 3;
    }
    int32_t param_id = (int32_t)strtol(argv[1], NULL, 10);
    float values[16];
    int32_t count = 0;
    for (int i = 2; i < argc && count < 16; i++) {
        values[count++] = strtof(argv[i], NULL);
    }

    const char *paths[] = {
        "/data/local/tmp/jdsp-offline/libjamesdsp.so",
        "/vendor/lib64/soundfx/libjamesdsp.so",
    };
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        if (load_engine(paths[i]) == 0) {
            break;
        }
        g_lib = NULL;
    }
    if (g_lib == NULL) {
        printf("RESULT failed reason=engine-not-loadable\n");
        return 3;
    }
    if (create_effect() != 0) {
        printf("RESULT failed reason=create\n");
        return 3;
    }

    uint32_t frames = 4800;
    int16_t *input = (int16_t *)calloc(frames * CHANNELS, sizeof(int16_t));
    int16_t *output = (int16_t *)calloc(frames * CHANNELS, sizeof(int16_t));
    if (input == NULL || output == NULL) {
        printf("RESULT failed reason=memory\n");
        return 3;
    }
    double amplitude = pow(10.0, TONE_DBFS / 20.0) * 32767.0;
    double phase = 0.0;
    for (uint32_t i = 0; i < frames; i++) {
        int16_t v = (int16_t)(sin(phase) * amplitude);
        input[2 * i] = v;
        input[2 * i + 1] = v;
        phase += 2.0 * M_PI * TONE_HZ / SAMPLE_RATE;
        if (phase > 2.0 * M_PI) {
            phase -= 2.0 * M_PI;
        }
    }

    if (apply_config(frames) != 0) {
        printf("RESULT failed reason=config\n");
        return 3;
    }

    float neutral[16];
    int32_t ncount = count > 3 ? 3 : count;
    for (int32_t i = 0; i < ncount; i++) {
        neutral[i] = 0.0f;
    }
    apply_param(param_id, neutral, ncount);
    double neutralRms = render_rms(input, output, frames);
    if (neutralRms < 0.0) {
        printf("RESULT failed reason=process-neutral\n");
        return 3;
    }

    apply_param(param_id, values, count);
    double testedRms = render_rms(input, output, frames);
    if (testedRms < 0.0) {
        printf("RESULT failed reason=process-tested\n");
        return 3;
    }

    apply_param(param_id, neutral, ncount);
    double restoredRms = render_rms(input, output, frames);
    if (restoredRms < 0.0) {
        printf("RESULT failed reason=process-restored\n");
        return 3;
    }

    printf("NEUTRAL rms=%.6f\n", neutralRms);
    printf("TESTED rms=%.6f\n", testedRms);
    printf("RESTORED rms=%.6f\n", restoredRms);
    if (neutralRms <= 0.0) {
        printf("RESULT failed reason=silent-neutral\n");
        return 3;
    }
    printf("RESULT ok\n");
    return 0;
}

// offline_render.cpp — render PCM through the vendor JamesDSP engine offline.
//
// The engine exports a small, stable ABI that is used here directly (the
// layouts below were verified against the shipped libjamesdsp.so):
//
//   EffectCreate(int, int, void *, void *) -> effect handle
//       the handle is 72 bytes:
//         [0x00] pointer to the generic vtable
//         [0x08] pointer to the engine (EffectDSPMain)
//         [0x18] state generation
//         [0x20] volume (unity = 0x10000)
//         [0x38]/[0x40] scratch buffer
//
//   the generic vtable is { get, set, process, release, get_descriptor } and
//   its command entry point is
//       generic_command(handle, cmdCode, cmdSize, pCmdData, pReplyData, replySize)
//   which forwards to EffectDSPMainCommand(engine, cmdCode, cmdSize, ...).
//
//   the process entry point is
//       generic_process(handle, pOffset, pNumFrames, pConfig, pBuffer)
//   with pBuffer pointing at an audio_buffer_t { structSize, data, offset, size }.
//
// The command numbers and the config layout are the AOSP ones from
// system/media/audio/effects/EffectConfig.h:
//   EFFECT_CMD_INIT = 1, EFFECT_CMD_SET_CONFIG = 6, EFFECT_CMD_SET_PARAM = 5,
//   EFFECT_CMD_ENABLE = 8, EFFECT_CMD_DISABLE = 9
//   EFFECT_TYPE_CONFIG = 1, EFFECT_TYPE_PARAM = 2, EFFECT_TYPE_ENABLE = 4
//
// The result is an RMS/peak pair for a fixed PCM block, so the same block can
// be rendered with neutral and with significant parameters and compared.
// No recording, no playback, no environment noise.
#include <jni.h>
#include <dlfcn.h>
#include <cstdio>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

constexpr int32_t kEffectCmdInit = 1;
constexpr int32_t kEffectCmdSetParam = 5;
constexpr int32_t kEffectCmdSetConfig = 6;
constexpr int32_t kEffectCmdEnable = 8;
constexpr int32_t kEffectBufferAccessRW = 1;
constexpr int32_t kAudioFormatPcmFloat = 4;
constexpr int32_t kEffectTypeConfig = 1;
constexpr int32_t kEffectTypeParam = 2;
constexpr int32_t kEffectTypeEnable = 4;

struct AudioBufferConfig {
    int32_t bufferCount;
    int32_t frameCount;
    int32_t channelMask;
    int32_t sampleRate;
    int32_t format;
    int32_t dataOffset;
    int32_t frameSize;
};

struct AudioBuffer {
    uint32_t structSize;
    void *data;
    uint32_t offset;
    uint32_t size;
};

struct EffectConfig {
    AudioBufferConfig inputBuffer;
    AudioBufferConfig outputBuffer;
    int32_t accessMode;
    int32_t mixRate;
    int32_t channelMixCount;
    int32_t channelMixerChannelCount;
    int32_t channelMixerFlags;
    int32_t volume;
    int32_t scalarCount;
    int32_t *scalars;
    int32_t effectClient;
    int32_t playEnable;
    int32_t auth2_0;
    int32_t threadId;
    int32_t priority;
    int32_t state;
};

struct EffectParameter {
    int32_t cmdCode;
    int32_t dataSize;
};

struct GenericInterface {
    int32_t (*get)(void *, int32_t, int32_t, void *);
    int32_t (*set)(void *, int32_t, int32_t, void *);
    int32_t (*process)(void *, int32_t *, int32_t *, int32_t *, void *);
    int32_t (*release)(void *);
    int32_t (*get_descriptor)(void *, uint32_t, void *);
};

// EffectCreate(effectId, size, pEffect, pFilter) fills pEffect with the handle
// and returns 0 on success (AOSP EffectFactory::createEffect convention).
using EffectCreateFn = int32_t (*)(int32_t, int32_t, void **, void *);
using EffectReleaseFn = int32_t (*)(void *);
using EffectCommandFn = int32_t (*)(void *, uint32_t, uint32_t, void *, uint32_t *, void *);

struct Engine {
    void *library = nullptr;
    void *handle = nullptr;
    EffectReleaseFn release = nullptr;
    EffectCommandFn command = nullptr;
    void *engine = nullptr;  // the EffectDSPMain behind the handle (offset 0x08)
    GenericInterface *iface = nullptr;
    bool ready = false;
};

Engine g_engine;
float g_values[16] = {0.0f, 0.0f, 0.0f};
int32_t g_valueCount = 3;

bool loadEngine() {
    if (g_engine.library != nullptr) {
        return g_engine.ready;
    }
    // app_process runs in a linker namespace without /vendor, so the script
    // copies the engine to a permitted path first.
    static const char *kPaths[] = {
        "/data/local/tmp/jdsp-offline/libjamesdsp.so",
        "/data/local/tmp/jdsp-signal/libjamesdsp.so",
        "/vendor/lib64/soundfx/libjamesdsp.so",
    };
    for (size_t i = 0; i < sizeof(kPaths) / sizeof(kPaths[0]); i++) {
        g_engine.library = dlopen(kPaths[i], RTLD_NOW | RTLD_LOCAL);
        if (g_engine.library != nullptr) {
            break;
        }
    }
    if (g_engine.library == nullptr) {
        return false;
    }
    auto create = reinterpret_cast<EffectCreateFn>(dlsym(g_engine.library, "EffectCreate"));
    auto release = reinterpret_cast<EffectReleaseFn>(dlsym(g_engine.library, "EffectRelease"));
    auto command = reinterpret_cast<EffectCommandFn>(dlsym(g_engine.library, "EffectDSPMainCommand"));
    if (create == nullptr || release == nullptr || command == nullptr) {
        return false;
    }
    g_engine.release = release;
    g_engine.command = command;
    void *handle = nullptr;
    int32_t rc = create(1, 0x48, &handle, nullptr);
    if (rc != 0 || handle == nullptr) {
        fprintf(stderr, "offlinerender: EffectCreate rc=%d handle=%p\n", rc, handle);
        return false;
    }
    g_engine.handle = handle;
    g_engine.engine = *reinterpret_cast<void **>(reinterpret_cast<char *>(g_engine.handle) + 0x08);
    g_engine.iface = *reinterpret_cast<GenericInterface **>(g_engine.handle);
    if (g_engine.iface == nullptr) {
        return false;
    }
    g_engine.ready = true;
    return true;
}

bool configure(int32_t frames, int32_t channels, int32_t rate) {
    EffectConfig config;
    memset(&config, 0, sizeof(config));
    config.inputBuffer.bufferCount = 1;
    config.inputBuffer.frameCount = frames;
    config.inputBuffer.channelMask = channels == 2 ? 0x3 : 0x4;
    config.inputBuffer.sampleRate = rate;
    config.inputBuffer.format = kAudioFormatPcmFloat;
    config.inputBuffer.dataOffset = 0;
    config.inputBuffer.frameSize = 0;
    config.outputBuffer = config.inputBuffer;
    config.accessMode = kEffectBufferAccessRW;
    config.mixRate = rate;
    config.channelMixCount = channels;
    config.channelMixerChannelCount = channels;
    config.volume = 0x10000;  // unity gain in the AOSP 16.16 scale
    // EFFECT_CMD_SET_CONFIG (6) then EFFECT_CMD_INIT (1)
    int32_t reply = 0;
    uint32_t replySize = sizeof(reply);
    g_engine.command(g_engine.engine, kEffectCmdSetConfig, sizeof(EffectConfig), &config,
                    &replySize, &reply);
    g_engine.command(g_engine.engine, kEffectCmdInit, sizeof(EffectConfig), &config,
                    &replySize, &reply);
    return true;
}

bool setParam(int32_t paramId, const float *values, int32_t count) {
    std::vector<uint8_t> payload(sizeof(EffectParameter) + sizeof(float) * count);
    EffectParameter header;
    header.cmdCode = paramId;
    header.dataSize = (int32_t)(sizeof(float) * count);
    memcpy(payload.data(), &header, sizeof(header));
    memcpy(payload.data() + sizeof(header), values, sizeof(float) * count);
    int32_t reply = 0;
    uint32_t replySize = sizeof(reply);
    // EFFECT_CMD_SET_PARAM (5): the payload starts with the parameter id and
    // the data length, exactly like the framework's setParameter().
    return g_engine.command(g_engine.engine, kEffectCmdSetParam, (uint32_t)payload.size(),
                            payload.data(), &replySize, &reply) == 0;
}

bool render(float *interleaved, int32_t frames, int32_t channels) {
    AudioBuffer buffer;
    buffer.structSize = sizeof(buffer);
    buffer.data = interleaved;
    buffer.offset = 0;
    buffer.size = (uint32_t)(frames * channels * sizeof(float));
    int32_t offset = 0;
    int32_t nframes = frames;
    int32_t config[4] = {0, 0, 0, 0};
    int32_t rc = g_engine.iface->process(g_engine.handle, &offset, &nframes, config, &buffer);
    if (rc != 0) {
        fprintf(stderr, "offlinerender: process rc=%d nframes=%d offset=%d\n",
                rc, nframes, offset);
    }
    return rc == 0;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_jdsp_offline_OfflineRender_nativeSetValues(JNIEnv *env, jclass,
                                                    jfloatArray values) {
    jsize n = env->GetArrayLength(values);
    if (n > 16) {
        n = 16;
    }
    env->GetFloatArrayRegion(values, 0, n, g_values);
    g_valueCount = n;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jdsp_offline_OfflineRender_nativeRender(JNIEnv *env, jclass,
                                                 jint paramId, jfloatArray input,
                                                 jint frames, jint channels,
                                                 jfloatArray output) {
    if (!loadEngine()) {
        fprintf(stderr, "offlinerender: loadEngine failed\n");
        return nullptr;
    }
    fprintf(stderr, "offlinerender: engine loaded handle=%p iface=%p\n",
            g_engine.handle, (void *)g_engine.iface);
    jfloat *in = env->GetFloatArrayElements(input, nullptr);
    jfloat *out = env->GetFloatArrayElements(output, nullptr);
    if (in == nullptr || out == nullptr) {
        if (in != nullptr) env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
        if (out != nullptr) env->ReleaseFloatArrayElements(output, out, JNI_ABORT);
        return nullptr;
    }
    bool ok = configure(frames, channels, 48000);
    if (!ok) {
        fprintf(stderr, "offlinerender: configure failed\n");
    }
    if (ok) {
        float values[16];
        int32_t count = g_valueCount > 0 ? g_valueCount : 3;
        for (int32_t i = 0; i < count; i++) {
            values[i] = g_values[i];
        }
        int32_t prc = setParam(paramId, values, count);
        fprintf(stderr, "offlinerender: setParam rc=%d\n", prc);
        memcpy(out, in, (size_t)frames * channels * sizeof(float));
        ok = render(out, frames, channels);
        fprintf(stderr, "offlinerender: render ok=%d\n", ok ? 1 : 0);
    }
    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, out, 0);
    if (!ok) {
        return nullptr;
    }
    jfloatArray rms = env->NewFloatArray(2);
    if (rms != nullptr) {
        double sum = 0.0;
        double peak = 0.0;
        int64_t n = (int64_t)frames * channels;
        for (int64_t i = 0; i < n; i++) {
            float v = out[i];
            sum += (double)v * v;
            double a = v < 0 ? -(double)v : (double)v;
            if (a > peak) {
                peak = a;
            }
        }
        float values[2];
        values[0] = (float)(n > 0 ? sqrt(sum / (double)n) : 0.0);
        values[1] = (float)peak;
        env->SetFloatArrayRegion(rms, 0, 2, values);
    }
    return rms;
}

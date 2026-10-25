// aaudio_tone.c — AAudio playback probe (LOW_LATENCY / MMAP path vs default path)
// usage: aaudio_tone <secs> <mode: low|normal>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <aaudio/AAudio.h>
#include <unistd.h>

static const double SR = 48000.0;

static aaudio_data_callback_result_t dataCb(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    static double phase = 0.0;
    float *out = (float *) audioData;
    for (int i = 0; i < numFrames; i++) {
        float v = (float) (sin(phase) * 0.25);
        out[2 * i] = v;
        out[2 * i + 1] = v;
        phase += 2.0 * M_PI * 440.0 / SR;
        if (phase > 2.0 * M_PI) phase -= 2.0 * M_PI;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

int main(int argc, char **argv) {
    int secs = argc > 1 ? atoi(argv[1]) : 8;
    int low = (argc > 2 && strcmp(argv[2], "low") == 0);
    int sessionId = argc > 3 ? atoi(argv[3]) : 0;
    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t rc = AAudio_createStreamBuilder(&builder);
    if (rc != AAUDIO_OK) { printf("BUILDER_FAIL %s\n", AAudio_convertResultToText(rc)); return 2; }
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setPerformanceMode(builder,
            low ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setDataCallback(builder, dataCb, NULL);
    if (sessionId > 0) AAudioStreamBuilder_setSessionId(builder, (int32_t) sessionId);
    AAudioStream *stream = NULL;
    rc = AAudioStreamBuilder_openStream(builder, &stream);
    if (rc != AAUDIO_OK) {
        printf("OPEN_FAIL %s\n", AAudio_convertResultToText(rc));
        AAudioStreamBuilder_delete(builder);
        return 3;
    }
    printf("SESSION=%d perf=%d shmode=%d framesPerBurst=%d\n",
           AAudioStream_getSessionId(stream),
           AAudioStream_getPerformanceMode(stream),
           AAudioStream_getSharingMode(stream),
           AAudioStream_getFramesPerBurst(stream));
    rc = AAudioStream_requestStart(stream);
    if (rc != AAUDIO_OK) { printf("START_FAIL %s\n", AAudio_convertResultToText(rc)); return 4; }
    int64_t f0 = AAudioStream_getFramesWritten(stream);
    int64_t p0 = AAudioStream_getFramesRead(stream);
    fflush(stdout);
    sleep(secs);
    int64_t f1 = AAudioStream_getFramesWritten(stream);
    int64_t p1 = AAudioStream_getFramesRead(stream);
    int32_t underruns = AAudioStream_getXRunCount(stream);
    printf("AFTER frames=%lld..%lld read=%lld..%lld xruns=%d %s\n",
           (long long) f0, (long long) f1, (long long) p0, (long long) p1,
           underruns, (f1 > f0 && p1 > p0) ? "OK" : "STALL");
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    AAudioStreamBuilder_delete(builder);
    return 0;
}

/*
 * test_tap.c - off device tests for the capture tap (jdsp_tap.c, patch 0007).
 *
 * Covers the contract the engine relies on:
 *   * the format/mode -> WAV tag mapping,
 *   * bit exact capture and read back for every engine format mode,
 *   * the ring overrun policy (drop the oldest, count exactly),
 *   * limit_frames stops the capture,
 *   * the WAV writer produces a readable file whose payload is byte identical
 *     to what was fed in,
 *   * a producer thread + a reader thread never lose or tear a frame,
 *   * a NULL tap / disabled lane / unknown format is a no-op, not a crash.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <stdint.h>

#include "jdsp_tap.h"

static int g_fail;
#define CHECK(cond) do { if (!(cond)) { \
	fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); g_fail++; } } while (0)
#define CHECK_EQ(a, b) do { long long _a = (long long)(a), _b = (long long)(b); if (_a != _b) { \
	fprintf(stderr, "FAIL %s:%d: %s=%lld != %s=%lld\n", __FILE__, __LINE__, #a, _a, #b, _b); g_fail++; } } while (0)

static jdsp_tap_config_t cfg_basic(uint32_t lanes, uint32_t frames, const char *path, uint32_t flags, uint32_t limit)
{
	jdsp_tap_config_t c;
	memset(&c, 0, sizeof(c));
	c.magic = JDSP_TAP_MAGIC;
	c.lanes = lanes;
	c.frames = frames;
	c.limit_frames = limit;
	c.flags = flags;
	if (path != NULL)
		snprintf(c.path, sizeof(c.path), "%s", path);
	return c;
}

/* deterministic test pattern: frame i has a per sample value derived from i */
static void fill_i16(int16_t *p, size_t frames, unsigned seed)
{
	for (size_t i = 0; i < frames; i++)
	{
		p[2 * i] = (int16_t)((int)((i * 7u + seed) % 65536u) - 32768);
		p[2 * i + 1] = (int16_t)((int)((i * 13u + seed + 1u) % 65536u) - 32768);
	}
}

static void test_formats(void)
{
	CHECK_EQ(jdsp_tap_frame_bytes(0), 4);
	CHECK_EQ(jdsp_tap_frame_bytes(1), 8);
	CHECK_EQ(jdsp_tap_frame_bytes(2), 8);
	CHECK_EQ(jdsp_tap_frame_bytes(3), 6);
	CHECK_EQ(jdsp_tap_frame_bytes(4), 8);
	CHECK_EQ(jdsp_tap_frame_bytes(9), 0);
}

static void test_capture_readback(void)
{
	int modes[5] = { 0, 1, 2, 3, 4 };
	for (int mi = 0; mi < 5; mi++)
	{
		int mode = modes[mi];
		size_t fb = jdsp_tap_frame_bytes(mode);
		jdsp_tap_config_t c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 4096, NULL, 0, 0);
		struct jdsp_tap *t = jdsp_tap_create(&c, mode, 48000);
		CHECK(t != NULL);
		if (t == NULL)
			continue;
		size_t frames = 1000;
		uint8_t *src = (uint8_t *)malloc(frames * fb);
		uint8_t *dst = (uint8_t *)malloc(frames * fb);
		for (size_t i = 0; i < frames * fb; i++)
			src[i] = (uint8_t)(i * 31u + mi);
		CHECK_EQ(jdsp_tap_capture(t, JDSP_TAP_LANE_IN, src, frames), frames);
		CHECK_EQ(jdsp_tap_capture(t, JDSP_TAP_LANE_OUT, src, frames), 0u); /* lane off */
		size_t got = 0;
		CHECK_EQ(jdsp_tap_read(t, JDSP_TAP_LANE_IN, dst, frames, &got), 0);
		CHECK_EQ(got, frames);
		CHECK(memcmp(src, dst, frames * fb) == 0);
		jdsp_tap_status_t st;
		CHECK_EQ(jdsp_tap_status(t, &st), 0);
		CHECK_EQ(st.magic, JDSP_TAP_MAGIC);
		CHECK_EQ(st.captured[JDSP_TAP_LANE_IN], frames);
		CHECK_EQ(st.read_by_caller[JDSP_TAP_LANE_IN], frames);
		CHECK_EQ(st.frame_bytes, fb);
		CHECK_EQ(st.sample_rate, 48000u);
		free(src);
		free(dst);
		jdsp_tap_destroy(t);
	}
}

static void test_overrun(void)
{
	/* ring of 1024 frames (clamped up to the minimum), feed 3000 */
	jdsp_tap_config_t c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 512, NULL, 0, 0);
	struct jdsp_tap *t = jdsp_tap_create(&c, 0, 48000);
	CHECK(t != NULL);
	if (t == NULL)
		return;
	jdsp_tap_status_t st;
	CHECK_EQ(jdsp_tap_status(t, &st), 0);
	uint32_t cap = st.ring_frames;
	CHECK_EQ(cap, JDSP_TAP_MIN_FRAMES);

	size_t frames = 3000;
	int16_t *src = (int16_t *)malloc(frames * 2 * sizeof(int16_t));
	int16_t *dst = (int16_t *)malloc(cap * 2 * sizeof(int16_t));
	fill_i16(src, frames, 11);
	/* a block larger than the ring keeps its tail: captured == cap, the rest is
	 * counted as dropped (and as one overrun event) */
	CHECK_EQ(jdsp_tap_capture(t, JDSP_TAP_LANE_IN, src, frames), cap);
	CHECK_EQ(jdsp_tap_status(t, &st), 0);
	CHECK_EQ(st.captured[JDSP_TAP_LANE_IN], cap);
	CHECK_EQ(st.dropped[JDSP_TAP_LANE_IN], frames - cap);
	CHECK(st.overruns >= 1u);
	size_t got = 0;
	CHECK_EQ(jdsp_tap_read(t, JDSP_TAP_LANE_IN, dst, cap, &got), 0);
	CHECK_EQ(got, cap);
	/* the ring must hold the newest cap frames */
	CHECK(memcmp(dst, src + 2 * (frames - cap), cap * 2 * sizeof(int16_t)) == 0);
	free(src);
	free(dst);
	jdsp_tap_destroy(t);

	/* the incremental path: many blocks that add up to more than the ring */
	c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 512, NULL, 0, 0);
	t = jdsp_tap_create(&c, 0, 48000);
	CHECK(t != NULL);
	if (t == NULL)
		return;
	int16_t blk[512 * 2];
	fill_i16(blk, 512, 5);
	for (int i = 0; i < 6; i++)
		CHECK_EQ(jdsp_tap_capture(t, JDSP_TAP_LANE_IN, blk, 512), 512u);
	CHECK_EQ(jdsp_tap_status(t, &st), 0);
	CHECK_EQ(st.captured[JDSP_TAP_LANE_IN], 6u * 512u);
	CHECK_EQ(st.dropped[JDSP_TAP_LANE_IN], 6u * 512u - 1024u);
	CHECK_EQ(st.overruns, 4u);   /* blocks 3..6 each overflow once */
	jdsp_tap_destroy(t);
}

static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static uint16_t rd16(const uint8_t *p) { return (uint16_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8)); }

static void test_wav_file(void)
{
	const char *dir = "/tmp/jdsp-tap-test";
	char cmd[256];
	snprintf(cmd, sizeof(cmd), "rm -rf %s && mkdir -p %s", dir, dir);
	CHECK(system(cmd) == 0);

	/* The whole capture fits in the ring, so no frame can be lost while the
	 * writer thread is still starting up - the payload check below is exact. */
	jdsp_tap_config_t c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN) | JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_OUT),
		16384, dir, 1u, 8192u);
	struct jdsp_tap *t = jdsp_tap_create(&c, 1 /* float */, 48000);
	CHECK(t != NULL);
	if (t == NULL)
		return;

	size_t total = 8192, block = 1024;
	float *src = (float *)malloc(block * 2 * sizeof(float));
	for (size_t b = 0; b + block <= total; b += block)
	{
		for (size_t i = 0; i < block; i++)
		{
			src[2 * i] = (float)((double)(b + i) / 48000.0);
			src[2 * i + 1] = -src[2 * i];
		}
		jdsp_tap_capture(t, JDSP_TAP_LANE_IN, src, block);
		jdsp_tap_capture(t, JDSP_TAP_LANE_OUT, src, block);
	}
	free(src);
	jdsp_tap_status_t st;
	CHECK_EQ(jdsp_tap_status(t, &st), 0);
	CHECK_EQ(st.captured[JDSP_TAP_LANE_IN], total);
	CHECK_EQ(st.captured[JDSP_TAP_LANE_OUT], total);
	jdsp_tap_destroy(t); /* joins the writer, patches the headers */

	snprintf(cmd, sizeof(cmd), "ls %s/*.wav | wc -l", dir);
	FILE *p = popen(cmd, "r");
	int files = 0;
	if (p != NULL)
	{
		if (fscanf(p, "%d", &files) != 1)
			files = 0;
		pclose(p);
	}
	CHECK_EQ(files, 2);

	snprintf(cmd, sizeof(cmd), "ls %s/*in*.wav", dir);
	p = popen(cmd, "r");
	char path[512];
	path[0] = '\0';
	if (p != NULL)
	{
		if (fgets(path, sizeof(path), p) == NULL)
			path[0] = '\0';
		pclose(p);
	}
	path[strcspn(path, "\n")] = '\0';
	CHECK(path[0] != '\0');
	if (path[0] == '\0')
		return;

	FILE *f = fopen(path, "rb");
	CHECK(f != NULL);
	if (f == NULL)
		return;
	uint8_t h[44];
	CHECK_EQ(fread(h, 1, 44, f), 44);
	CHECK(memcmp(h, "RIFF", 4) == 0);
	CHECK(memcmp(h + 8, "WAVE", 4) == 0);
	CHECK_EQ(rd32(h + 16), 16u);         /* fmt chunk size */
	CHECK_EQ(rd16(h + 20), 3u);          /* IEEE float */
	CHECK_EQ(rd16(h + 22), 2u);          /* stereo */
	CHECK_EQ(rd32(h + 24), 48000u);      /* sample rate */
	CHECK_EQ(rd32(h + 28), 48000u * 8u); /* byte rate */
	CHECK_EQ(rd16(h + 32), 8u);          /* block align */
	CHECK_EQ(rd16(h + 34), 32u);         /* bits */
	CHECK(memcmp(h + 36, "data", 4) == 0);
	CHECK_EQ(rd32(h + 40), (uint32_t)(total * 8u));
	CHECK_EQ(rd32(h + 4), (uint32_t)(total * 8u + 36u));
	/* payload: must be exactly the fed ramp, frame by frame */
	size_t n = (size_t)rd32(h + 40);
	uint8_t *payload = (uint8_t *)malloc(n);
	CHECK(payload != NULL);
	if (payload != NULL)
	{
		CHECK_EQ(fread(payload, 1, n, f), n);
		int ok = 1;
		for (size_t i = 0; i < total && ok; i++)
		{
			float a, b;
			memcpy(&a, payload + i * 8u, 4);
			memcpy(&b, payload + i * 8u + 4, 4);
			if (a != (float)((double)i / 48000.0) || b != -a)
				ok = 0;
		}
		CHECK(ok);
		free(payload);
	}
	fclose(f);
}

struct prod_arg {
	struct jdsp_tap *t;
	uint32_t frames;
	int mode;
	size_t fb;
	int done;
};

static void *producer(void *arg)
{
	struct prod_arg *a = (struct prod_arg *)arg;
	const size_t block = 240;
	uint8_t *buf = (uint8_t *)malloc(block * a->fb);
	for (uint32_t done = 0; done < a->frames; done += block)
	{
		size_t n = block;
		if ((size_t)done + n > a->frames)
			n = a->frames - done;
		for (size_t i = 0; i < n * a->fb; i++)
			buf[i] = (uint8_t)((done * 7u + i) & 0xffu);
		jdsp_tap_capture(a->t, JDSP_TAP_LANE_IN, buf, n);
		if ((done % (block * 40u)) == 0u)
			usleep(100); /* let the consumer fall behind sometimes */
	}
	free(buf);
	a->done = 1;
	return NULL;
}

static void test_concurrent(void)
{
	jdsp_tap_config_t c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 262144, NULL, 0, 0);
	struct jdsp_tap *t = jdsp_tap_create(&c, 1, 48000);
	CHECK(t != NULL);
	if (t == NULL)
		return;
	struct prod_arg a;
	memset(&a, 0, sizeof(a));
	a.t = t;
	a.frames = 48000u * 4u;
	a.mode = 1;
	a.fb = jdsp_tap_frame_bytes(1);
	pthread_t th;
	CHECK_EQ(pthread_create(&th, NULL, producer, &a), 0);
	size_t fb = a.fb;
	uint8_t *buf = (uint8_t *)malloc(1024 * fb);
	uint64_t read_total = 0;
	int mismatch = 0;
	while (!a.done || read_total < a.frames)
	{
		size_t got = 0;
		if (jdsp_tap_read(t, JDSP_TAP_LANE_IN, buf, 1024, &got) != 0)
			break;
		for (size_t i = 0; i < got; i++)
		{
			uint64_t frame = read_total + i;
			for (size_t b = 0; b < fb; b++)
			{
				uint8_t want = (uint8_t)(((frame / 240u) * 240u * 7u + (frame % 240u) * fb + b) & 0xffu);
				if (buf[i * fb + b] != want)
					mismatch = 1;
			}
		}
		read_total += got;
		usleep(500);
	}
	pthread_join(th, NULL);
	CHECK_EQ(mismatch, 0);
	jdsp_tap_status_t st;
	CHECK_EQ(jdsp_tap_status(t, &st), 0);
	CHECK_EQ(st.dropped[JDSP_TAP_LANE_IN], 0u);   /* 5.46 s ring, 4 s of audio */
	CHECK_EQ(st.captured[JDSP_TAP_LANE_IN], a.frames);
	CHECK_EQ(read_total, a.frames);
	free(buf);
	jdsp_tap_destroy(t);
}

static void test_limit_and_nulls(void)
{
	/* NULL tap must be harmless */
	CHECK_EQ(jdsp_tap_capture(NULL, JDSP_TAP_LANE_IN, "x", 1), 0u);
	CHECK_EQ(jdsp_tap_read(NULL, 0, NULL, 0, NULL), -1);
	jdsp_tap_destroy(NULL);

	/* unknown format: the lane stays off, capture is a no-op */
	jdsp_tap_config_t c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 4096, NULL, 0, 0);
	struct jdsp_tap *t = jdsp_tap_create(&c, -1, 0);
	CHECK(t == NULL || jdsp_tap_capture(t, JDSP_TAP_LANE_IN, "abcd", 1) == 0u);
	jdsp_tap_destroy(t);

	/* limit_frames stops the capture */
	c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 4096, NULL, 0, 480u);
	t = jdsp_tap_create(&c, 0, 48000);
	CHECK(t != NULL);
	if (t == NULL)
		return;
	int16_t block[480 * 2];
	fill_i16(block, 480, 3);
	for (int i = 0; i < 5; i++)
		jdsp_tap_capture(t, JDSP_TAP_LANE_IN, block, 480);
	jdsp_tap_status_t st;
	CHECK_EQ(jdsp_tap_status(t, &st), 0);
	CHECK_EQ(st.captured[JDSP_TAP_LANE_IN], 480u);   /* stops exactly at the limit */
	jdsp_tap_destroy(t);
}

static void test_configure(void)
{
	struct jdsp_tap *slot = NULL;
	jdsp_tap_config_t c = cfg_basic(JDSP_TAP_LANE_BIT(JDSP_TAP_LANE_IN), 4096, NULL, 0, 0);
	CHECK_EQ(jdsp_tap_configure(&slot, &c, sizeof(c), 1, 48000), 0);
	CHECK(slot != NULL);
	/* status must be readable through the getter */
	jdsp_tap_status_t st;
	jdsp_tap_status(slot, &st);
	CHECK_EQ(st.frame_bytes, 8u);
	/* a second configure replaces it */
	CHECK_EQ(jdsp_tap_configure(&slot, &c, sizeof(c), 0, 44100), 0);
	jdsp_tap_status(slot, &st);
	CHECK_EQ(st.frame_bytes, 4u);
	CHECK_EQ(st.sample_rate, 44100u);
	/* lanes == 0 tears the tap down */
	jdsp_tap_config_t off = c;
	off.lanes = 0;
	CHECK_EQ(jdsp_tap_configure(&slot, &off, sizeof(off), 0, 44100), 0);
	CHECK(slot == NULL);
	/* malformed configs are rejected */
	jdsp_tap_config_t bad = c;
	bad.magic = 0;
	CHECK_EQ(jdsp_tap_configure(&slot, &bad, sizeof(bad), 0, 44100), -1);
	CHECK_EQ(jdsp_tap_configure(&slot, &c, 4, 0, 44100), -1);
}

int main(void)
{
	printf("== test_tap\n");
	test_formats();
	test_capture_readback();
	test_overrun();
	test_wav_file();
	test_concurrent();
	test_limit_and_nulls();
	test_configure();
	if (g_fail != 0)
	{
		printf("FAILED %d checks\n", g_fail);
		return 1;
	}
	printf("PASS all capture tap checks\n");
	return 0;
}

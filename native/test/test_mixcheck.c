/*
 * test_mixcheck.c - off device tests for the mix fidelity check (patch 0008).
 *
 * The check exists to catch the 0005 regression family (a replace style write
 * over the output thread's shared mix) without a vendor PCM dump, so the test
 * encodes the two real failure shapes plus the saturation accounting:
 *
 *   1. correct ACCUMULATE       -> mismatch count 0
 *   2. replace bug              -> mismatch count == every sample of the mix
 *   3. silent holder (render 0) -> 0 only when the mix was preserved
 *   4. int16/int32 saturation   -> saturated add is faithful, un-saturated is not
 *   5. arm/disarm/counters      -> runs always grow, broken only on damage,
 *                                  reset clears, status reports both
 *   6. the unarmed default      -> armed() is 0 before anything is configured
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jdsp_mixcheck.h"

static int g_fail;

#define CHECK(cond) do { if (!(cond)) { \
	fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); g_fail++; } } while (0)
#define CHECK_EQ(a, b) do { long long _a = (long long)(a), _b = (long long)(b); \
	if (_a != _b) { fprintf(stderr, "FAIL %s:%d: %s=%lld != %s=%lld\n", \
	__FILE__, __LINE__, #a, _a, #b, _b); g_fail++; } } while (0)

int main(void)
{
	const size_t frames = 64, ch = 2, n = frames * ch;
	float *before = (float *)calloc(n, sizeof(float));
	float *rendered = (float *)calloc(n, sizeof(float));
	float *after = (float *)calloc(n, sizeof(float));
	int16_t *b16 = (int16_t *)calloc(n, sizeof(int16_t));
	int16_t *r16 = (int16_t *)calloc(n, sizeof(int16_t));
	int16_t *o16 = (int16_t *)calloc(n, sizeof(int16_t));
	int32_t *b32 = (int32_t *)calloc(n, sizeof(int32_t));
	int32_t *r32 = (int32_t *)calloc(n, sizeof(int32_t));
	int32_t *o32 = (int32_t *)calloc(n, sizeof(int32_t));
	uint32_t armed = 0u;
	uint64_t runs = 0ull, broken = 0ull, lastBad = 0ull, lastFrames = 0ull;
	size_t i;

	printf("== test_mixcheck (0008 mix fidelity check)\n");

	/* 6. default state: nothing armed until somebody asks for it. */
	CHECK_EQ(jdsp_mixcheck_armed(), 0);

	/* 1. the correct accumulate: out = before + rendered. */
	for (i = 0; i < n; i++)
	{
		before[i] = 0.25f;   /* the other sessions' contribution            */
		rendered[i] = 0.5f;  /* what this instance rendered                 */
		after[i] = before[i] + rendered[i];
	}
	CHECK_EQ(jdsp_mixcheck_mismatch_count(after, before, rendered, frames,
		JDSP_MIXCHECK_FLOAT, 2), 0);

	/* 2. the 0005 regression: the engine stored its own render over the mix. */
	for (i = 0; i < n; i++)
		after[i] = rendered[i];
	CHECK_EQ(jdsp_mixcheck_mismatch_count(after, before, rendered, frames,
		JDSP_MIXCHECK_FLOAT, 2), (long long)n);

	/* 3. silent holder: rendered is digital silence, the mix must survive. */
	for (i = 0; i < n; i++)
		rendered[i] = 0.0f;
	for (i = 0; i < n; i++)
		after[i] = 0.0f;                       /* the bug: thread goes mute */
	CHECK_EQ(jdsp_mixcheck_mismatch_count(after, before, rendered, frames,
		JDSP_MIXCHECK_FLOAT, 2), (long long)n);
	for (i = 0; i < n; i++)
		after[i] = before[i];                  /* correct: adding zero      */
	CHECK_EQ(jdsp_mixcheck_mismatch_count(after, before, rendered, frames,
		JDSP_MIXCHECK_FLOAT, 2), 0);

	/* 4a. int16 saturation is faithful, an un-saturated write is not. */
	for (i = 0; i < n; i++)
	{
		b16[i] = 32000;
		r16[i] = 1000;
		o16[i] = 32767;
	}
	CHECK_EQ(jdsp_mixcheck_mismatch_count(o16, b16, r16, frames,
		JDSP_MIXCHECK_INT16, 2), 0);
	o16[7] = 32000;
	CHECK_EQ(jdsp_mixcheck_mismatch_count(o16, b16, r16, frames,
		JDSP_MIXCHECK_INT16, 2), 1);

	/* 4b. int32 saturation: INT32_MAX is not representable in float32, so this
	 * also pins the integer-domain saturation of the comparison. */
	for (i = 0; i < n; i++)
	{
		b32[i] = 2000000000;
		r32[i] = 2000000000;
		o32[i] = 2147483647;
	}
	CHECK_EQ(jdsp_mixcheck_mismatch_count(o32, b32, r32, frames,
		JDSP_MIXCHECK_INT32, 2), 0);
	for (i = 0; i < n; i++)
	{
		b32[i] = 1000;
		r32[i] = -400;
		o32[i] = 600;
	}
	CHECK_EQ(jdsp_mixcheck_mismatch_count(o32, b32, r32, frames,
		JDSP_MIXCHECK_INT32, 2), 0);
	o32[3] = 601;
	CHECK_EQ(jdsp_mixcheck_mismatch_count(o32, b32, r32, frames,
		JDSP_MIXCHECK_INT32, 2), 1);

	/* 5. counters: runs always grow, broken only on damage, reset clears. */
	jdsp_mixcheck_reset();
	jdsp_mixcheck_configure(1u);
	CHECK_EQ(jdsp_mixcheck_armed(), 1);
	CHECK_EQ(jdsp_mixcheck_configure(1u), 0);
	jdsp_mixcheck_account(frames, 0);   /* a clean buffer  */
	jdsp_mixcheck_account(frames, 3);   /* a damaged one   */
	jdsp_mixcheck_account(frames, 0);   /* another clean   */
	jdsp_mixcheck_status(&armed, &runs, &broken, &lastBad, &lastFrames);
	CHECK_EQ(armed, 1);
	CHECK_EQ(runs, 3);
	CHECK_EQ(broken, 1);
	CHECK_EQ(lastBad, 3);
	CHECK_EQ(lastFrames, (long long)frames);
	jdsp_mixcheck_reset();
	jdsp_mixcheck_status(&armed, &runs, &broken, &lastBad, &lastFrames);
	CHECK_EQ(runs, 0);
	CHECK_EQ(broken, 0);
	jdsp_mixcheck_configure(0u);
	CHECK_EQ(jdsp_mixcheck_armed(), 0);

	/* NULL buffers never claim fidelity and must not crash. */
	CHECK_EQ(jdsp_mixcheck_mismatch_count(NULL, before, rendered, frames,
		JDSP_MIXCHECK_FLOAT, 2), (long long)n);

	free(before); free(rendered); free(after);
	free(b16); free(r16); free(o16);
	free(b32); free(r32); free(o32);

	if (g_fail == 0)
	{
		printf("test_mixcheck: all passed\n");
		return 0;
	}
	fprintf(stderr, "test_mixcheck: %d failure(s)\n", g_fail);
	return 1;
}

/*
 * test_accum.c - off-device unit test for the 0005 ACCESS_ACCUMULATE helper
 * (jdsp_accum.c).  Exercises every supported render mode and the fallbacks.
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jdsp_accum.h"

static int failures;

static void check(int cond, const char *what)
{
	if (!cond)
	{
		printf("FAIL: %s\n", what);
		failures++;
	}
}

static int nearly(float a, float b)
{
	return fabsf(a - b) < 1e-4f;
}

int main(void)
{
	/* int16: plain add and both saturation rails */
	{
		static const int16_t dst0[4] = { 100, -100, 32000, -32000 };
		static const int16_t src[4] = { 23, -23, 1000, -1000 };
		int16_t dst[4];
		memcpy(dst, dst0, sizeof(dst));
		check(jdsp_accum_add(dst, src, 2, JDSP_ACCUM_MODE_INT16) == 0, "int16 rc");
		check(dst[0] == 123 && dst[1] == -123, "int16 plain add");
		check(dst[2] == 32767 && dst[3] == -32768, "int16 saturation");
	}

	/* float: plain add, no saturation */
	{
		float dst[4] = { 0.25f, -0.25f, 1.5f, -1.5f };
		static const float src[4] = { 0.5f, -0.5f, 0.75f, -0.75f };
		check(jdsp_accum_add(dst, src, 2, JDSP_ACCUM_MODE_FLOAT) == 0, "float rc");
		check(nearly(dst[0], 0.75f) && nearly(dst[1], -0.75f), "float plain add");
		check(nearly(dst[2], 2.25f) && nearly(dst[3], -2.25f), "float no clamp");
	}

	/* int32 and int8_24: 64 bit add with clamping (same width, 8_24 payload) */
	{
		static const int32_t dst0[4] = { 1000, -1000, 2000000000, -2000000000 };
		static const int32_t src[4] = { 7, -7, 1000000000, -1000000000 };
		int32_t dst[4];
		memcpy(dst, dst0, sizeof(dst));
		check(jdsp_accum_add(dst, src, 2, JDSP_ACCUM_MODE_INT32) == 0, "int32 rc");
		check(dst[0] == 1007 && dst[1] == -1007, "int32 plain add");
		check(dst[2] == INT32_MAX && dst[3] == INT32_MIN, "int32 clamp");

		memcpy(dst, dst0, sizeof(dst));
		check(jdsp_accum_add(dst, src, 2, JDSP_ACCUM_MODE_INT8_24) == 0, "int8_24 rc");
		check(dst[0] == 1007 && dst[2] == INT32_MAX, "int8_24 same width");
	}

	/* packed 24 bit: unsupported, caller must keep replace behaviour */
	{
		int16_t dst[2] = { 1, 2 };
		static const int16_t src[2] = { 1, 2 };
		check(jdsp_accum_add(dst, src, 1, JDSP_ACCUM_MODE_PACKED24) == -1,
			"packed24 rejected");
		check(dst[0] == 1 && dst[1] == 2, "packed24 dst untouched");
	}

	/* degenerate arguments */
	{
		int16_t dst[2] = { 1, 2 };
		check(jdsp_accum_add(NULL, dst, 1, JDSP_ACCUM_MODE_INT16) == -1, "null dst");
		check(jdsp_accum_add(dst, NULL, 1, JDSP_ACCUM_MODE_INT16) == -1, "null src");
		check(jdsp_accum_add(dst, dst, 0, JDSP_ACCUM_MODE_INT16) == -1, "zero frames");
	}

	if (failures == 0)
		printf("test_accum: all ok\n");
	return failures == 0 ? 0 : 1;
}

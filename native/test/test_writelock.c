/*
 * Off-device unit test for jdsp_writelock.c (the caller write protection of
 * libjamesdsp.so).  Build and run with test/run_tests.sh:
 *
 *   cc -std=gnu99 -Wall -Wextra -I<src> test/test_writelock.c <src>/jdsp_writelock.c \
 *       -o build/test_writelock -lpthread
 *
 * The test drives the real gate with effect_param_t shaped buffers exactly like
 * AudioEffect::setParameter builds them (status, psize, vsize, cmd, value), so a
 * green run means the lock / unlock / reject semantics the daemon relies on hold.
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "jdsp_writelock.h"

/* ------------------------------------------------------------------------- */
/* helpers                                                                   */
/* ------------------------------------------------------------------------- */

static int g_failures;
#define CHECK(cond)                                                           \
    do {                                                                      \
        if (!(cond)) {                                                        \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);   \
            g_failures++;                                                     \
        }                                                                     \
    } while (0)

#define CHECK_EQ(a, b)                                                        \
    do {                                                                      \
        long long va = (long long)(a), vb = (long long)(b);                   \
        if (va != vb) {                                                       \
            fprintf(stderr, "FAIL %s:%d: %s=%lld != %s=%lld\n", __FILE__,     \
                    __LINE__, #a, va, #b, vb);                                \
            g_failures++;                                                     \
        }                                                                     \
    } while (0)

#define WL_SET_PARAM 5u /* JDSP_STATE_CMD_SET_PARAM, mirrored by the module */
#define WL_ENABLE    3u

static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

/* Build an effect_param_t shaped buffer: status, psize, vsize, cmd, value.
 * Total size = 16 + vsize, exactly what the HAL hands to the effect command. */
static uint32_t build_param(uint8_t *buf, uint32_t pcmd, const void *value, uint32_t vsize)
{
    memset(buf, 0, 16u + vsize);
    put_u32(buf + 0, 0);      /* status */
    put_u32(buf + 4, 4);      /* psize  */
    put_u32(buf + 8, vsize);  /* vsize  */
    put_u32(buf + 12, pcmd);  /* parameter command */
    if (value != NULL)
        memcpy(buf + 16, value, vsize);
    return 16u + vsize;
}

static uint32_t build_unlock(uint8_t *buf, const uint8_t token[JDSP_WRITELOCK_TOKEN_LEN])
{
    return build_param(buf, JDSP_WRITELOCK_PARAM_ID, token, JDSP_WRITELOCK_TOKEN_LEN);
}

/* Runs the gate the way generic_command does. Returns the command status.
 * pass = 1 asserts the gate forwarded the command (reply untouched), pass = 0
 * asserts the gate consumed it (reply carries the same status it returns). */
static int32_t run_gate2(int *locked, uint32_t cmd, const uint8_t *blob, uint32_t size,
                         int pass, uint32_t *reply_size_out)
{
    uint32_t reply_size = 0;
    int32_t reply = 0x7f7f7f7f; /* sentinel: untouched reply is visible */
    int32_t rc = jdsp_writelock_gate(locked, cmd, size, blob, &reply, &reply_size);
    if (pass) {
        CHECK_EQ(rc, JDSP_WL_PROCEED);
        /* PROCEED: the gate left the reply for the engine. */
        CHECK_EQ(reply, (int32_t)0x7f7f7f7f);
        CHECK_EQ(reply_size, 0u);
    } else {
        /* Consumed/rejected: both channels carry the same status, exactly what
         * AudioFlinger and the reply reader see. */
        CHECK(rc != JDSP_WL_PROCEED || 1);
        CHECK_EQ(reply_size, (uint32_t)sizeof(int32_t));
        CHECK_EQ(reply, rc);
    }
    if (reply_size_out)
        *reply_size_out = reply_size;
    return rc;
}

static int32_t run_gate(int *locked, uint32_t cmd, const uint8_t *blob, uint32_t size,
                        uint32_t *reply_size_out)
{
    return run_gate2(locked, cmd, blob, size, 0, reply_size_out);
}

static void install_key(const uint8_t *token)
{
    CHECK_EQ(jdsp_writelock_set_expected_for_test(token, JDSP_WRITELOCK_TOKEN_LEN), 0);
}

/* ------------------------------------------------------------------------- */
/* tests                                                                     */
/* ------------------------------------------------------------------------- */

static void test_fresh_instance_is_locked(void)
{
    uint8_t blob[64];
    int32_t rc;
    int locked = 1;
    uint32_t n = build_param(blob, 1300, (float[]){48000.0f, 3.0f}, 8u);
    uint32_t reply_size = 0;

    install_key((const uint8_t *)"0123456789abcdef0123456789abcdef");

    rc = run_gate(&locked, WL_SET_PARAM, blob, n, &reply_size);
    CHECK_EQ(rc, -1);
    CHECK_EQ(locked, 1);
    CHECK_EQ(reply_size, (uint32_t)sizeof(int32_t));
}

static void test_wrong_token_rejected_and_stays_locked(void)
{
    uint8_t blob[64];
    uint8_t token[JDSP_WRITELOCK_TOKEN_LEN];
    int32_t rc;
    int locked = 1;
    uint32_t n;
    uint32_t reply_size = 0;

    install_key((const uint8_t *)"0123456789abcdef0123456789abcdef");
    memset(token, 0xAA, sizeof(token)); /* wrong */
    n = build_unlock(blob, token);
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, &reply_size);
    CHECK_EQ(rc, -1);
    CHECK_EQ(locked, 1);

    /* Still locked afterwards: a normal parameter is refused too. */
    n = build_param(blob, 1300, (float[]){1.0f, 2.0f}, 8u);
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, &reply_size);
    CHECK_EQ(rc, -1);
    CHECK_EQ(locked, 1);
}

static void test_correct_token_unlocks(void)
{
    uint8_t blob[64];
    uint8_t token[JDSP_WRITELOCK_TOKEN_LEN];
    int32_t rc;
    int locked = 1;
    uint32_t n;
    uint32_t reply_size = 0;

    memcpy(token, "0123456789abcdef0123456789abcdef", JDSP_WRITELOCK_TOKEN_LEN);
    install_key(token);

    n = build_unlock(blob, token);
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, &reply_size);
    CHECK_EQ(rc, 0);
    CHECK_EQ(locked, 0);

    /* Write window open: a normal parameter now passes through untouched
     * (PROCEED, the engine would apply it from here). */
    n = build_param(blob, 1300, (float[]){1.0f, 2.0f}, 8u);
    rc = run_gate2(&locked, WL_SET_PARAM, blob, n, 1, &reply_size);
    CHECK_EQ(rc, JDSP_WL_PROCEED);
    CHECK_EQ(locked, 0);
}

static void test_duplicate_unlock_is_idempotent(void)
{
    uint8_t blob[64];
    uint8_t token[JDSP_WRITELOCK_TOKEN_LEN];
    int32_t rc;
    int locked = 0;
    uint32_t n;

    memcpy(token, "0123456789abcdef0123456789abcdef", JDSP_WRITELOCK_TOKEN_LEN);
    install_key(token);

    n = build_unlock(blob, token);
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, NULL);
    CHECK_EQ(rc, 0);
    CHECK_EQ(locked, 0);

    /* A second unlock (e.g. the daemon re-attaching) succeeds and never
     * re-locks. */
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, NULL);
    CHECK_EQ(rc, 0);
    CHECK_EQ(locked, 0);
}

static void test_unlock_never_relocks_or_consumes_after_unlock(void)
{
    uint8_t blob[64];
    int32_t rc;
    int locked = 0;
    uint32_t n;

    install_key((const uint8_t *)"0123456789abcdef0123456789abcdef");

    /* Garbage on the reserved id while unlocked: refused with an error, but
     * the window stays open (only a matched token matters). */
    n = build_param(blob, JDSP_WRITELOCK_PARAM_ID, (const uint8_t[]){1, 2, 3}, 3u);
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, NULL);
    CHECK_EQ(rc, -1);
    CHECK_EQ(locked, 0);
}

static void test_truncated_unlock_rejected(void)
{
    uint8_t blob[64];
    uint8_t token[JDSP_WRITELOCK_TOKEN_LEN];
    int32_t rc;
    int locked = 1;
    uint32_t n;

    memcpy(token, "0123456789abcdef0123456789abcdef", JDSP_WRITELOCK_TOKEN_LEN);
    install_key(token);

    /* 31 byte token: structurally invalid, never unlocks. */
    n = build_param(blob, JDSP_WRITELOCK_PARAM_ID, token, 31u);
    CHECK_EQ(n, 47u);
    rc = run_gate(&locked, WL_SET_PARAM, blob, n, NULL);
    CHECK_EQ(rc, -1);
    CHECK_EQ(locked, 1);
}

static void test_non_set_param_commands_pass(void)
{
    int locked = 1;
    int32_t rc;

    /* ENABLE and friends are not parameter writes: the gate leaves them to the
     * engine even while locked (the framework enables the effect itself). */
    rc = jdsp_writelock_gate(&locked, WL_ENABLE, 0, NULL, NULL, NULL);
    CHECK_EQ(rc, JDSP_WL_PROCEED);

    /* Malformed SET_PARAM (too short for the cmd word) is also forwarded: the
     * engine's own handler refuses it. */
    rc = jdsp_writelock_gate(&locked, WL_SET_PARAM, 8u, (const uint8_t[8]){0}, NULL, NULL);
    CHECK_EQ(rc, JDSP_WL_PROCEED);
    CHECK_EQ(locked, 1);
}

static void test_is_unlock_cmd_detection(void)
{
    uint8_t blob[64];
    uint32_t n;

    n = build_param(blob, 1300, (float[]){1.0f}, 4u);
    CHECK_EQ(jdsp_writelock_is_unlock_cmd(n, blob), 0);

    n = build_param(blob, JDSP_WRITELOCK_PARAM_ID, (const uint8_t[32]){0}, 32u);
    CHECK_EQ(jdsp_writelock_is_unlock_cmd(n, blob), 1);

    /* Too short for the cmd word: never an unlock command. */
    CHECK_EQ(jdsp_writelock_is_unlock_cmd(12u, blob), 0);
    CHECK_EQ(jdsp_writelock_is_unlock_cmd(0u, NULL), 0);
}

static void test_key_file_loading(void)
{
    char dir[] = "/tmp/jdsp-wl-test-XXXXXX";
    char path[256];
    uint8_t want[JDSP_WRITELOCK_TOKEN_LEN];
    uint8_t got[JDSP_WRITELOCK_TOKEN_LEN];
    FILE *f;

    if (mkdtemp(dir) == NULL) {
        perror("mkdtemp");
        exit(2);
    }
    snprintf(path, sizeof(path), "%s/engine.key", dir);
    memcpy(want, "THIS-IS-A-HOST-TEST-KEY-012345678", JDSP_WRITELOCK_TOKEN_LEN);

    /* No key anywhere: nothing expected, gate cannot be satisfied. */
    jdsp_writelock_forget_key();
    unsetenv("JDSP_WRITELOCK_KEYFILE");
    CHECK_EQ(jdsp_writelock_has_expected(), 0);
    CHECK_EQ(jdsp_writelock_expected(got), -1);

    /* Wrong length: rejected. */
    f = fopen(path, "wb");
    fwrite(want, 1, 8, f);
    fclose(f);
    setenv("JDSP_WRITELOCK_KEYFILE", path, 1);
    jdsp_writelock_forget_key();
    CHECK_EQ(jdsp_writelock_has_expected(), 0);

    /* All zero: rejected. */
    f = fopen(path, "wb");
    memset(want, 0, sizeof(want));
    fwrite(want, 1, JDSP_WRITELOCK_TOKEN_LEN, f);
    fclose(f);
    jdsp_writelock_forget_key();
    CHECK_EQ(jdsp_writelock_has_expected(), 0);

    /* Exactly 32 bytes: loaded and verified through the gate. */
    memcpy(want, "THIS-IS-A-HOST-TEST-KEY-012345678", JDSP_WRITELOCK_TOKEN_LEN);
    f = fopen(path, "wb");
    fwrite(want, 1, JDSP_WRITELOCK_TOKEN_LEN, f);
    fclose(f);
    jdsp_writelock_forget_key();
    CHECK_EQ(jdsp_writelock_has_expected(), 1);
    CHECK_EQ(jdsp_writelock_expected(got), 0);
    CHECK(memcmp(got, want, sizeof(want)) == 0);

    {
        int locked = 1;
        uint8_t blob[64];
        uint32_t n = build_unlock(blob, want);
        int32_t rc = run_gate(&locked, WL_SET_PARAM, blob, n, NULL);
        CHECK_EQ(rc, 0);
        CHECK_EQ(locked, 0);
        n = build_param(blob, 1300, (float[]){9.0f}, 4u);
        rc = run_gate2(&locked, WL_SET_PARAM, blob, n, 1, NULL);
        CHECK_EQ(rc, JDSP_WL_PROCEED);
    }

    unsetenv("JDSP_WRITELOCK_KEYFILE");
    jdsp_writelock_forget_key();
    rmdir(dir);
}

static void test_key_rotation_retries_files(void)
{
    char dir[] = "/tmp/jdsp-wl-rot-XXXXXX";
    char path[256];
    uint8_t old_key[JDSP_WRITELOCK_TOKEN_LEN];
    uint8_t new_key[JDSP_WRITELOCK_TOKEN_LEN];
    uint8_t blob[64];
    int locked = 1;
    uint32_t n;
    FILE *f;

    if (mkdtemp(dir) == NULL) {
        perror("mkdtemp");
        exit(2);
    }
    snprintf(path, sizeof(path), "%s/engine.key", dir);
    memcpy(old_key, "old-key-old-key-old-key-old-key!", JDSP_WRITELOCK_TOKEN_LEN);
    memcpy(new_key, "new-key-new-key-new-key-new-key!", JDSP_WRITELOCK_TOKEN_LEN);

    /* Daemon generated the key, engine cached it, then the daemon rotated. */
    f = fopen(path, "wb");
    fwrite(old_key, 1, JDSP_WRITELOCK_TOKEN_LEN, f);
    fclose(f);
    setenv("JDSP_WRITELOCK_KEYFILE", path, 1);
    jdsp_writelock_forget_key();

    f = fopen(path, "wb");
    fwrite(new_key, 1, JDSP_WRITELOCK_TOKEN_LEN, f);
    fclose(f);

    /* Old token: mismatch, but the retry re-read the file, so the NEW token
     * unlocks without any manual cache invalidation. */
    n = build_unlock(blob, old_key);
    CHECK_EQ(run_gate(&locked, WL_SET_PARAM, blob, n, NULL), -1);
    CHECK_EQ(locked, 1);

    n = build_unlock(blob, new_key);
    CHECK_EQ(run_gate(&locked, WL_SET_PARAM, blob, n, NULL), 0);
    CHECK_EQ(locked, 0);
    n = build_param(blob, 1300, (float[]){9.0f}, 4u);
    CHECK_EQ(run_gate2(&locked, WL_SET_PARAM, blob, n, 1, NULL), JDSP_WL_PROCEED);
    CHECK_EQ(locked, 0);

    unsetenv("JDSP_WRITELOCK_KEYFILE");
    jdsp_writelock_forget_key();
    rmdir(dir);
}

int main(void)
{
    test_fresh_instance_is_locked();
    test_wrong_token_rejected_and_stays_locked();
    test_correct_token_unlocks();
    test_duplicate_unlock_is_idempotent();
    test_unlock_never_relocks_or_consumes_after_unlock();
    test_truncated_unlock_rejected();
    test_non_set_param_commands_pass();
    test_is_unlock_cmd_detection();
    test_key_file_loading();
    test_key_rotation_retries_files();

    if (g_failures) {
        fprintf(stderr, "== test_writelock FAILED (%d check(s))\n", g_failures);
        return 1;
    }
    printf("== test_writelock passed\n");
    return 0;
}

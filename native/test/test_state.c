/*
 * Off-device unit test for jdsp_state.c (the persistence module of
 * libjamesdsp.so).  Build and run with test/run_tests.sh:
 *
 *   cc -std=gnu99 -Wall -Wextra -I<src> test/test_state.c <src>/jdsp_state.c \
 *      -o build/test_state -lpthread
 *
 * The test exercises the real file format, not mocks: it writes/reads the same
 * files the device would see, so a green run means record -> persist -> reload
 * -> replay works and that corrupt or oversized data is rejected safely.
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "jdsp_state.h"

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

static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint32_t get_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

/* Build an effect_param_t shaped buffer exactly like AudioEffect::setParameter
 * does: status, psize, vsize, data[] = cmd + value. */
static uint32_t build_param(uint8_t *buf, uint32_t pcmd, const void *value, uint32_t vsize)
{
    memset(buf, 0, 16u + vsize);
    put_u32(buf + 0, 0);      /* status */
    put_u32(buf + 4, 4);      /* psize  */
    put_u32(buf + 8, vsize);  /* vsize  */
    put_u32(buf + 12, pcmd);  /* parameter command */
    memcpy(buf + 16, value, vsize);
    return 16u + vsize;
}

typedef struct {
    uint32_t cmd;
    uint32_t size;
    uint8_t buf[64];
} replay_rec_t;

static struct {
    replay_rec_t items[512];
    int count;
} g_replay;

static int32_t replay_dispatch(void *engine, uint32_t cmd, uint32_t size, const void *data)
{
    (void)engine;
    if (g_replay.count < 512) {
        replay_rec_t *r = &g_replay.items[g_replay.count++];
        r->cmd = cmd;
        r->size = size;
        /* Keep at least the effect_param_t header (cmd lives at offset 12) even
         * for large chunked records: the buffer is only a test snapshot. */
        if (size > 0u && data != NULL)
            memcpy(r->buf, data, size <= sizeof(r->buf) ? size : sizeof(r->buf));
    }
    return 0;
}

static void replay_reset(void)
{
    memset(&g_replay, 0, sizeof(g_replay));
}

static int replay_count(void)
{
    replay_reset();
    jdsp_state_replay(NULL, replay_dispatch);
    return g_replay.count;
}

static char g_dir[256];
static char g_path[512];

static void make_path(void)
{
    snprintf(g_dir, sizeof(g_dir), "/tmp/jdsp-state-test-XXXXXX");
    if (mkdtemp(g_dir) == NULL) {
        perror("mkdtemp");
        exit(2);
    }
    snprintf(g_path, sizeof(g_path), "%s/state.bin", g_dir);
    setenv("JDSP_STATE_PATH", g_path, 1);
}

static long file_size(const char *path)
{
    FILE *f = fopen(path, "rb");
    long n;
    if (f == NULL)
        return -1;
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    fclose(f);
    return n;
}

static int read_file(const char *path, uint8_t *buf, size_t cap)
{
    FILE *f = fopen(path, "rb");
    size_t n;
    if (f == NULL)
        return -1;
    n = fread(buf, 1, cap, f);
    fclose(f);
    return (int)n;
}

static void write_file(const char *path, const void *buf, size_t len)
{
    FILE *f = fopen(path, "wb");
    if (f == NULL) {
        perror("fopen");
        exit(2);
    }
    if (fwrite(buf, 1, len, f) != len) {
        perror("fwrite");
        exit(2);
    }
    fclose(f);
}

/* ------------------------------------------------------------------------- */
/* tests                                                                     */
/* ------------------------------------------------------------------------- */

static void test_empty_initial_state(void)
{
    /* No file yet: nothing to replay, no crash. */
    CHECK_EQ(jdsp_state_record_count(), 0);
    CHECK_EQ(replay_count(), 0);
    CHECK_EQ(file_size(g_path), -1);
}

static void test_record_persist_and_replay(void)
{
    uint8_t buf[64];
    float peq[2] = {48000.0f, 3.0f};
    int16_t enable = 1;
    uint32_t n;

    n = build_param(buf, 1300, peq, sizeof(peq));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);

    n = build_param(buf, 1214, &enable, sizeof(enable));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);

    jdsp_state_record(JDSP_STATE_CMD_ENABLE, 0, NULL);

    CHECK_EQ(jdsp_state_record_count(), 3);
    CHECK(file_size(g_path) > 16);

    /* Same file, fresh load: the store must come back identical. */
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), 3);
    CHECK_EQ(replay_count(), 3);
    CHECK_EQ(g_replay.items[0].cmd, JDSP_STATE_CMD_SET_PARAM);
    CHECK_EQ(g_replay.items[0].size, 16u + sizeof(peq));
    CHECK(memcmp(g_replay.items[0].buf + 16, peq, sizeof(peq)) == 0);
    CHECK_EQ(g_replay.items[1].cmd, JDSP_STATE_CMD_SET_PARAM);
    CHECK(memcmp(g_replay.items[1].buf + 16, &enable, sizeof(enable)) == 0);
    CHECK_EQ(g_replay.items[2].cmd, JDSP_STATE_CMD_ENABLE);
    CHECK_EQ(g_replay.items[2].size, 0u);
}

static void test_dedupe(void)
{
    uint8_t buf[64];
    float v[2] = {44100.0f, 5.0f};
    uint32_t n;
    size_t before = jdsp_state_record_count();

    /* Re-sending the same value must not grow the file (the app pushes its
     * complete state on every start). */
    n = build_param(buf, 1300, v, sizeof(v));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    CHECK_EQ(jdsp_state_record_count(), before + 1);
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    CHECK_EQ(jdsp_state_record_count(), before + 1);
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), before + 1);

    /* A different value for the same command is a new record. */
    v[1] = 7.0f;
    n = build_param(buf, 1300, v, sizeof(v));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    CHECK_EQ(jdsp_state_record_count(), before + 2);
    CHECK_EQ(replay_count(), (int)(before + 2));
    CHECK_EQ(g_replay.items[g_replay.count - 1].cmd, JDSP_STATE_CMD_SET_PARAM);
    CHECK(memcmp(g_replay.items[g_replay.count - 1].buf + 16, v, sizeof(v)) == 0);
}

static void test_dedupe_keeps_unrelated_records(void)
{
    uint8_t buf[64];
    float a[2] = {111.0f, 1.0f};
    float b[2] = {222.0f, 2.0f};
    uint32_t n;
    size_t before = jdsp_state_record_count();
    size_t mid;

    /* 1301/1302 are full-value settings, i.e. records the dedupe path owns. */
    n = build_param(buf, 1301, a, sizeof(a));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    jdsp_state_record(JDSP_STATE_CMD_ENABLE, 0, NULL);
    n = build_param(buf, 1302, b, sizeof(b));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    /* Two parameters are definitely new; the ENABLE may replace an older one
     * left by an earlier test, so only the lower bound is fixed here. */
    CHECK(jdsp_state_record_count() >= before + 2);
    mid = jdsp_state_record_count();

    /* The framework re-sends ENABLE around every effect teardown.  Only the old
     * ENABLE may be replaced; the parameters must survive.  (Regression: the
     * dedupe path used to evict the whole prefix up to the duplicate, wiping
     * every parameter recorded before it.) */
    jdsp_state_record(JDSP_STATE_CMD_ENABLE, 0, NULL);
    CHECK_EQ(jdsp_state_record_count(), mid);

    /* Same for a re-sent parameter that is not the last record. */
    n = build_param(buf, 1301, a, sizeof(a));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    CHECK_EQ(jdsp_state_record_count(), mid);

    /* Both parameters must still be in the replayed sequence. */
    CHECK_EQ(replay_count(), (int)mid);
    {
        int seen_1400 = 0, seen_1401 = 0, i;
        for (i = 0; i < g_replay.count; i++) {
            const uint8_t *d = g_replay.items[i].buf;
            if (g_replay.items[i].cmd != JDSP_STATE_CMD_SET_PARAM || g_replay.items[i].size < 16u)
                continue;
            if (get_u32(d + 12) == 1301)
                seen_1400 = 1;
            else if (get_u32(d + 12) == 1302)
                seen_1401 = 1;
        }
        CHECK(seen_1400);
        CHECK(seen_1401);
    }
}

static void test_enable_disable_last_wins(void)
{
    jdsp_state_record(JDSP_STATE_CMD_ENABLE, 0, NULL);
    jdsp_state_record(JDSP_STATE_CMD_DISABLE, 0, NULL);
    jdsp_state_record(JDSP_STATE_CMD_ENABLE, 0, NULL);
    CHECK_EQ(replay_count(), (int)jdsp_state_record_count());
    CHECK_EQ(g_replay.items[g_replay.count - 1].cmd, JDSP_STATE_CMD_ENABLE);
}

static void test_chunked_transfer_is_not_deduped(void)
{
    uint8_t buf[512];
    char chunk[256];
    uint32_t n;
    size_t before;
    int i;
    int chunk_records = 0;

    /* 8888 = allocate string buffer: times=1, sizePerBuffer=512 -> 2 chunks */
    int32_t alloc[2] = {1, 512};
    n = build_param(buf, 8888, alloc, sizeof(alloc));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);

    before = jdsp_state_record_count();
    memset(chunk, 'A', sizeof(chunk)); /* identical payloads on purpose */
    for (i = 0; i < 2; i++) {
        n = build_param(buf, 12001, chunk, sizeof(chunk));
        jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    }
    /* both chunks must survive although they are byte identical */
    CHECK_EQ(jdsp_state_record_count(), before + 2);

    jdsp_state_set_path(g_path);
    CHECK_EQ(replay_count(), (int)jdsp_state_record_count());
    for (i = 0; i < g_replay.count; i++) {
        if (g_replay.items[i].size >= 16u) {
            uint32_t size;
            memcpy(&size, g_replay.items[i].buf + 8, 4);
            (void)size;
        }
        if (g_replay.items[i].cmd == JDSP_STATE_CMD_SET_PARAM)
            chunk_records++;
    }
    CHECK(chunk_records >= 3);
}

static void test_oversized_record_is_dropped(void)
{
    size_t before = jdsp_state_record_count();
    uint8_t *big = calloc(1, JDSP_STATE_MAX_RECORD_BYTES + 32u);
    uint32_t n;
    CHECK(big != NULL);
    if (big == NULL)
        return;
    n = build_param(big, 1300, big + 16, JDSP_STATE_MAX_RECORD_BYTES); /* 1 MiB + 16 */
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, big);
    CHECK_EQ(jdsp_state_record_count(), before); /* dropped, not crashed */
    n = build_param(big, 1300, big + 16, 8u);    /* sane record still works */
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, big);
    CHECK_EQ(jdsp_state_record_count(), before + 1);
    free(big);
}

static void test_oversized_transfer_group_is_skipped(void)
{
    uint8_t buf[64];
    uint32_t n;
    size_t before;

    /* 9999 with 4096 chunks would need ~64 MiB: the whole transfer must be
     * skipped, including the chunks that follow it. */
    int32_t impulse[4] = {0, 2, 0, 4096};
    n = build_param(buf, 9999, impulse, sizeof(impulse));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    before = jdsp_state_record_count();

    {
        /* 16 bytes of effect_param_t + 16384 bytes of impulse chunk */
        uint8_t *chunk = calloc(1, 16u + 16384u);
        CHECK(chunk != NULL);
        if (chunk != NULL) {
            n = build_param(chunk, 12000, chunk + 16, 16384u);
            jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, chunk);
            CHECK_EQ(jdsp_state_record_count(), before);
            free(chunk);
        }
    }
}

static void test_corrupt_files_are_ignored(void)
{
    uint8_t snapshot[4096];
    int len = read_file(g_path, snapshot, sizeof(snapshot));
    CHECK(len > 20);
    if (len <= 20)
        return;

    /* 1) bad magic */
    snapshot[0] = 'X';
    write_file(g_path, snapshot, (size_t)len);
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), 0);
    CHECK_EQ(replay_count(), 0);
    snapshot[0] = 'J';

    /* 2) flipped byte in the middle -> checksum mismatch */
    snapshot[len / 2] ^= 0xFF;
    write_file(g_path, snapshot, (size_t)len);
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), 0);
    snapshot[len / 2] ^= 0xFF;

    /* 3) truncated file */
    write_file(g_path, snapshot, (size_t)(len - 7));
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), 0);

    /* 4) garbage that is not even a header */
    memset(snapshot, 0xA5, sizeof(snapshot));
    write_file(g_path, snapshot, sizeof(snapshot));
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), 0);
    CHECK_EQ(replay_count(), 0);

    /* 5) empty file */
    write_file(g_path, snapshot, 0);
    jdsp_state_set_path(g_path);
    CHECK_EQ(jdsp_state_record_count(), 0);

    /* 6) recording still works afterwards */
    {
        uint8_t buf[64];
        float v = 1.0f;
        uint32_t n = build_param(buf, 1302, &v, sizeof(v));
        jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
        CHECK_EQ(jdsp_state_record_count(), 1);
        jdsp_state_set_path(g_path);
        CHECK_EQ(jdsp_state_record_count(), 1);
    }
}

static void test_rejects_malformed_param_shape(void)
{
    uint8_t buf[64];
    size_t before = jdsp_state_record_count();

    /* psize must be 4 like every JamesDSP parameter */
    memset(buf, 0, sizeof(buf));
    put_u32(buf + 4, 8);
    put_u32(buf + 8, 4);
    put_u32(buf + 12, 1300);
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, 32, buf);
    CHECK_EQ(jdsp_state_record_count(), before);

    /* cmdSize smaller than psize+vsize+header */
    memset(buf, 0, sizeof(buf));
    put_u32(buf + 4, 4);
    put_u32(buf + 8, 16);
    put_u32(buf + 12, 1300);
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, 20, buf);
    CHECK_EQ(jdsp_state_record_count(), before);

    /* unknown effect command is not persisted */
    jdsp_state_record(0x7777, 0, NULL);
    CHECK_EQ(jdsp_state_record_count(), before);
}


/* ------------------------------------------------------------------------- */
/* hot reload                                                                */
/* ------------------------------------------------------------------------- */

static void make_state_file(const char *path, float v1, float v2)
{
    /* Produce a valid state file the same way another process would: through the
     * public API, then put the path back. */
    char saved[512];
    snprintf(saved, sizeof(saved), "%s", g_path);
    unlink(path); /* start from an empty file so the size is deterministic */
    jdsp_state_set_path(path);
    {
        uint8_t buf[64];
        float v[2] = {0.0f, 0.0f};
        uint32_t n;
        v[0] = v1;
        n = build_param(buf, 1300, v, sizeof(v));
        jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
        v[1] = v2;
        n = build_param(buf, 1301, v, sizeof(v));
        jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    }
    jdsp_state_set_path(saved);
}

static void test_reload_unchanged_returns_zero(void)
{
    /* Nothing touched the file since the last load: one stat, no reload. */
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);
}

static void test_reload_ignores_own_save(void)
{
    uint8_t buf[64];
    float v = 3.0f;
    uint32_t n = build_param(buf, 1302, &v, sizeof(v));
    size_t before = jdsp_state_record_count();

    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf); /* saves the file */
    CHECK_EQ(jdsp_state_record_count(), before + 1);
    /* Our own write must not look like a foreign change. */
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);
}

static void test_reload_detects_external_same_size_rewrite(void)
{
    char other[600];
    uint8_t a[4096];
    uint8_t b[4096];
    int la, lb;
    int replayed;

    snprintf(other, sizeof(other), "%s/other.bin", g_dir);

    /* Snapshot the current file, then let the module rewrite it through a
     * different path so we can copy the bytes back in place (same size, same
     * inode - exactly what a CLI tool writing a new value looks like). */
    la = read_file(g_path, a, sizeof(a));
    CHECK(la > 20);
    make_state_file(other, 11.0f, 12.0f);
    lb = read_file(other, b, sizeof(b));
    CHECK(lb > 20);
    CHECK_EQ(jdsp_state_record_count(), 2);
    CHECK_EQ(jdsp_state_reload_if_changed(), 0); /* just loaded 'other' */

    /* Now put a *different* file of the *same* byte size in place of 'other'
     * (values differ, layout identical) and copy it over the watched path. */
    make_state_file(other, 21.0f, 22.0f);
    CHECK_EQ(read_file(other, b, sizeof(b)), lb);
    usleep(20000); /* make sure the timestamp moves on any filesystem */
    write_file(g_path, b, (size_t)lb);

    replayed = jdsp_state_reload_if_changed();
    CHECK_EQ(replayed, 2);
    /* ... and the next poll is quiet again. */
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);

    /* Restore the original content so later tests keep their expectations. */
    write_file(g_path, a, (size_t)la);
    usleep(20000);
    CHECK_EQ(jdsp_state_reload_if_changed(), (int)jdsp_state_record_count());
    CHECK(jdsp_state_record_count() > 0);
}

static void test_reload_after_missing_then_appearing_file(void)
{
    char tmpdir[600];
    char p[700];
    snprintf(tmpdir, sizeof(tmpdir), "%s/reload", g_dir);
    snprintf(p, sizeof(p), "%s/state.bin", tmpdir);
    (void)mkdir(tmpdir, 0700);

    jdsp_state_set_path(p); /* does not exist */
    CHECK_EQ(jdsp_state_record_count(), 0);
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);

    jdsp_state_set_path(g_path); /* back to the real one */
    CHECK(jdsp_state_record_count() > 0);
}

static void test_reload_corrupt_file_is_silent(void)
{
    uint8_t snapshot[4096];
    uint8_t junk[256];
    int len = read_file(g_path, snapshot, sizeof(snapshot));
    CHECK(len > 20);
    if (len <= 20)
        return;

    memset(junk, 0x5A, sizeof(junk));
    usleep(20000);
    write_file(g_path, junk, sizeof(junk)); /* bad magic + wrong size */
    CHECK_EQ(jdsp_state_reload_if_changed(), 0); /* silent, store now empty */
    CHECK_EQ(jdsp_state_record_count(), 0);
    CHECK_EQ(jdsp_state_reload_if_changed(), 0); /* corrupt file not re-read */

    /* A valid file placed afterwards is picked up again. */
    usleep(20000);
    write_file(g_path, snapshot, (size_t)len);
    CHECK(jdsp_state_reload_if_changed() > 0);
    CHECK(jdsp_state_record_count() > 0);
}


static void test_generation_and_own_change(void)
{
    uint8_t buf[64];
    float v = 9.0f;
    uint32_t n = build_param(buf, 1302, &v, sizeof(v));
    uint64_t gen = jdsp_state_generation();

    /* Recording bumps the generation (another instance has to replay) but the
     * file stamp stays in sync, so the writer itself sees no file change. */
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
    CHECK(jdsp_state_generation() != gen);
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);

    /* External file replacement: the stamp check fires and also bumps the
     * generation again. */
    {
        char other[600];
        uint8_t bytes[2048];
        int len;
        snprintf(other, sizeof(other), "%s/gen.bin", g_dir);
        make_state_file(other, 31.0f, 32.0f);
        len = read_file(other, bytes, sizeof(bytes));
        CHECK(len > 20);
        usleep(20000);
        write_file(g_path, bytes, (size_t)len);
        gen = jdsp_state_generation();
        CHECK_EQ(jdsp_state_reload_if_changed(), 2);
        CHECK(jdsp_state_generation() != gen);
    }
}

#ifdef TEST_LIMITS
static void test_eviction_keeps_transfer_groups_whole(void)
{
    uint8_t buf[512];
    char chunk[256];
    uint32_t n;
    size_t i;
    int alloc_seen;
    int chunk_after_alloc;

    /* With the tiny limits this binary is built with, append transfers until
     * the store has to evict.  Invariant: a replay never sees a chunk whose
     * allocating record was evicted. */
    for (int round = 0; round < 12; round++) {
        int32_t alloc[2] = {1, 512};
        n = build_param(buf, 8888, alloc, sizeof(alloc));
        jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
        memset(chunk, '0' + round, sizeof(chunk));
        for (i = 0; i < 2; i++) {
            n = build_param(buf, 12001, chunk, sizeof(chunk));
            jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
        }
        {
            float v = (float)round;
            n = build_param(buf, 1302, &v, sizeof(v));
            jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
        }
    }

    jdsp_state_set_path(g_path);
    CHECK(jdsp_state_record_count() > 0);
    CHECK(jdsp_state_record_count() <= JDSP_STATE_MAX_RECORDS);
    CHECK_EQ(replay_count(), (int)jdsp_state_record_count());

    alloc_seen = 0;
    chunk_after_alloc = 0;
    for (i = 0; i < (size_t)g_replay.count; i++) {
        uint32_t pcmd = 0;
        if (g_replay.items[i].size < 16u)
            continue;
        memcpy(&pcmd, g_replay.items[i].buf + 12, 4);
        if (pcmd == 8888) {
            alloc_seen = 1;
            chunk_after_alloc = 0;
        } else if (pcmd == 12001 && alloc_seen) {
            chunk_after_alloc++;
        }
        if (pcmd == 8888 || pcmd == 12001) {
            CHECK(alloc_seen || pcmd == 8888);
        }
    }
    CHECK(alloc_seen);
    CHECK(chunk_after_alloc >= 1);
}
#endif

int main(void)
{
    make_path();

    test_empty_initial_state();
    test_record_persist_and_replay();
    test_dedupe();
    test_dedupe_keeps_unrelated_records();
    test_enable_disable_last_wins();
    test_chunked_transfer_is_not_deduped();
    test_oversized_record_is_dropped();
    test_oversized_transfer_group_is_skipped();
    test_rejects_malformed_param_shape();
    test_corrupt_files_are_ignored();
    test_reload_unchanged_returns_zero();
    test_reload_ignores_own_save();
    test_reload_detects_external_same_size_rewrite();
    test_reload_after_missing_then_appearing_file();
    test_reload_corrupt_file_is_silent();
    test_generation_and_own_change();
#ifdef TEST_LIMITS
    test_eviction_keeps_transfer_groups_whole();
#endif

    jdsp_state_shutdown();

    if (g_failures == 0) {
        printf("PASS: all jdsp_state tests passed (%s)\n", g_dir);
        return 0;
    }
    printf("FAILED: %d check(s) failed\n", g_failures);
    return 1;
}
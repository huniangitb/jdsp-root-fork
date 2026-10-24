/*
 * test_watch.c - host test for the inotify watcher (A+ hot reload).
 *
 * Two modes, because the watcher is started once per process:
 *   ./test_watch watch   watcher available (default): the file replacement must
 *                        be picked up by the watcher thread alone, without any
 *                        call to jdsp_state_reload_if_changed(), and our own
 *                        saves must not look like a foreign change.
 *   JDSP_STATE_NO_INOTIFY=1 ./test_watch poll
 *                        watcher suppressed: the same replacement must still be
 *                        picked up through the polling API.
 *
 * Run by test/run_tests.sh.
 */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "jdsp_state.h"

static int g_failures;
static char g_dir[512];
static char g_path[600];

#define CHECK(cond)                                                                    \
    do {                                                                               \
        if (!(cond)) {                                                                 \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);            \
            g_failures++;                                                              \
        }                                                                              \
    } while (0)

#define CHECK_EQ(a, b)                                                                 \
    do {                                                                               \
        long long va = (long long)(a), vb = (long long)(b);                            \
        if (va != vb) {                                                                \
            fprintf(stderr, "FAIL %s:%d: %s=%lld != %s=%lld\n", __FILE__, __LINE__,    \
                    #a, va, #b, vb);                                                   \
            g_failures++;                                                              \
        }                                                                              \
    } while (0)

static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint32_t build_param(uint8_t *buf, uint32_t pcmd, const void *value, uint32_t vsize)
{
    memset(buf, 0, 16u + vsize);
    put_u32(buf + 0, 0);
    put_u32(buf + 4, 4);
    put_u32(buf + 8, vsize);
    put_u32(buf + 12, pcmd);
    memcpy(buf + 16, value, vsize);
    return 16u + vsize;
}

static void record_float(uint32_t pcmd, float v)
{
    uint8_t buf[32];
    uint32_t n = build_param(buf, pcmd, &v, sizeof(v));
    jdsp_state_record(JDSP_STATE_CMD_SET_PARAM, n, buf);
}

static long read_file(const char *path, void *buf, size_t cap)
{
    FILE *f = fopen(path, "rb");
    long n;
    if (f == NULL)
        return -1;
    n = (long)fread(buf, 1, cap, f);
    fclose(f);
    return n;
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

/* Replace the state file the way a CLI tool or the app would: write the bytes to
 * a temporary name and rename() over the watched path.  rename() is atomic and
 * changes the inode, which is exactly the event the watcher has to handle. */
static void replace_file(const char *path, const void *buf, size_t len)
{
    char tmp[700];
    snprintf(tmp, sizeof(tmp), "%s.new", path);
    write_file(tmp, buf, len);
    if (rename(tmp, path) != 0) {
        perror("rename");
        exit(2);
    }
}

/* Spin until the store holds n records or the deadline expires. */
static int wait_for_count(size_t n, int timeout_ms)
{
    int waited = 0;
    while (waited < timeout_ms) {
        if (jdsp_state_record_count() == n)
            return 1;
        usleep(10000);
        waited += 10;
    }
    return jdsp_state_record_count() == n;
}

static void test_watch_mode(void)
{
    uint8_t snapshot[4096];
    long snap_len;
    uint64_t gen_after_record;
    int i;

    /* The watcher thread needs a moment to establish its watch; until then the
     * render path is expected to stay on the polling fallback. */
    for (i = 0; i < 200 && !jdsp_state_watcher_active(); i++)
        usleep(10000);
    printf("  watcher active: %d\n", jdsp_state_watcher_active());
    CHECK_EQ(jdsp_state_watcher_active(), 1);

    /* Two records, then snapshot the file. */
    record_float(400, 1.0f);
    record_float(401, 2.0f);
    CHECK_EQ(jdsp_state_record_count(), 2);
    snap_len = read_file(g_path, snapshot, sizeof(snapshot));
    CHECK(snap_len > 20);

    /* Grow the store so a reload is observable, and take the generation. */
    record_float(402, 3.0f);
    CHECK_EQ(jdsp_state_record_count(), 3);
    gen_after_record = jdsp_state_generation();

    /* Our own save must not come back as a reload: the generation may only move
     * when a record is added, never again afterwards. */
    usleep(400000);
    CHECK_EQ(jdsp_state_generation(), gen_after_record);
    CHECK_EQ(jdsp_state_record_count(), 3);

    /* External replacement: the watcher alone must pick this up (no call to
     * jdsp_state_reload_if_changed() anywhere in this test). */
    replace_file(g_path, snapshot, (size_t)snap_len);
    CHECK(wait_for_count(2, 2000));
    CHECK_EQ(jdsp_state_last_change_source(), JDSP_STATE_SRC_FILE);
    CHECK(jdsp_state_generation() != gen_after_record);

    /* And a second replacement with different content is picked up too. */
    record_float(403, 4.0f);
    CHECK_EQ(jdsp_state_record_count(), 3);
    for (i = 0; i < 3; i++)
        usleep(100000);
    replace_file(g_path, snapshot, (size_t)snap_len);
    CHECK(wait_for_count(2, 2000));
}

static void test_poll_mode(void)
{
    uint8_t snapshot[4096];
    long snap_len;

    printf("  watcher active: %d\n", jdsp_state_watcher_active());
    CHECK_EQ(jdsp_state_watcher_active(), 0);

    record_float(400, 1.0f);
    record_float(401, 2.0f);
    CHECK_EQ(jdsp_state_record_count(), 2);
    snap_len = read_file(g_path, snapshot, sizeof(snapshot));
    CHECK(snap_len > 20);

    record_float(402, 3.0f);
    CHECK_EQ(jdsp_state_record_count(), 3);

    usleep(20000);
    replace_file(g_path, snapshot, (size_t)snap_len);

    /* No watcher: the explicit poll call has to see it. */
    CHECK_EQ(jdsp_state_reload_if_changed(), 2);
    CHECK_EQ(jdsp_state_last_change_source(), JDSP_STATE_SRC_FILE);
    /* ... and the next poll is quiet. */
    CHECK_EQ(jdsp_state_reload_if_changed(), 0);
}

int main(int argc, char **argv)
{
    const char *mode = argc > 1 ? argv[1] : "watch";

    snprintf(g_dir, sizeof(g_dir), "/tmp/jdsp-watch-%ld", (long)getpid());
    if (mkdir(g_dir, 0700) != 0 && errno != EEXIST) {
        perror("mkdir");
        return 2;
    }
    snprintf(g_path, sizeof(g_path), "%s/state.bin", g_dir);

    jdsp_state_set_path(g_path);

    if (strcmp(mode, "poll") == 0)
        test_poll_mode();
    else
        test_watch_mode();

    if (g_failures == 0) {
        printf("  PASS\n");
        return 0;
    }
    fprintf(stderr, "  FAILED: %d check(s)\n", g_failures);
    return 1;
}

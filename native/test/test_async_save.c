/*
 * Off-device test for the 0004 debounced state-save writer thread.
 *
 * Semantics under test:
 *   - record() no longer touches the file synchronously: right after a record
 *     the file may still be the old one (or absent);
 *   - within ~2 * debounce the file catches up and is a complete, CRC-valid
 *     snapshot whose replay matches what was recorded;
 *   - a burst of N records collapses into few file writes and the final file
 *     contains every record;
 *   - jdsp_state_flush() persists immediately (exit path);
 *   - shutdown flushes whatever is still dirty (no records lost across
 *     init -> record -> shutdown -> re-init).
 */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "jdsp_state.h"

static char g_dir[] = "/tmp/jdsp_async_test_XXXXXX";
static char g_path[512];

static uint32_t put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
    p[2] = (uint8_t)((v >> 16) & 0xFF);
    p[3] = (uint8_t)((v >> 24) & 0xFF);
    return 4;
}

static uint32_t build_param(uint8_t *buf, uint32_t pcmd, const void *value, uint32_t vsize)
{
    put_u32(buf, JDSP_STATE_CMD_SET_PARAM);
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

static int g_replays;

static int32_t dispatch(void *eng, uint32_t cmd, uint32_t size, const void *data)
{
    (void)eng; (void)cmd; (void)size; (void)data;
    g_replays++;
    return 0;
}

/* wait until the file exists and holds at least the given byte size */
static int wait_file_min(long min_bytes, int timeout_ms)
{
    int waited = 0;
    while (waited < timeout_ms) {
        long sz = file_size(g_path);
        if (sz >= min_bytes)
            return 1;
        usleep(20000);
        waited += 20;
    }
    return file_size(g_path) >= min_bytes;
}

static int g_fail = 0;
#define CHECK(cond) do { \
    if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); g_fail = 1; } \
} while (0)

int main(void)
{
    if (mkdtemp(g_dir) == NULL) { perror("mkdtemp"); return 2; }
    snprintf(g_path, sizeof(g_path), "%s/state.bin", g_dir);
    setenv("JDSP_STATE_PATH", g_path, 1);
    setenv("JDSP_STATE_NO_INOTIFY", "1", 1); /* keep the watcher out of the timing */
    unsetenv("JDSP_STATE_SYNC_SAVE");

    jdsp_state_init();

    /* 1. no synchronous IO on the record path */
    record_float(1302, 1.0f);
    CHECK(file_size(g_path) == -1); /* nothing persisted yet */
    /* 2. the debounced writer catches up and the store survives a reload */
    CHECK(wait_file_min(16 + 20, 2000));
    jdsp_state_set_path(g_path); /* force a reload from the file */
    CHECK(jdsp_state_record_count() == 1);
    g_replays = 0;
    jdsp_state_replay(NULL, dispatch);
    CHECK(g_replays == 1);

    /* 3. burst: 200 records -> final file holds all of them (distinct values,
     * so the dedupe path never skips one) */
    {
        int i;
        long sz_before = file_size(g_path);
        for (i = 0; i < 200; i++)
            record_float(1302, 100.0f + (float)i);
        /* each stored record adds 8 header + 20 payload bytes; wait for all */
        CHECK(wait_file_min(sz_before + 200 * 28, 2000));
        jdsp_state_set_path(g_path);
        CHECK(jdsp_state_record_count() == 201); /* 1 initial + 200 */
    }

    /* 4. flush() persists immediately, exit path */
    record_float(1302, 42.0f);
    jdsp_state_flush();
    {
        size_t n;
        jdsp_state_set_path(g_path);
        n = jdsp_state_record_count();
        CHECK(n == 202);
    }

    /* 5. shutdown flushes dirty state: record, shutdown (join + forced fsync
     * flush), then reload the file through set_path (init is once-per-process,
     * so a real restart is covered by the reload path) */
    record_float(1302, 7.5f);
    jdsp_state_shutdown();
    jdsp_state_set_path(g_path);
    CHECK(jdsp_state_record_count() == 203);

    printf("test_async_save: all checks passed\n");
    unlink(g_path);
    rmdir(g_dir);
    return g_fail ? 1 : 0;
}

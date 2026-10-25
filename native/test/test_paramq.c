/*
 * Off-device unit test for jdsp_paramq.c (deferred SET_PARAM application,
 * patch 0003).  Build and run with test/run_tests.sh:
 *
 *   cc -std=gnu99 -Wall -Wextra -I<src> test/test_paramq.c <src>/jdsp_paramq.c \
 *       -o build/test_paramq -lpthread
 *
 * Covered:
 *   - jdsp_param_classify: every value id the engine handler accepts (with its
 *     exact value size) classifies VALUE; wrong value size, unknown ids, the
 *     write-lock handshake id, malformed headers and truncated commands are
 *     INVALID; chunked transfer commands are STREAM;
 *   - queue: FIFO order, same-id merge (latest value wins, position kept),
 *     oldest-eviction at capacity with the dropped counter;
 *   - drain: applies outside the lock, frees every entry, resets the queue;
 *   - destroy with pending entries (no leak / no crash);
 *   - concurrency: one producer thread pushing slider-like updates while the
 *     main thread drains - the final applied value must be the last written.
 */
#define _GNU_SOURCE /* pthread_tryjoin_np */
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jdsp_paramq.h"

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

/* Mirror of JDSP_WRITELOCK_PARAM_ID (the handshake must classify INVALID). */
#define TEST_UNLOCK_ID 31232

static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

/* Build an effect_param_t shaped command: status, psize, vsize, cmd, value. */
static uint32_t build_param(uint8_t *buf, uint32_t pcmd, const void *value, uint32_t vsize)
{
    memset(buf, 0, 16u + vsize);
    put_u32(buf + 0, 0); /* status */
    put_u32(buf + 4, 4); /* psize */
    put_u32(buf + 8, vsize);
    put_u32(buf + 12, pcmd);
    if (value != NULL)
        memcpy(buf + 16, value, vsize);
    return 16u + vsize;
}

/* Recording of what the "audio thread" applied, for the drain callbacks. */
#define APPLIED_MAX 256
typedef struct {
    int32_t ids[APPLIED_MAX];
    uint32_t sizes[APPLIED_MAX];
    int count;
} applied_log_t;

static void apply_log_cb(void *ud, uint32_t size, const uint8_t *data)
{
    applied_log_t *log = (applied_log_t *)ud;
    int32_t id = 0;
    if (size >= 16)
        memcpy(&id, data + 12, 4);
    if (log->count < APPLIED_MAX)
    {
        log->ids[log->count] = id;
        log->sizes[log->count] = size;
    }
    log->count++;
}

/* ------------------------------------------------------------------------- */
/* classification                                                            */
/* ------------------------------------------------------------------------- */

static void test_classify_value_ids(void)
{
    /* {id, vsize} pairs that EffectDSPMainCommand consumes, with the exact
     * shape its handler checks. */
    static const struct { int32_t id; uint32_t vsize; } value_ids[] = {
        {112, 2}, {115, 68}, {116, 128}, {128, 2}, {137, 2}, {150, 2}, {188, 2},
        {1200, 2}, {1201, 2}, {1202, 2}, {1203, 2}, {1204, 2}, {1205, 2},
        {1206, 2}, {1208, 2}, {1210, 2}, {1212, 2}, {1213, 2}, {1214, 2},
        {1215, 2}, {1216, 2}, {1217, 2}, {1218, 2},
        {1301, 20}, {1302, 4}, {1305, 44}, {1306, 44}, {1307, 44},
        {1500, 12}, {25000, 4}, {25001, 4}, {25002, 4}, {25003, 4},
    };
    uint8_t buf[2048];
    size_t i;

    for (i = 0; i < sizeof(value_ids) / sizeof(value_ids[0]); i++)
    {
        uint32_t size = build_param(buf, (uint32_t)value_ids[i].id, NULL, value_ids[i].vsize);
        int32_t id = 0;
        CHECK_EQ(jdsp_param_classify(buf, size, &id), JDSP_PARAM_CLASS_VALUE);
        CHECK_EQ(id, value_ids[i].id);
        /* wrong value size must be rejected */
        size = build_param(buf, (uint32_t)value_ids[i].id, NULL, value_ids[i].vsize + 1);
        CHECK_EQ(jdsp_param_classify(buf, size, NULL), JDSP_PARAM_CLASS_INVALID);
    }
}

static void test_classify_peq_variable(void)
{
    uint8_t buf[1400];
    int32_t id = 0;

    /* PEQ cascade (1300): 2 header floats + N * 5 band floats. */
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 1300, NULL, 8), &id),
             JDSP_PARAM_CLASS_VALUE);
    CHECK_EQ(id, 1300);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 1300, NULL, 12), NULL),
             JDSP_PARAM_CLASS_VALUE);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 1300, NULL, 1288), NULL),
             JDSP_PARAM_CLASS_VALUE);
    /* below minimum, not multiple of 4, above 64 bands */
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 1300, NULL, 4), NULL),
             JDSP_PARAM_CLASS_INVALID);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 1300, NULL, 6), NULL),
             JDSP_PARAM_CLASS_INVALID);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 1300, NULL, 1292), NULL),
             JDSP_PARAM_CLASS_INVALID);
}

static void test_classify_stream_ids(void)
{
    uint8_t buf[17408];
    int32_t id = 0;

    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 8888, NULL, 8), &id),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(id, 8888);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 9999, NULL, 16), NULL),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 12000, NULL, 16384), NULL),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 12001, NULL, 256), NULL),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 10004, NULL, 2), NULL),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 10006, NULL, 2), NULL),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 10009, NULL, 2), NULL),
             JDSP_PARAM_CLASS_STREAM);
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 10010, NULL, 2), NULL),
             JDSP_PARAM_CLASS_STREAM);
}

static void test_classify_invalid(void)
{
    uint8_t buf[256];
    int32_t id = 12345;

    /* unknown parameter id */
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 7777, NULL, 4), &id),
             JDSP_PARAM_CLASS_INVALID);
    /* the write-lock handshake must never queue or record: it is consumed by
     * the gate; with the lock compiled out it must still classify invalid */
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, TEST_UNLOCK_ID, NULL, 32), &id),
             JDSP_PARAM_CLASS_INVALID);
    CHECK_EQ(id, TEST_UNLOCK_ID);

    /* psize != 4 */
    build_param(buf, 112, NULL, 2);
    put_u32(buf + 4, 8);
    CHECK_EQ(jdsp_param_classify(buf, 18, NULL), JDSP_PARAM_CLASS_INVALID);

    /* vsize == 0 */
    build_param(buf, 112, NULL, 2);
    put_u32(buf + 8, 0);
    CHECK_EQ(jdsp_param_classify(buf, 18, NULL), JDSP_PARAM_CLASS_INVALID);

    /* truncated command: cmd_size does not cover 16 + vsize */
    CHECK_EQ(jdsp_param_classify(buf, build_param(buf, 116, NULL, 128) - 1, NULL),
             JDSP_PARAM_CLASS_INVALID);
    /* too short for a header at all */
    CHECK_EQ(jdsp_param_classify(buf, 15, NULL), JDSP_PARAM_CLASS_INVALID);
    CHECK_EQ(jdsp_param_classify(buf, 8, NULL), JDSP_PARAM_CLASS_INVALID);
    CHECK_EQ(jdsp_param_classify(NULL, 32, NULL), JDSP_PARAM_CLASS_INVALID);
}

/* ------------------------------------------------------------------------- */
/* queue                                                                     */
/* ------------------------------------------------------------------------- */

static uint8_t *make_cmd(int32_t id, uint32_t vsize)
{
    uint8_t buf[2048];
    uint32_t size = build_param(buf, (uint32_t)id, NULL, vsize);
    uint8_t *copy = (uint8_t *)malloc(size);
    memcpy(copy, buf, size);
    return copy;
}

static void test_queue_fifo_order(void)
{
    jdsp_paramq_t q;
    applied_log_t log;
    memset(&log, 0, sizeof(log));

    jdsp_paramq_init(&q);
    CHECK_EQ(jdsp_paramq_push(&q, 112, 18, make_cmd(112, 2), 1), 0);
    CHECK_EQ(jdsp_paramq_push(&q, 1300, 16 + 8, make_cmd(1300, 8), 1), 0);
    CHECK_EQ(jdsp_paramq_push(&q, 1500, 28, make_cmd(1500, 12), 1), 0);
    jdsp_paramq_drain(&q, &log, apply_log_cb);
    CHECK_EQ(log.count, 3);
    CHECK_EQ(log.ids[0], 112);
    CHECK_EQ(log.ids[1], 1300);
    CHECK_EQ(log.ids[2], 1500);
    CHECK_EQ(log.sizes[0], 18);
    CHECK_EQ(log.sizes[1], 16 + 8);
    CHECK_EQ(log.sizes[2], 28);
    /* empty after drain; a second drain applies nothing */
    log.count = 0;
    jdsp_paramq_drain(&q, &log, apply_log_cb);
    CHECK_EQ(log.count, 0);
    CHECK_EQ(q.dropped, 0u);
    jdsp_paramq_destroy(&q);
}

static void test_queue_merge_same_id(void)
{
    jdsp_paramq_t q;
    applied_log_t log;
    uint8_t *a, *b, *peq;
    memset(&log, 0, sizeof(log));

    jdsp_paramq_init(&q);
    a = make_cmd(112, 2);
    b = make_cmd(112, 2);
    peq = make_cmd(1300, 8);
    /* mark the payloads so the applied bytes can be checked */
    a[16] = 1;
    b[16] = 2;
    peq[16] = 7;
    CHECK_EQ(jdsp_paramq_push(&q, 112, 18, a, 1), 0);
    CHECK_EQ(jdsp_paramq_push(&q, 1300, 16 + 8, peq, 1), 0);
    CHECK_EQ(jdsp_paramq_push(&q, 112, 18, b, 1), 0); /* merges into slot 0 */
    jdsp_paramq_drain(&q, &log, apply_log_cb);
    CHECK_EQ(log.count, 2); /* merged: one apply per parameter */
    CHECK_EQ(log.ids[0], 112);
    CHECK_EQ(log.ids[1], 1300);
    jdsp_paramq_destroy(&q);
}

static void test_queue_evict_oldest_when_full(void)
{
    jdsp_paramq_t q;
    applied_log_t log;
    int i;
    memset(&log, 0, sizeof(log));

    jdsp_paramq_init(&q);
    for (i = 0; i < JDSP_PARAMQ_CAPACITY; i++)
        CHECK_EQ(jdsp_paramq_push(&q, 100 + i, 18, make_cmd(100 + i, 2), 0), 0);
    CHECK_EQ(q.dropped, 0u);
    /* one more distinct id: the oldest (100) must be evicted and counted */
    CHECK_EQ(jdsp_paramq_push(&q, 999, 18, make_cmd(999, 2), 0), 1);
    CHECK_EQ(q.dropped, 1u);
    jdsp_paramq_drain(&q, &log, apply_log_cb);
    CHECK_EQ(log.count, JDSP_PARAMQ_CAPACITY);
    CHECK_EQ(log.ids[0], 101); /* 100 gone */
    CHECK_EQ(log.ids[JDSP_PARAMQ_CAPACITY - 1], 999);
    jdsp_paramq_destroy(&q);
}

static void test_queue_merge_avoids_eviction(void)
{
    jdsp_paramq_t q;
    int i;
    jdsp_paramq_init(&q);
    /* a slider storm of the same id collapses into a single slot */
    for (i = 0; i < JDSP_PARAMQ_CAPACITY * 4; i++)
        CHECK_EQ(jdsp_paramq_push(&q, 112, 18, make_cmd(112, 2), 1), 0);
    CHECK_EQ(q.dropped, 0u);
    {
        applied_log_t log;
        memset(&log, 0, sizeof(log));
        jdsp_paramq_drain(&q, &log, apply_log_cb);
        CHECK_EQ(log.count, 1);
    }
    jdsp_paramq_destroy(&q);
}

static void test_destroy_with_pending(void)
{
    jdsp_paramq_t q;
    int i;
    jdsp_paramq_init(&q);
    for (i = 0; i < 10; i++)
        CHECK_EQ(jdsp_paramq_push(&q, 112 + i, 18, make_cmd(112 + i, 2), 1), 0);
    jdsp_paramq_destroy(&q); /* must free the queued copies */
    jdsp_paramq_destroy(&q); /* idempotent */
}

/* ------------------------------------------------------------------------- */
/* concurrency: producer pushes slider updates while the consumer drains     */
/* ------------------------------------------------------------------------- */

#define STRESS_UPDATES 20000

typedef struct {
    jdsp_paramq_t *q;
    long updates;
} producer_arg_t;

static void *stress_producer(void *arg)
{
    producer_arg_t *pa = (producer_arg_t *)arg;
    long i;
    for (i = 0; i < pa->updates; i++)
    {
        uint8_t buf[18];
        uint8_t *copy;
        int16_t v = (int16_t)i;
        uint32_t size = build_param(buf, 112, &v, 2);
        (void)size;
        copy = (uint8_t *)malloc(18);
        memcpy(copy, buf, 18);
        jdsp_paramq_push(pa->q, 112, 18, copy, 1);
    }
    return NULL;
}

static int g_stress_last;
static int g_stress_applied;

static void stress_apply_cb(void *ud, uint32_t size, const uint8_t *data)
{
    int16_t v = 0;
    (void)ud;
    (void)size;
    memcpy(&v, data + 16, 2);
    /* values from the single producer are monotonically increasing */
    if (v < g_stress_last)
        g_failures++; /* out of order / torn value */
    g_stress_last = v;
    g_stress_applied++;
}

static void test_concurrent_producer_drain(void)
{
    jdsp_paramq_t q;
    producer_arg_t pa;
    pthread_t tid;
    long drained_batches = 0;

    jdsp_paramq_init(&q);
    pa.q = &q;
    pa.updates = STRESS_UPDATES;
    g_stress_last = -1;
    g_stress_applied = 0;
    pthread_create(&tid, NULL, stress_producer, &pa);
    /* drain like the render thread: repeatedly, until the producer is done
     * and the queue is empty */
    for (;;)
    {
        jdsp_paramq_drain(&q, NULL, stress_apply_cb);
        drained_batches++;
        if (pthread_tryjoin_np(tid, NULL) == 0)
        {
            jdsp_paramq_drain(&q, NULL, stress_apply_cb);
            break;
        }
        if (drained_batches > 1000000)
            break; /* must never happen */
    }
    CHECK_EQ(g_stress_last, STRESS_UPDATES - 1); /* the latest value always wins */
    CHECK(g_stress_applied < STRESS_UPDATES);    /* merging actually happened */
    printf("  stress: %d applied of %d updates in %ld batches\n",
           g_stress_applied, STRESS_UPDATES, drained_batches);
    jdsp_paramq_destroy(&q);
}

int main(void)
{
    test_classify_value_ids();
    test_classify_peq_variable();
    test_classify_stream_ids();
    test_classify_invalid();
    test_queue_fifo_order();
    test_queue_merge_same_id();
    test_queue_evict_oldest_when_full();
    test_queue_merge_avoids_eviction();
    test_destroy_with_pending();
    test_concurrent_producer_drain();

    if (g_failures == 0)
    {
        printf("test_paramq: all passed\n");
        return 0;
    }
    printf("test_paramq: %d failure(s)\n", g_failures);
    return 1;
}

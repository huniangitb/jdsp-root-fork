#!/usr/bin/env bash
#
# Off-device unit test for the state persistence module (jdsp_state.c).
# Compiles it for the host with two sets of limits:
#   1. production limits  -> format/replay/corruption behaviour
#   2. tiny limits        -> eviction keeps chunked transfer groups whole
# plus test_watch.c, which covers the inotify watcher and its polling fallback.
#
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRC=${SRC:-$ROOT/src/Main/libjamesdsp/jni/jamesdsp}
OUT=$ROOT/build
CC=${CC:-cc}
mkdir -p "$OUT"

# Since 0004 the record path saves through a debounced writer thread.  The
# format/replay tests below assert the state file immediately after a record,
# so they run against the legacy synchronous save path (the same serialize/
# write/rename code, just called inline).  The async path is smoke-tested by
# test_async_save at the end.
export JDSP_STATE_SYNC_SAVE=1

# The format/replay tests drive the polling API directly, so the watcher is
# switched off for them: otherwise its thread could pick the change up first and
# the poll would legitimately report "unchanged".  The watcher has its own test.
echo "== build test_state (production limits, polling path)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_state.c" "$SRC/jdsp_state.c" -o "$OUT/test_state" -lpthread
JDSP_STATE_NO_INOTIFY=1 "$OUT/test_state"

echo "== build test_state (tiny limits, eviction)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    -DTEST_LIMITS -DJDSP_STATE_MAX_TOTAL_BYTES=2048u -DJDSP_STATE_MAX_RECORDS=12u \
    -DJDSP_STATE_MAX_GROUP_BYTES=900u \
    "$ROOT/test/test_state.c" "$SRC/jdsp_state.c" -o "$OUT/test_state_limits" -lpthread
JDSP_STATE_NO_INOTIFY=1 "$OUT/test_state_limits"

echo "== build test_watch (inotify watcher + polling fallback)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_watch.c" "$SRC/jdsp_state.c" -o "$OUT/test_watch" -lpthread
echo "-- watcher mode"
"$OUT/test_watch" watch
echo "-- polling fallback mode"
JDSP_STATE_NO_INOTIFY=1 "$OUT/test_watch" poll

echo "== build test_writelock (caller write protection)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_writelock.c" "$SRC/jdsp_writelock.c" -o "$OUT/test_writelock" -lpthread
"$OUT/test_writelock"

echo "== build test_paramq (deferred SET_PARAM queue, 0003)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_paramq.c" "$SRC/jdsp_paramq.c" -o "$OUT/test_paramq" -lpthread
"$OUT/test_paramq"

echo "== build test_accum (ACCESS_ACCUMULATE helper, 0005)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_accum.c" "$SRC/jdsp_accum.c" -o "$OUT/test_accum"
"$OUT/test_accum"

echo "== build test_async_save (debounced writer thread, 0004)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_async_save.c" "$SRC/jdsp_state.c" -o "$OUT/test_async_save" -lpthread
"$OUT/test_async_save"

echo "== build test_tap (0007 capture tap)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_tap.c" "$SRC/jdsp_tap.c" -o "$OUT/test_tap" -lpthread
"$OUT/test_tap"

echo "== build test_mixcheck (0008 mix fidelity check)"
$CC -std=gnu99 -O1 -g -Wall -Wextra -Wno-unused-parameter -I"$SRC" \
    "$ROOT/test/test_mixcheck.c" "$SRC/jdsp_mixcheck.c" -o "$OUT/test_mixcheck" -lm
"$OUT/test_mixcheck"

echo "== all host tests passed"

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
SRC=$ROOT/src/Main/libjamesdsp/jni/jamesdsp
OUT=$ROOT/build
CC=${CC:-cc}
mkdir -p "$OUT"

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

echo "== all host tests passed"

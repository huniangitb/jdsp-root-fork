#!/usr/bin/env bash
# Host self-test for the shared device-selection library (no device needed).
#
#   bash backend/test/device/lib/selftest.sh
#
# It stubs "adb" with a fake that reports a configurable device list, so the
# resolution rules can be checked without any real device.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
FAIL=0
PASS=0

check() { # name expected actual
    if [ "$2" = "$3" ]; then
        PASS=$((PASS + 1))
        echo "PASS  $1"
    else
        FAIL=$((FAIL + 1))
        echo "FAIL  $1: expected <$2> but got <$3>"
    fi
}

# Fake adb: prints the device list from $FAKE_ADB_DEVICES, one per line.
FAKE_BIN=$(mktemp -d)
cat > "$FAKE_BIN/adb" <<'EOF'
#!/usr/bin/env bash
# Fake adb for the device-selection self-test.
#   adb devices                 -> lists $FAKE_ADB_DEVICES as "device"
#   adb -s <serial> get-state   -> "device" only for a listed serial
if [ "$1" = "devices" ]; then
    echo "List of devices attached"
    for d in ${FAKE_ADB_DEVICES:-}; do
        echo "$d	device"
    done
    exit 0
fi
if [ "$1" = "-s" ] && [ "$3" = "get-state" ]; then
    for d in ${FAKE_ADB_DEVICES:-}; do
        if [ "$d" = "$2" ]; then
            echo "device"
            exit 0
        fi
    done
    echo "error: no devices/emulators found"
    exit 1
fi
exit 0
EOF
chmod +x "$FAKE_BIN/adb"
export PATH="$FAKE_BIN:$PATH"

# 1) explicit argument wins
out=$(FAKE_ADB_DEVICES=AAA111 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial AAA111" 2>&1)
check "explicit serial" "AAA111" "$out"

# 2) DSH_SERIAL
out=$(FAKE_ADB_DEVICES=BBB222 DSH_SERIAL=BBB222 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
check "DSH_SERIAL" "BBB222" "$out"

# 3) ANDROID_SERIAL
out=$(FAKE_ADB_DEVICES=CCC333 ANDROID_SERIAL=CCC333 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
check "ANDROID_SERIAL" "CCC333" "$out"

# 4) SERIAL
out=$(FAKE_ADB_DEVICES=DDD444 SERIAL=DDD444 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
check "SERIAL" "DDD444" "$out"

# 5) single online device
out=$(FAKE_ADB_DEVICES=EEE555 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
check "single online device" "EEE555" "$out"

# 6) two online devices -> refuse (exit 2), no serial chosen
out=$(FAKE_ADB_DEVICES="FFF666 GGG777" bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
rc=$?
if [ "$rc" = "2" ] && printf '%s' "$out" | grep -q "refusing to guess"; then
    PASS=$((PASS + 1))
    echo "PASS  two online devices are refused"
else
    FAIL=$((FAIL + 1))
    echo "FAIL  two online devices are refused (rc=$rc out=$out)"
fi

# 7) no online device -> refuse
out=$(FAKE_ADB_DEVICES="" bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
rc=$?
if [ "$rc" = "2" ] && printf '%s' "$out" | grep -q "no online adb device"; then
    PASS=$((PASS + 1))
    echo "PASS  no online device is refused"
else
    FAIL=$((FAIL + 1))
    echo "FAIL  no online device is refused (rc=$rc out=$out)"
fi

# 8) explicit serial that is not online -> refuse
out=$(FAKE_ADB_DEVICES=EEE555 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial ZZZ999" 2>&1)
rc=$?
if [ "$rc" = "2" ] && printf '%s' "$out" | grep -q "is not available"; then
    PASS=$((PASS + 1))
    echo "PASS  offline explicit serial is refused"
else
    FAIL=$((FAIL + 1))
    echo "FAIL  offline explicit serial is refused (rc=$rc out=$out)"
fi

# 9) explicit serial that is online -> accepted even with several devices online
out=$(FAKE_ADB_DEVICES="EEE555 FFF666" bash -c ". '$HERE/device.sh'; jdsp_resolve_serial FFF666" 2>&1)
check "explicit serial with several devices online" "FFF666" "$out"

# 10) priority: argument beats DSH_SERIAL
out=$(FAKE_ADB_DEVICES="AAA111 BBB222" DSH_SERIAL=BBB222 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial AAA111" 2>&1)
check "argument beats DSH_SERIAL" "AAA111" "$out"

# 11) priority: DSH_SERIAL beats ANDROID_SERIAL
out=$(FAKE_ADB_DEVICES="BBB222 CCC333" DSH_SERIAL=BBB222 ANDROID_SERIAL=CCC333 bash -c ". '$HERE/device.sh'; jdsp_resolve_serial" 2>&1)
check "DSH_SERIAL beats ANDROID_SERIAL" "BBB222" "$out"

rm -rf "$FAKE_BIN"

echo "---"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]

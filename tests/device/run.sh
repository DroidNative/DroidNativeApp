#!/usr/bin/env bash
# Runs the DroidNative on-device test suite on a phone connected over adb and
# collects the results into tests/results/<date>-<device>/.
#
# Usage: tests/device/run.sh [-f play|fdroid] [-s serial] [-t TestClass]
#                            [--no-build] [--push-rootfs] [--fix-phantom]
#
#   -f            flavour to test (default: play)
#   -s            adb serial when several devices are connected
#   -t            run one test class instead of the whole suite,
#                 e.g. -t T06VulkanWaylandTest
#   --no-build    reuse the APKs from the last build
#   --push-rootfs download the Void rootfs on this machine (cached in
#                 .cache/rootfs) and push it, saving the phone the download
#   --fix-phantom disable Android 12+'s phantom process killer via adb
#
# The app is installed, never uninstalled: the rootfs and installed packages
# survive between runs, so only the first run is slow.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FLAVOUR=play
SERIAL=""
TEST_CLASS=""
BUILD=1
PUSH_ROOTFS=0
FIX_PHANTOM=0
ROOTFS_DATE=20250202

while [ $# -gt 0 ]; do
    case "$1" in
        -f) FLAVOUR="$2"; shift 2 ;;
        -s) SERIAL="$2"; shift 2 ;;
        -t) TEST_CLASS="$2"; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --push-rootfs) PUSH_ROOTFS=1; shift ;;
        --fix-phantom) FIX_PHANTOM=1; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

case "$FLAVOUR" in
    play) PKG=app.droidnative; CAP=Play ;;
    fdroid) PKG=app.droidnative.fdroid; CAP=Fdroid ;;
    *) echo "flavour must be play or fdroid" >&2; exit 2 ;;
esac
TEST_PKG="$PKG.test"

"${ADB[@]}" get-state >/dev/null || { echo "no device connected" >&2; exit 1; }
MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')"
ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
OUT="$ROOT/tests/results/$(date +%Y%m%d-%H%M%S)-$MODEL-$FLAVOUR"
mkdir -p "$OUT"
echo "device: $MODEL abi=$ABI sdk=$SDK -> $OUT"

if [ "$BUILD" -eq 1 ]; then
    "$ROOT/gradlew" -p "$ROOT" -q "assemble${CAP}Debug" "assemble${CAP}DebugAndroidTest"
fi
APK="$ROOT/app/build/outputs/apk/$FLAVOUR/debug/app-$FLAVOUR-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/$FLAVOUR/debug/app-$FLAVOUR-debug-androidTest.apk"
"${ADB[@]}" install -r -g "$APK" >/dev/null
"${ADB[@]}" install -r -g "$TEST_APK" >/dev/null

# Permissions a user would grant through the UI
"${ADB[@]}" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
"${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Device passthrough (T11)
for perm in CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    "${ADB[@]}" shell pm grant "$PKG" android.permission.$perm 2>/dev/null || true
done

if [ "$FIX_PHANTOM" -eq 1 ] && [ "$SDK" -ge 31 ]; then
    "${ADB[@]}" shell settings put global settings_enable_monitor_phantom_procs false || true
    "${ADB[@]}" shell device_config set_sync_disabled_for_tests persistent || true
    "${ADB[@]}" shell device_config put activity_manager max_phantom_processes 2147483647 || true
fi

if [ "$PUSH_ROOTFS" -eq 1 ]; then
    case "$ABI" in
        arm64-v8a) VA=aarch64 ;;
        armeabi-v7a) VA=armv7l ;;
        x86_64) VA=x86_64 ;;
        x86) VA=i686 ;;
        *) echo "unknown abi $ABI" >&2; exit 1 ;;
    esac
    NAME="void-$VA-ROOTFS-$ROOTFS_DATE.tar.xz"
    mkdir -p "$ROOT/.cache/rootfs"
    if [ ! -f "$ROOT/.cache/rootfs/$NAME" ]; then
        curl -fL -o "$ROOT/.cache/rootfs/$NAME.part" \
            "https://repo-default.voidlinux.org/live/current/$NAME"
        mv "$ROOT/.cache/rootfs/$NAME.part" "$ROOT/.cache/rootfs/$NAME"
    fi
    DEST="/sdcard/Android/data/$PKG/files"
    "${ADB[@]}" shell mkdir -p "$DEST"
    "${ADB[@]}" push "$ROOT/.cache/rootfs/$NAME" "$DEST/$NAME" >/dev/null
fi

# Window tests need a visible, unlocked screen: wake the phone, keep it on
# while plugged in for the duration, dismiss a non-secure keyguard.
STAYON_BEFORE="$("${ADB[@]}" shell settings get global stay_on_while_plugged_in | tr -d '\r')"
restore_screen() {
    "${ADB[@]}" shell settings put global stay_on_while_plugged_in "${STAYON_BEFORE:-0}" >/dev/null 2>&1 || true
}
trap restore_screen EXIT
"${ADB[@]}" shell settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1 || true
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 1
if "${ADB[@]}" shell dumpsys window | grep -q "isKeyguardShowing=true"; then
    echo "warning: the lock screen is still showing (secure lock?). Unlock the" >&2
    echo "         phone, otherwise the window tests cannot see any output." >&2
fi

# Fresh report for this run (the app keeps its rootfs, only the report goes)
"${ADB[@]}" shell run-as "$PKG" rm -f files/test-reports/report.txt 2>/dev/null || true
"${ADB[@]}" logcat -c || true

TARGET="app.droidnative.test.DeviceSuite"
[ -n "$TEST_CLASS" ] && TARGET="app.droidnative.test.$TEST_CLASS"
echo "running $TARGET (first run downloads and installs packages, this can take a while)"
set +e
"${ADB[@]}" shell am instrument -w -r -e class "$TARGET" \
    "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"
set -e

"${ADB[@]}" logcat -d -v time -s dn-test dnc dnc-gles dn-compositor dn-env \
    AndroidRuntime TestRunner > "$OUT/logcat.txt" || true
"${ADB[@]}" exec-out run-as "$PKG" tar -cf - -C files test-reports logs 2>/dev/null \
    | tar -xf - -C "$OUT" 2>/dev/null || echo "note: could not pull report files"

PASSED="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$OUT/instrument.txt" || true)"
# Status codes: 0 ok, -1 error, -2 failure, -3 ignored, -4 assumption failed
FAILED="$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$OUT/instrument.txt" || true)"
SKIPPED="$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -[34]$' "$OUT/instrument.txt" || true)"
echo
echo "passed=$PASSED failed=$FAILED skipped=$SKIPPED"
[ -f "$OUT/test-reports/report.txt" ] && cat "$OUT/test-reports/report.txt"
echo "results: $OUT"
grep -q "^INSTRUMENTATION_CODE: -1" "$OUT/instrument.txt" || echo "warning: instrumentation did not finish cleanly"
[ "$FAILED" -eq 0 ]

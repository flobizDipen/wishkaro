#!/usr/bin/env bash
#
# Produce M7's screenshots — every screen, both themes — and pull them off the device.
#
# ## Why this is not `./gradlew connectedDebugAndroidTest`
#
# Same reason `instrumented-tests.sh` is not: **the Gradle task uninstalls the app when it
# finishes.** Not `pm clear` — a full uninstall. On 2026-11-26 that wiped the owner's handset
# mid-M4, taking the local database and the session with it. `am instrument` has no opinion
# about uninstalling anything.
#
# It also matters here for a second reason: the files this writes live in the app's own
# external files directory, and an uninstall takes that directory with it. The Gradle task
# would delete the screenshots it had just produced.
#
# ## Run this on an EMULATOR
#
# The harness composes every screen from invented fixtures and never reads the database, so it
# is safe anywhere — but there is no reason to point it at the phone that holds a real account,
# and `ANDROID_SERIAL` exists so that "which device" is a decision rather than whichever one adb
# happens to list first.
#
# Build both APKs first:
#   ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
#
# Then:
#   ANDROID_SERIAL=emulator-5554 tools/device/screenshots.sh
#
# Output lands in ./build/design-shots/.

set -euo pipefail

PKG="${WK_PKG:-in.wishkaro.app}"
TEST_PKG="$PKG.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CLASS="${WK_CLASS:-in.wishkaro.app.ui.DesignScreenshotTest}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_APK="$ROOT/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
TEST_APK="$ROOT/composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk"
OUT="${WK_SHOTS:-$ROOT/build/design-shots}"

die() { printf '\033[31mFAIL\033[0m  %s\n' "$*" >&2; exit 1; }
ok()  { printf '\033[32mok\033[0m    %s\n' "$*" >&2; }

command -v adb >/dev/null || die "adb is not on PATH"
n="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
[ "$n" = "0" ] && die "no device attached"
[ "$n" != "1" ] && [ -z "${ANDROID_SERIAL:-}" ] && die "$n devices attached; set ANDROID_SERIAL"

for apk in "$APP_APK" "$TEST_APK"; do
  [ -f "$apk" ] || die "missing $apk — run: ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest"
done

adb install -r "$APP_APK"  > /dev/null || die "could not install the app"
adb install -r "$TEST_APK" > /dev/null || die "could not install the test APK"
ok "both APKs installed over the existing ones; nothing was uninstalled"

# Clear only the directory this script owns, so a rerun cannot silently keep a stale image of
# a screen that has since been renamed or deleted. An image that is no longer produced but is
# still on disk is the quietest way for a verification document to describe something that is
# not in the build any more.
REMOTE="/sdcard/Android/data/$PKG/files/design"
adb shell rm -rf "$REMOTE" >/dev/null 2>&1 || true

ok "running $CLASS"
# `-e class` rather than running everything: the other instrumented tests are the real suite
# and have nothing to do with screenshots.
adb shell am instrument -w -r \
  -e class "$CLASS" \
  -e debug false \
  "$TEST_PKG/$RUNNER" | tee /tmp/wk-screenshots.log | grep -E "OK |FAILURES|Error|INSTRUMENTATION_STATUS: stream" || true

grep -q "OK (" /tmp/wk-screenshots.log || die "the harness did not pass — see /tmp/wk-screenshots.log"

rm -rf "$OUT"
mkdir -p "$OUT"
adb pull "$REMOTE" "$OUT" >/dev/null || die "nothing to pull from $REMOTE"
# `adb pull` of a directory creates a subdirectory named after it; flatten so the output is
# just the PNGs.
if [ -d "$OUT/design" ]; then mv "$OUT"/design/* "$OUT"/ && rmdir "$OUT/design"; fi

count="$(ls -1 "$OUT"/*.png 2>/dev/null | wc -l | tr -d ' ')"
[ "$count" = "0" ] && die "no screenshots were produced"
ok "$count screenshots in $OUT"
ls -1 "$OUT" | sed 's/^/      /'

#!/usr/bin/env bash
#
# Run the instrumented suite WITHOUT losing the device's data.
#
# THIS EXISTS BECAUSE `./gradlew :composeApp:connectedDebugAndroidTest` UNINSTALLS THE APP
# WHEN IT FINISHES. Not `pm clear` — a full uninstall. The local SQLite database goes, the
# session tokens in EncryptedSharedPreferences go, and the next launch is a first launch.
#
# On 2026-11-26 that wiped the owner's handset mid-M4: the 566 invented people M3 deliberately
# left in the live account (M3_VERIFY.md §8) vanished from the device, along with the
# session, and the app came up on the sign-in screen. Confirmed afterwards by watching
# `pm list packages | grep wishkaro` return nothing straight after a green test run.
#
# The records themselves were safe — they are on the server and a sign-in pulls them back —
# but "the test suite signs you out and empties your phone" is not something to rediscover
# once a milestone.
#
# So this installs both APKs and drives the runner directly. `am instrument` has no opinion
# about uninstalling anything.
#
# Build them first:
#   ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
#
# Then:
#   tools/device/instrumented-tests.sh                     everything
#   tools/device/instrumented-tests.sh ActivityRecreationTest     one class
#
# The Gradle task is still the right thing to use on an emulator or any device whose data
# nobody minds losing — it is simpler, and it writes the HTML report.

set -euo pipefail

PKG="${WK_PKG:-in.wishkaro.app}"
TEST_PKG="$PKG.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_APK="$ROOT/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
TEST_APK="$ROOT/composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk"

die() { printf '\033[31mFAIL\033[0m  %s\n' "$*" >&2; exit 1; }
ok()  { printf '\033[32mok\033[0m    %s\n' "$*" >&2; }

for apk in "$APP_APK" "$TEST_APK"; do
  [ -f "$apk" ] || die "missing $apk — run: ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest"
done

# -r, never a plain install: this is the whole point. Data survives a reinstall and does not
# survive an uninstall.
adb install -r "$APP_APK"  > /dev/null || die "could not install the app"
adb install -r "$TEST_APK" > /dev/null || die "could not install the test APK"
ok "both APKs installed over the existing ones; nothing was uninstalled"

# macOS ships bash 3.2, where an empty array reads as unbound under `set -u`, so the two
# cases are spelled out rather than expanding a possibly-empty array.
if [ $# -ge 1 ]; then
  ok "running only $1"
  output="$(adb shell am instrument -w -r -e class "in.wishkaro.app.ui.nav.$1" "$TEST_PKG/$RUNNER" 2>&1)"
else
  output="$(adb shell am instrument -w -r "$TEST_PKG/$RUNNER" 2>&1)"
fi
echo "$output" | sed -n 's/^INSTRUMENTATION_STATUS: stream=//p' | sed '/^$/d'

failures="$(sed -n 's/.*FAILURES!!!.*Tests run: \([0-9]*\),  Failures: \([0-9]*\).*/\2/p' <<<"$output")"
if grep -q "FAILURES!!!" <<<"$output"; then
  echo "$output" | tail -40
  die "${failures:-some} instrumented test(s) failed"
fi

grep -qE "OK \([0-9]+ test" <<<"$output" || { echo "$output" | tail -40; die "the runner did not report OK"; }
ok "$(grep -oE 'OK \([0-9]+ tests?\)' <<<"$output" | tail -1)"

# The check that matters, every time: the app is still installed and still has its data.
adb shell pm list packages | grep -q "^package:$PKG$" || die "the app is GONE — something uninstalled it"
ok "$PKG is still installed"

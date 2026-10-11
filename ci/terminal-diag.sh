#!/usr/bin/env bash
# Terminal diagnostics on the emulator (android-emulator-runner `script`; the debug and test APKs are
# already built). Runs TerminalDiagTest against the LIVE herdr and prints its METRICS ONLY (logcat tag
# TermDiag: rectangles, counts, booleans, short error messages) to the job log. The page runs under the
# narrowed diagnostic guard (interact role kept, attach/resize sent; input/keys/submit/pending-action/
# secret/take-over frames and non-GET requests dropped, see app/src/debug/assets/diag_probe.js).
#
#   ci/terminal-diag.sh
#
# Credentials: the workflow writes $RUNNER_TEMP/qa-token.json; this script pushes it and removes both.
# The repository is public: nothing is uploaded, no screenshot is taken, no `set -x`.
set -uo pipefail
cd "$(dirname "$0")/.."

# shellcheck source=ci/qa-lib.sh
source ci/qa-lib.sh
PKG=$QA_PKG
RUNNER=$QA_RUNNER
TOKEN_FILE="${RUNNER_TEMP:?RUNNER_TEMP is required}/qa-token.json"
DEVICE_TOKEN=/data/local/tmp/qa-token.json
TMP=$(mktemp -d)

cleanup() {
  adb shell rm -f "$DEVICE_TOKEN" >/dev/null 2>&1 || true
  rm -f "$TOKEN_FILE"
  rm -rf "$TMP"
}
trap cleanup EXIT

[ -s "$TOKEN_FILE" ] || { echo "FAIL: qa token file missing"; exit 1; }
adb install -r -t app/build/outputs/apk/debug/app-debug.apk || { echo "FAIL: debug apk install"; exit 1; }
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || { echo "FAIL: test apk install"; exit 1; }
adb push "$TOKEN_FILE" "$DEVICE_TOKEN" >/dev/null || { echo "FAIL: token push"; exit 1; }
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put global hide_error_dialogs 1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS

webview=$(adb shell dumpsys webviewupdate 2>/dev/null | tr -d '\r' | grep -E 'Current WebView package|versionName' | head -3 | tr '\n' ' ')
echo "webview: $webview"

adb logcat -c
timeout 900 adb shell am instrument -w -r -e class "$QA_TEST_PKG.TerminalDiagTest" "$RUNNER" >"$TMP/instr.txt" 2>&1
status=$?
echo "=== TermDiag metrics (WebView, live, narrowed guard) ==="
adb logcat -d -s TermDiag:I | grep -v '^---' | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ I TermDiag: //'
echo "=== instrumentation result (exit $status) ==="
grep -E '^OK \(|FAILURES|INSTRUMENTATION_RESULT|Error|AssertionError|Exception' "$TMP/instr.txt" | cut -c1-300 | sed -n 1,20p
# Same acceptance rule as ci/instrumented.sh: successful runner exit and exactly the expected positive test count.
expected=$(qa_expected_counts "$QA_TEST_SRC" | awk '$1 == "TerminalDiagTest" { print $2 }')
[ -n "$expected" ] || { echo "FAIL: no @Test count found for TerminalDiagTest"; exit 1; }
if reason=$(qa_check_phase TerminalDiagTest "$TMP/instr.txt" "$status" "$expected"); then
  echo "TerminalDiagTest: OK ($(qa_executed_count "$TMP/instr.txt") tests executed)"
else
  echo "FAIL: TerminalDiagTest did not pass: $reason"
  exit 1
fi

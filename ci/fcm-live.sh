#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/qa-lib.sh

uuid='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
[[ "${FCM_INSTALLATION_ID:-}" =~ $uuid ]] && [[ "${FCM_EVENT_ID:-}" =~ $uuid ]] || {
  echo 'FCM test identifiers must be UUIDs' >&2
  exit 2
}
test -s "$RUNNER_TEMP/qa-token.json"
result=$(mktemp)
cleanup() {
  adb shell rm -f /data/local/tmp/qa-token.json >/dev/null 2>&1 || true
  adb shell am force-stop "$QA_PKG" >/dev/null 2>&1 || true
  rm -f "$result"
}
trap cleanup EXIT

adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb push "$RUNNER_TEMP/qa-token.json" /data/local/tmp/qa-token.json >/dev/null
adb shell pm grant "$QA_PKG" android.permission.POST_NOTIFICATIONS

expected=$(qa_expected_counts | while read -r class count; do
  if [ "$class" = FcmLiveDeliveryTest ]; then echo "$count"; fi
done)
[[ "$expected" =~ ^[1-9][0-9]*$ ]] || { echo 'FCM live test class has no executable tests' >&2; exit 1; }
set +e
adb shell am instrument -w -r \
  -e class "$QA_TEST_PKG.FcmLiveDeliveryTest" \
  -e fcmInstallationId "$FCM_INSTALLATION_ID" \
  -e fcmEventId "$FCM_EVENT_ID" \
  "$QA_RUNNER" >"$result" 2>&1
rc=$?
set -e
# Raw instrumentation output can contain private origin/credentials on failure; never print or upload it.
if ! qa_check_phase FcmLiveDeliveryTest "$result" "$rc" "$expected"; then
  echo 'FCM LIVE FAIL: delivery or notification-tap assertion did not pass' >&2
  exit 1
fi
echo "FCM LIVE PASS: $expected test(s), real background receipt and pane tap"

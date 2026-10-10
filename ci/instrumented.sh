#!/usr/bin/env bash
# Emulator QA harness. Run as the android-emulator-runner `script` (the emulator is already booted):
#
#   ci/instrumented.sh <api-level>
#
# API 34 runs every phase, any other API only the lifecycle/inset subset (F5, F6, W1-W4) plus the onboarding phase O1.
# QA_PHASES (space separated group ids, e.g. "L1 W3") restricts a run for debugging; a full run
# leaves it empty. Credentials: the workflow writes $RUNNER_TEMP/qa-token.json, this script pushes
# it to /data/local/tmp and the trap removes both. No `set -x`, nothing secret is echoed.
set -uo pipefail
cd "$(dirname "$0")/.."

API="${1:?usage: ci/instrumented.sh <api-level>}"
PKG=com.gimso2x.herdr
RUNNER=com.gimso2x.herdr.test/androidx.test.runner.AndroidJUnitRunner
TEST_PKG=com.gimso2x.herdr.qa
OUT=qa-screens
TOKEN_FILE="${RUNNER_TEMP:?RUNNER_TEMP is required}/qa-token.json"
DEVICE_TOKEN=/data/local/tmp/qa-token.json
PHASE_TIMEOUT=480

mkdir -p "$OUT"
FAILED=()
RAN=()

# The repository is public and L1-L4 run against the owner's live herdr: their screenshots (l1.png,
# L*-fail.png), logcat dumps and fact files can show workspace labels or pane titles, so they never
# stay in $OUT (which is uploaded). The pixel gate and the plain phase outputs (L*.txt) stay.
purge_live_evidence() {
  rm -f "$OUT"/l1.png "$OUT"/L[1-4]*-fail.png "$OUT"/L[1-4]*Test-*.txt
}

cleanup() {
  adb pull /sdcard/Download/qa/. "$OUT/" >/dev/null 2>&1 || true
  purge_live_evidence
  adb shell rm -f "$DEVICE_TOKEN" >/dev/null 2>&1 || true
  rm -f "$TOKEN_FILE"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*"
  FAILED+=("$*")
}

declare -A CLASSES=(
  [L1]="L1LiveLoadTest" [L2]="L2BadTokenTest" [L3]="L3ExpiryRecoveryTest" [L4]="L4LiveDownloadTest"
  [G1]="G1GuardFixtureTest" [A1]="A1RenewalTest" [A2]="A2RenewalRejectedTest"
  [F1]="F1ExternalPopupTest" [F2]="F2RotationTest" [F3]="F3RecreateTest" [F4]="F4BackOrderTest" [F5]="F5ImeResizeTest" [F6]="F6ViewportUnitsTest"
  [C1]="C1FileChooserTest" [C2]="C2MicGrantTest" [C3]="C3MicDenyTest" [C4]="C4ClipboardTest" [C5]="C5FixtureDownloadTest"
  [R1]="R1ServedRevisionTest"
  [W1]="W1NotifyTest" [W2]="W2TapNotificationTest" [W3]="W3SeedOnTest W3SeedOffTest W3ToggleTest" [W4]="W4PermissionOffTest"
  [O1]="O1SetupToOnboardingTest O1MainStartShowsOnboardingTest O1ButtonsTest O1StartTest O1WatchNotificationTest"
  [O2]="O2PermissionRequestTest"
  [Sstore]="SStoreWriteTest SStoreReadTest" [Scookie]="SCookieWipeTest"
)
FULL_ORDER="L1 L2 L3 L4 G1 A1 A2 F1 F2 F3 F4 F5 F6 C1 C2 C3 C4 C5 R1 W1 W2 W3 W4 O1 O2 Sstore Scookie"
SUBSET_ORDER="F5 F6 W1 W2 W3 W4 O1"
if [ "$API" = "34" ]; then ALLOWED="$FULL_ORDER"; else ALLOWED="$SUBSET_ORDER"; fi

selected() {
  [[ " $ALLOWED " == *" $1 "* ]] || return 1
  [ -z "${QA_PHASES:-}" ] || [[ " $QA_PHASES " == *" $1 "* ]]
}

# One instrumentation phase: the output must contain `OK (`; the exit code alone proves nothing.
run_phase() {
  local cls="$1" out="$OUT/$1.txt"
  RAN+=("$cls")
  echo "=== phase $cls (api $API)"
  adb logcat -c
  timeout "$PHASE_TIMEOUT" adb shell am instrument -w -r -e class "$TEST_PKG.$cls" "$RUNNER" 2>&1 | tee "$out"
  # Facts the tests log on purpose (tag QaFail), kept for passing phases too.
  adb logcat -d -s QaFail 2>/dev/null | grep -v '^---' >"$OUT/$cls-facts.txt" || true
  [ -s "$OUT/$cls-facts.txt" ] || rm -f "$OUT/$cls-facts.txt"
  if grep -q '^OK (' "$out" && ! grep -q 'FAILURES!!!' "$out"; then
    echo "=== phase $cls: OK"
  else
    fail "phase $cls did not report OK"
    # Crash/diagnostic lines of the failed phase only: app tags, crashes, test failure facts.
    adb logcat -d -s QaFail HerdrWatch HerdrBoot HerdrDownload AndroidRuntime:E >"$OUT/$cls-log.txt" 2>&1 || true
    adb logcat -d -b all -t 600 '*:W' >"$OUT/$cls-log-all.txt" 2>&1 || true
    adb logcat -d -b events -s am_kill am_crash am_proc_died am_anr am_low_memory >"$OUT/$cls-events.txt" 2>&1 || true
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  fi
}

# Diagnostics for the host-orchestrated boot phases: app log tags only (no token is ever logged).
dump_boot_log() {
  adb logcat -d -s HerdrBoot HerdrWatch AndroidRuntime:E ActivityManager:W >"$OUT/W3-logcat-$1.txt" 2>&1 || true
}

wait_until() { # seconds command...
  local deadline=$((SECONDS + $1))
  shift
  until "$@"; do
    [ "$SECONDS" -ge "$deadline" ] && return 1
    sleep 1
  done
}

HOST="$OUT/W3-host.txt"
hc() { echo "W3 host-check: $*" | tee -a "$HOST"; }

service_foreground() {
  local dump
  dump=$(adb shell dumpsys activity services "$PKG" 2>/dev/null)
  grep -q 'StatusWatcherService' <<<"$dump" && grep -q 'isForeground=true' <<<"$dump"
}
service_absent() {
  ! adb shell dumpsys activity services "$PKG" 2>/dev/null | grep -q 'StatusWatcherService'
}
channel_listed() {
  local dump
  dump=$(adb shell dumpsys notification --noredact 2>/dev/null)
  grep -q "$PKG" <<<"$dump" && grep -q 'herdr_watch' <<<"$dump"
}
boot_log_has() {
  adb logcat -d -s HerdrBoot 2>/dev/null | grep -qF "$1"
}
is_root() { [ "$(adb shell id -u 2>/dev/null | tr -d '\r\n')" = "0" ]; }

# Force-stop, become root (only root may send the protected BOOT_COMPLETED), broadcast to the stopped app.
boot_broadcast() {
  adb shell am force-stop "$PKG"
  adb wait-for-device
  # adbd restarts on every root/unroot: "unable to connect for root: closed" means it is not back yet.
  root_ok() { adb root >"$OUT/adb-root.txt" 2>&1; }
  if ! wait_until 45 root_ok; then
    fail "adb root failed: $(tr '\n' ' ' <"$OUT/adb-root.txt")"
    return 1
  fi
  adb wait-for-device
  if ! wait_until 30 is_root; then
    fail "adb shell is not uid 0 after adb root"
    return 1
  fi
  hc "adb root: shell uid 0"
  adb logcat -c
  local result
  result=$(adb shell am broadcast --include-stopped-packages -a android.intent.action.BOOT_COMPLETED -p "$PKG" 2>&1)
  if grep -q 'Broadcast completed' <<<"$result"; then
    hc "BOOT_COMPLETED delivered with --include-stopped-packages to the force-stopped app"
  else
    fail "BOOT_COMPLETED broadcast was not accepted"
    return 1
  fi
}

unroot() {
  adb unroot >/dev/null 2>&1 || true
  adb wait-for-device
  wait_until 30 adb shell true >/dev/null 2>&1 || true
}

w3_group() {
  : >"$HOST"
  run_phase W3SeedOnTest
  # A real boot sends BOOT_COMPLETED with the system's temporary foreground-service allowlist; a
  # shell broadcast has none ("Background started FGS: Disallowed"). The power allowlist is the
  # documented equivalent exemption, so the FGS start path itself can still be exercised.
  adb shell cmd deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true
  if boot_broadcast; then
    if wait_until 30 boot_log_has "boot: starting"; then
      hc "logcat HerdrBoot: boot: starting"
    else
      fail "HerdrBoot did not log 'boot: starting'"
    fi
    if wait_until 30 service_foreground; then
      hc "StatusWatcherService running with isForeground=true"
    else
      fail "StatusWatcherService never reached isForeground=true after BOOT_COMPLETED"
    fi
    if wait_until 30 channel_listed; then
      hc "dumpsys notification --noredact lists channel herdr_watch for $PKG"
    else
      fail "channel herdr_watch not listed for $PKG"
    fi
    dump_boot_log on
  fi
  adb shell cmd deviceidle whitelist "-$PKG" >/dev/null 2>&1 || true
  unroot

  run_phase W3SeedOffTest
  if boot_broadcast; then
    if wait_until 30 boot_log_has "boot: alerts off, not starting"; then
      hc "logcat HerdrBoot: boot: alerts off, not starting"
      if service_absent; then
        hc "dumpsys activity services shows no StatusWatcherService after the alerts-off boot"
      else
        fail "StatusWatcherService is present after the alerts-off boot"
      fi
    else
      fail "HerdrBoot did not log 'boot: alerts off, not starting'"
    fi
  fi
  dump_boot_log off
  unroot
  adb shell am force-stop "$PKG"

  run_phase W3ToggleTest
}

group() {
  case "$1" in
    C3)
      adb shell pm revoke "$PKG" android.permission.RECORD_AUDIO
      adb shell pm clear-permission-flags "$PKG" android.permission.RECORD_AUDIO user-set user-fixed
      run_phase C3MicDenyTest
      ;;
    W3) w3_group ;;
    W4)
      adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
      run_phase W4PermissionOffTest
      adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
      ;;
    O2)
      adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
      run_phase O2PermissionRequestTest
      adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
      ;;
    Sstore)
      run_phase SStoreWriteTest
      adb shell am force-stop "$PKG"
      run_phase SStoreReadTest
      ;;
    Scookie)
      run_phase SCookieWipeTest
      local crypto manifest
      crypto=$(adb shell getprop ro.crypto.type | tr -d '\r\n')
      echo "S-cookie host-check: ro.crypto.type=$crypto" | tee -a "$OUT/S-cookie-host.txt"
      [ "$crypto" = "file" ] || fail "ro.crypto.type is '$crypto', expected file"
      manifest=$("$ANDROID_HOME"/build-tools/36.0.0/aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml 2>&1)
      if grep -Eq 'allowBackup.*=false' <<<"$manifest"; then
        echo "S-cookie host-check: allowBackup=false" | tee -a "$OUT/S-cookie-host.txt"
      else
        fail "manifest does not show allowBackup=false"
      fi
      if grep -q 'dataExtractionRules' <<<"$manifest"; then
        echo "S-cookie host-check: dataExtractionRules present" | tee -a "$OUT/S-cookie-host.txt"
      else
        fail "manifest does not show dataExtractionRules"
      fi
      ;;
    *)
      local cls
      for cls in ${CLASSES[$1]}; do run_phase "$cls"; done
      ;;
  esac
}

# --- build, install, prepare the device -----------------------------------------------------

if [ ! -s "$TOKEN_FILE" ]; then
  echo "qa-token.json was not written by the workflow"
  exit 1
fi

if ! ./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest; then
  echo "gradle install failed"
  exit 1
fi

# The install build leaves a Kotlin daemon behind; the emulator needs that memory.
pkill -f KotlinCompileDaemon >/dev/null 2>&1 || true
free -m
adb push "$TOKEN_FILE" "$DEVICE_TOKEN" >/dev/null || { echo "could not push the qa token"; exit 1; }
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# The soft keyboard must show even though the emulator reports a hardware keyboard (F4, F5).
adb shell settings put secure show_ime_with_hard_keyboard 1
# A system "isn't responding" dialog would steal window focus from the app under test.
adb shell settings put global hide_error_dialogs 1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS

# Harness warm-up (keyboard process cold start); skipped for debug runs that name phases.
if [ -z "${QA_PHASES:-}" ] || [[ " $QA_PHASES " == *" Warmup "* ]]; then run_phase ImeWarmupTest; fi

for g in $FULL_ORDER; do
  if selected "$g"; then group "$g"; fi
done

# --- verification of the collected evidence --------------------------------------------------

adb pull /sdcard/Download/qa/. "$OUT/" >/dev/null 2>&1 || true
if selected L1 && [ ! -s "$OUT/l1.png" ]; then fail "l1.png was not produced"; fi

# A missing phase file fails the run: on a full run every expected class must have an OK output.
if [ -z "${QA_PHASES:-}" ]; then
  for g in $ALLOWED; do
    for cls in ${CLASSES[$g]}; do
      if [ ! -s "$OUT/$cls.txt" ]; then
        fail "missing phase output for $cls"
      elif ! grep -q '^OK (' "$OUT/$cls.txt" && [[ " ${RAN[*]} " != *" $cls "* ]]; then
        fail "phase $cls never ran"
      fi
    done
  done
fi

echo "=== QA SUMMARY api=$API phases run: ${RAN[*]:-none}"
if [ "${#FAILED[@]}" -gt 0 ]; then
  printf 'FAILED: %s\n' "${FAILED[@]}"
  exit 1
fi
echo "=== QA SUMMARY: all phases OK"

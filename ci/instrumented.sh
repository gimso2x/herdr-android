#!/usr/bin/env bash
# Emulator QA harness. Run as the android-emulator-runner `script` (the emulator is already booted):
#
#   ci/instrumented.sh <api-level> [--tier fast|full]    (default tier: full)
#   ci/instrumented.sh --validate-dispatch               (plan job: validates the dispatch inputs, prints outputs)
#
# This script is the single source of truth for which phases run where (lists in ci/qa-lib.sh):
#   full: API 34 runs every phase (incl. live L1-L4); any other API only the lifecycle/inset subset
#         (F5, F6, W1-W4) plus the onboarding phase O1 and U1.
#   fast: the same lists minus the live groups L1-L4 (fixture-only; no credentials, no live server).
# QA_PHASES (space separated group ids, e.g. "L1 W3") restricts a run for debugging; a normal run
# leaves it empty. Every requested id must be a supported id of the tier/API, otherwise the run is
# refused before anything starts. A phase passes only with a successful runner exit and the exact number
# of executed tests (OK (N tests), N from the @Test methods of the class); zero tests never pass.
# Credentials (full tier only): the workflow writes $RUNNER_TEMP/qa-token.json, this
# script pushes it to /data/local/tmp and the trap removes both. No `set -x`, nothing secret is echoed.
# The debug build runs as com.gimso2x.herdr.debug (applicationIdSuffix), never as the production package.
set -uo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=ci/qa-lib.sh
source ci/qa-lib.sh

if [ "${1:-}" = "--validate-dispatch" ]; then qa_validate_dispatch; exit $?; fi

API="${1:?usage: ci/instrumented.sh <api-level> [--tier fast|full]}"
[[ "$API" =~ ^[0-9]+$ ]] || { echo "invalid api level: '$API'" >&2; exit 2; }
TIER=full
if [ "${2:-}" = "--tier" ]; then TIER="${3:?--tier needs fast or full}"; elif [ -n "${2:-}" ]; then echo "unknown argument: $2" >&2; exit 2; fi
case "$TIER" in fast|full) ;; *) echo "unknown tier: $TIER (fast|full)" >&2; exit 2 ;; esac
PKG=$QA_PKG
RUNNER=$QA_RUNNER
TEST_PKG=$QA_TEST_PKG
OUT=qa-screens
TOKEN_FILE="${RUNNER_TEMP:?RUNNER_TEMP is required}/qa-token.json"
DEVICE_TOKEN=/data/local/tmp/qa-token.json
PHASE_TIMEOUT=480

# Which groups this run may and does select. Validated before any device work: an unknown or unavailable id
# (a typo, a live group on the fast tier) fails here instead of producing a green run that ran nothing.
QA_PHASES=$(qa_normalize_phases)
declare -n CLASSES=QA_CLASSES
FULL_ORDER=$QA_FULL_ORDER
LIVE_GROUPS=$QA_LIVE_GROUPS
PROBE_GROUPS=$QA_PROBE_GROUPS
named=0; [ -n "$QA_PHASES" ] && named=1
ALLOWED=$(qa_allowed "$API" "$TIER" "$named")
qa_validate_phase_ids "$API" "$TIER" || exit 2

mkdir -p "$OUT"
MEM_LOG="$(mktemp)"
# Light host sampler (every 2 s, killed with the script): shows whether the host ran out of memory and
# when the emulator process (qemu=count of qemu-system processes, RSS KB) disappeared.
( while true; do
    echo "$(date +%T) $(free -m | awk 'NR==2{print "used=" $3 " avail=" $7}') qemu=$(pgrep -fc qemu-system) qemuRSS=$(ps -o rss= -C qemu-system-x86_64 -C qemu-system-x86_64-headless | tr '\n' ' ')"
    sleep 2
  done ) >"$MEM_LOG" 2>&1 &
SAMPLER=$!
EV_LOG=/dev/null
PROBE_LOG=/dev/null
# Host process output of the emulator; the workflow redirects it here only on debug runs with a gpu list.
EMULATOR_LOG=/tmp/emulator.log
FAILED=()
RAN=()

# The repository is public and the live classes (QA_LIVE_GROUPS: L1-L4 and the P1 render probe) run against the
# owner's live herdr: their screenshots (l1.png, <class>-fail.png, <class>-device.png), logcat dumps and fact
# files can show workspace labels or pane titles, so none of it is ever collected into $OUT (which is uploaded).
# Purged by the live class list, on the device before anything is pulled and on the host afterwards. The
# plain phase outputs (<class>.txt: runner result lines) stay.
purge_live_evidence() {
  local c
  timeout 30 adb shell "rm -f $QA_DEVICE_OUT/l1.png $QA_DEVICE_OUT/L[1-4]*-fail.png" >/dev/null 2>&1 || true
  for c in $(qa_live_classes); do
    timeout 30 adb shell "rm -f $QA_DEVICE_OUT/$c-* $QA_DEVICE_OUT/$c.png" >/dev/null 2>&1 || true
  done
  qa_purge_live_dir "$OUT"
}

cleanup() {
  kill "$SAMPLER" "${EV_PID:-}" "${PROBE_PID:-}" 2>/dev/null || true
  purge_live_evidence
  timeout 60 adb pull "$QA_DEVICE_OUT/." "$OUT/" >/dev/null 2>&1 || true
  qa_purge_live_dir "$OUT"
  adb shell rm -f "$DEVICE_TOKEN" >/dev/null 2>&1 || true
  rm -f "$TOKEN_FILE"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*"
  FAILED+=("$*")
}

# Debug runs that name phases (QA_PHASES) may run any group on any API, plus the render probes P0 (fixture
# page) and P1 (live page): counts of canvases/WebGL/images/WebSocket bytes, logged to tag RenderProbe.
# (The class map, the group lists and the live classification live in ci/qa-lib.sh.)
EXPECTED_COUNTS="$(qa_expected_counts "$QA_TEST_SRC")"
expected_count() { awk -v c="$1" '$1 == c { print $2 }' <<<"$EXPECTED_COUNTS"; }
OKCLS=()

selected() {
  [[ " $ALLOWED " == *" $1 "* ]] || return 1
  [ -z "${QA_PHASES:-}" ] || [[ " $QA_PHASES " == *" $1 "* ]]
}

# A wedged phase (test started, then nothing until the limit): before anything is killed, say whether the
# app's main thread or the instrumentation thread is stuck. The instrumentation runs inside the app
# process, so one SIGQUIT thread dump covers both. Printed to the job log only (never an artifact):
# thread names, states and the top three frames, no strings, no page content. Every step is bounded.
hang_diagnostics() {
  local cls="$1" pid trace
  echo "--- hang diagnostics for $cls (thread names/states and top frames only)"
  pid=$(timeout 20 adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  if [ -z "$pid" ]; then
    echo "app process is gone (pidof empty): the app died, it did not hang"
    timeout 30 adb logcat -d -b events -s am_proc_died am_kill am_crash am_anr 2>/dev/null | tail -n 5 | cut -c1-160
    return 0
  fi
  echo "app pid present"
  timeout 30 adb shell dumpsys activity processes 2>/dev/null | tr -d '\r' \
    | grep -E "ProcessRecord\{.*$PKG|curProcState=|isForeground|ANR|notResponding" | cut -c1-160 | head -n 8
  timeout 30 adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | grep -o -E 'ResumedActivity: ActivityRecord\{[^ ]+ u0 [^ ]+' | sed -E 's/.* u0 //' | head -n 2
  # SIGQUIT needs root for an app uid; the ART thread dump lands in /data/anr (readable as root).
  if wait_until 45 adb root >/dev/null 2>&1 && wait_until 30 is_root; then
    timeout 20 adb shell kill -3 "$pid" >/dev/null 2>&1 || true
    anr_new() { timeout 20 adb shell "ls -t /data/anr/anr_* 2>/dev/null | head -n 1" 2>/dev/null | tr -d '\r' | grep -q anr_; }
    wait_until 20 anr_new || echo "no /data/anr trace file appeared"
    trace=$(timeout 20 adb shell "ls -t /data/anr/anr_* 2>/dev/null | head -n 1" 2>/dev/null | tr -d '\r')
    if [ -n "$trace" ]; then
      timeout 60 adb shell cat "$trace" 2>/dev/null | tr -d '\r' | awk '
        /^"/ { print; k = 0; next }
        /^  (at |native: )/ { if (k < 3) print; k++ }' | cut -c1-200 | head -n 400
    fi
    unroot
  else
    echo "adb root unavailable: no thread dump taken"
  fi
  echo "--- end hang diagnostics"
}

# Host-side facts for a vanished emulator (job log only: process names, sizes and kernel kill lines).
emulator_death_facts() {
  echo "--- emulator death facts"
  echo "host memory samples (time used/avail MB, qemu process count, RSS KB): last 6 with the emulator alive, then the minimum avail of the run:"
  grep -E ' qemu=[1-9]' "$MEM_LOG" 2>/dev/null | tail -n 6
  awk '{ split($3, a, "="); if (min == "" || a[2] + 0 < min) min = a[2] + 0 } END { print "min avail MB over the run: " min }' "$MEM_LOG" 2>/dev/null
  free -m | sed -n 1,3p
  local dm cg f
  dm=$(sudo -n dmesg 2>&1 | tail -n 600)
  echo "dmesg readable lines: $(wc -l <<<"$dm"); kill/crash lines:"
  grep -iE "out of memory|oom|killed process|qemu|emulator|segfault|general protection|invalid opcode|trap" <<<"$dm" | tail -n 8 | cut -c1-200
  cg="/sys/fs/cgroup$(cut -d: -f3 /proc/self/cgroup | head -n 1)"
  for f in memory.max memory.peak memory.events; do echo "cgroup $f: $(tr '\n' ' ' <"$cg/$f" 2>/dev/null)"; done
  echo "crashing thread (gdb, QA_GDB runs): signal line, fault address and top frames as 'frame function from module' only:"
  grep -E 'received signal|^\$[0-9]+ = ' "$GDB_LOG" 2>/dev/null | cut -c1-160 | tail -n 3
  awk '/^#[0-9]+/ { fn = ($2 ~ /^0x/ && $3 == "in") ? $4 : $2; lib = ""; if (match($0, / from [^ ]+$/)) lib = substr($0, RSTART, RLENGTH); sub(/\(.*/, "", fn); print $1, fn lib }' "$GDB_LOG" 2>/dev/null | head -n 14 | cut -c1-200
  ls -1 /tmp/android-runner 2>/dev/null | head -n 5
  echo "emulator stderr/-verbose log, last 40 lines (host process output, 200 cols; empty without -verbose redirect):"
  tail -n 40 "$EMULATOR_LOG" 2>/dev/null | cut -c1-200
  echo "render probe lines logged before the death:"
  cut -c1-700 "$PROBE_LOG" 2>/dev/null | tail -n 12
  echo "crashing thread top frames (module!function only):"
  python3 ci/crash_frames.py 2>&1 | cut -c1-200 | head -n 24
  ls -1 "$HOME/.android" 2>/dev/null | grep -iE "crash|hs_err|emu-" | head -n 5
  echo "last guest events/crash lines (process-level buffers, 160 cols):"
  tail -n 30 "$EV_LOG" 2>/dev/null | cut -c1-160
  ls -1R /tmp/android-runner 2>/dev/null | head -n 12
  echo "--- end emulator death facts"
}

# Render probe counts (P0/P1) from the host-side stream: they survive an emulator death.
PROBE_SEEN=0
print_probe_lines() {
  local total
  sleep 2
  total=$(wc -l <"$PROBE_LOG")
  echo "--- render probe counts (canvases, contexts, image pixels, websocket bytes; no page content)"
  tail -n +$((PROBE_SEEN + 1)) "$PROBE_LOG" 2>/dev/null | cut -c1-900
  PROBE_SEEN=$total
  echo "--- end render probe counts"
}

# One instrumentation phase: the output must contain `OK (`; the exit code alone proves nothing.
run_phase() {
  local cls="$1" out="$OUT/$1.txt"
  RAN+=("$cls")
  echo "=== phase $cls (api $API)"
  # A dead emulator makes every later adb call wait forever (one run idled 40 min on "waiting for device"):
  # say so once and stop instead.
  if [ "$(timeout 30 adb get-state 2>/dev/null | tr -d '\r\n')" != "device" ]; then
    echo "FATAL: the emulator is gone before phase $cls (adb get-state != device); qemu processes: $(pgrep -c -f qemu-system || true)"
    emulator_death_facts
    FAILED+=("emulator lost before $cls")
    echo "=== QA SUMMARY: aborted"
    exit 1
  fi
  # A phase must start from a clean screen: an earlier phase can leave the notification shade open
  # (it then covers every later UI scenario), so close it and return to the launcher first.
  timeout 20 adb shell cmd statusbar collapse >/dev/null 2>&1 || true
  timeout 20 adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  adb logcat -c
  # -k: a wedged adb client ignores SIGTERM; without it one stuck phase held the job until the 40 min cap.
  timeout -k 15 "$PHASE_TIMEOUT" adb shell am instrument -w -r -e class "$TEST_PKG.$cls" "$RUNNER" 2>&1 | tee "$out"
  local rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
    echo "phase $cls hit the ${PHASE_TIMEOUT}s limit (rc=$rc); device state: $(timeout 20 adb get-state 2>&1 | tr -d '\r\n')"
    timeout 20 adb devices | tee -a "$out"
    hang_diagnostics "$cls"
  fi
  local live=0 expected reason
  qa_is_live_class "$cls" && live=1
  # Facts the tests log on purpose (tag QaFail), kept for passing fixture phases too. Never for a live class:
  # they carry page URLs and view state of the owner's server.
  if [ "$live" -eq 0 ]; then
    timeout 60 adb logcat -d -s QaFail 2>/dev/null | grep -v '^---' >"$OUT/$cls-facts.txt" || true
    [ -s "$OUT/$cls-facts.txt" ] || rm -f "$OUT/$cls-facts.txt"
  fi
  expected=$(expected_count "$cls")
  if [ -z "$expected" ]; then
    fail "phase $cls: no @Test count found in $QA_TEST_SRC (class missing from the instrumented sources)"
  elif reason=$(qa_check_phase "$cls" "$out" "$rc" "$expected"); then
    echo "=== phase $cls: OK ($(qa_executed_count "$out") tests executed)"
    OKCLS+=("$cls")
    case "$cls" in P*) print_probe_lines ;; esac
  else
    fail "phase $cls did not pass: $reason"
    case "$cls" in P*) print_probe_lines ;; esac
    if [ "$live" -eq 0 ]; then
      # Not a live phase: window focus, display and rotation settings and a screenshot are safe to keep.
      echo "--- device state after $cls"
      timeout 30 adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -E "mCurrentFocus|mFocusedApp|mInputMethodWindow|isKeyguardShowing|mShowingDream" | cut -c1-200 | head -n 6
      timeout 20 adb shell wm size 2>/dev/null | tr -d '\r'
      timeout 20 adb shell wm density 2>/dev/null | tr -d '\r'
      echo "rotation: user=$(timeout 20 adb shell settings get system user_rotation 2>/dev/null | tr -d '\r') auto=$(timeout 20 adb shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r') font=$(timeout 20 adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"
      timeout 20 adb shell mkdir -p "$QA_DEVICE_OUT" >/dev/null 2>&1 || true
      timeout 30 adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -E "topResumedActivity|mResumedActivity" | cut -c1-200 | head -n 3
      timeout 30 adb shell screencap -p "$QA_DEVICE_OUT/$cls-device.png" >/dev/null 2>&1 || true
      echo "--- end device state"
      # Crash/diagnostic lines of the failed phase only: app tags, crashes, test failure facts. The all-buffer
      # dump leaves out the WebView's own tags (chromium, cr_*): page console output is page content.
      timeout 60 adb logcat -d -s QaFail HerdrWatch HerdrBoot HerdrDownload AndroidRuntime:E >"$OUT/$cls-log.txt" 2>&1 || true
      timeout 60 adb logcat -d -b all -t 600 '*:W' 2>&1 | grep -v -E '^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] (chromium|cr_[A-Za-z0-9_]*) *:' >"$OUT/$cls-log-all.txt" || true
      timeout 60 adb logcat -d -b events -s am_kill am_crash am_proc_died am_anr am_low_memory >"$OUT/$cls-events.txt" 2>&1 || true
    else
      echo "(live phase $cls: no screenshot, logcat or facts are collected)"
    fi
    timeout 30 adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
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

# The fast tier is fixture-only and never receives credentials; only the full tier needs the token file.
if [ "$TIER" = "full" ] && [ ! -s "$TOKEN_FILE" ]; then
  echo "qa-token.json was not written by the workflow"
  exit 1
fi

# The workflow downloads the APKs the build job produced; no second Gradle build on the emulator host.
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  if ! adb install -r -t "$apk"; then
    echo "adb install failed for $apk"
    exit 1
  fi
done
free -m
if [ "$TIER" = "full" ]; then
  adb push "$TOKEN_FILE" "$DEVICE_TOKEN" >/dev/null || { echo "could not push the qa token"; exit 1; }
fi
# Process-level event/crash buffers only (no page text), streamed to the host so they survive an emulator death.
EV_LOG="$(mktemp)"
adb logcat -b events,crash -v time >"$EV_LOG" 2>&1 &
EV_PID=$!
GDB_LOG=/tmp/qemu-gdb.log
if [ -n "${QA_GDB:-}" ]; then
  # Debug only: attach gdb to the emulator process, let it run, and on the fatal signal print the faulting
  # thread's top frames (names only; the dump itself is never kept or printed).
  qpid=$(pgrep -fn qemu-system-x86_64 || true)
  if [ -n "$qpid" ]; then
    sudo -n env DEBUGINFOD_URLS= gdb -p "$qpid" -batch -nx \
      -ex 'set pagination off' -ex 'set confirm off' \
      -ex 'handle all nostop noprint pass' \
      -ex 'handle SIGSEGV SIGABRT SIGBUS SIGILL SIGFPE stop print' \
      -ex continue -ex 'p $_siginfo._sifields._sigfault.si_addr' -ex 'bt 14' >"$GDB_LOG" 2>&1 &
    # The emulator is frozen while gdb loads symbols: do not start any phase before it runs again.
    wait_until 180 grep -q 'Continuing' "$GDB_LOG" || echo "gdb did not reach 'Continuing' in time"
    echo "gdb attached to emulator pid $qpid"
  else
    echo "gdb: no emulator pid found"
  fi
fi
PROBE_LOG="$(mktemp)"
adb logcat -s RenderProbe -v raw >"$PROBE_LOG" 2>&1 &
PROBE_PID=$!
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# The soft keyboard must show even though the emulator reports a hardware keyboard (F4, F5).
adb shell settings put secure show_ime_with_hard_keyboard 1
# A system "isn't responding" dialog would steal window focus from the app under test.
adb shell settings put global hide_error_dialogs 1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS

# Harness warm-up (keyboard process cold start); skipped for debug runs that name phases.
if [ -z "${QA_PHASES:-}" ] || [[ " $QA_PHASES " == *" Warmup "* ]]; then run_phase ImeWarmupTest; fi

for g in $PROBE_GROUPS $FULL_ORDER; do
  if selected "$g"; then group "$g"; fi
done

# A debug run can end on the dying phase: report how the emulator went away.
if [ "$(timeout 30 adb get-state 2>/dev/null | tr -d '\r\n')" != "device" ]; then
  echo "FATAL: the emulator is gone at the end of the run"
  emulator_death_facts
  FAILED+=("emulator lost during the run")
fi

# --- verification of the collected evidence --------------------------------------------------

# The L1 pixel gate: the screenshot must exist on the device. It is live-server evidence, so it is removed
# there before anything is pulled and never reaches $OUT.
if selected L1 && ! timeout 30 adb shell "[ -s $QA_DEVICE_OUT/l1.png ]" >/dev/null 2>&1; then fail "l1.png was not produced"; fi
purge_live_evidence
timeout 60 adb pull "$QA_DEVICE_OUT/." "$OUT/" >/dev/null 2>&1 || true
qa_purge_live_dir "$OUT"

# Nothing may pass by running nothing: every selected group (all groups of the tier/API on a normal run, exactly
# the named ones on a debug run) must have run each of its classes with a passing, non-empty result.
WANT=()
if [ -z "${QA_PHASES:-}" ] || [[ " $QA_PHASES " == *" Warmup "* ]]; then WANT+=(ImeWarmupTest); fi
for g in $PROBE_GROUPS $FULL_ORDER; do
  if selected "$g"; then WANT+=(${CLASSES[$g]}); fi
done
if [ "${#RAN[@]}" -eq 0 ]; then fail "no phase ran"; fi
if [ "${#WANT[@]}" -eq 0 ]; then fail "the request selected no test class"; fi
for cls in "${WANT[@]}"; do
  if [[ " ${OKCLS[*]:-} " != *" $cls "* ]]; then
    fail "class $cls has no passing result"
  elif [ ! -s "$OUT/$cls.txt" ]; then
    fail "missing phase output for $cls"
  fi
done

echo "=== QA SUMMARY api=$API phases run: ${RAN[*]:-none}; classes passed: ${#OKCLS[@]} of ${#WANT[@]} expected"
if [ "${#FAILED[@]}" -gt 0 ]; then
  printf 'FAILED: %s\n' "${FAILED[@]}"
  exit 1
fi
echo "=== QA SUMMARY: all phases OK"

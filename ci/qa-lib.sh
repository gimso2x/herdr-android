#!/usr/bin/env bash
# Shared definitions and pure helpers of the QA harness. Sourced by ci/instrumented.sh,
# ci/terminal-diag.sh, the workflow's purge step and ci/selftest.sh. Nothing here talks to adb.

# The debug build is installed as <applicationId>.debug (app/build.gradle applicationIdSuffix), so it can never
# replace the production app; its test APK is <applicationId>.debug.test. The test classes keep their package.
QA_PKG=com.gimso2x.herdr.debug
QA_RUNNER=com.gimso2x.herdr.debug.test/androidx.test.runner.AndroidJUnitRunner
QA_TEST_PKG=com.gimso2x.herdr.qa
QA_TEST_SRC=app/src/androidTest/java/com/gimso2x/herdr/qa

declare -gA QA_CLASSES=(
  [L1]="L1LiveLoadTest" [L2]="L2BadTokenTest" [L3]="L3ExpiryRecoveryTest" [L4]="L4LiveDownloadTest"
  [G1]="G1GuardFixtureTest" [A1]="A1RenewalTest" [A2]="A2RenewalRejectedTest"
  [F1]="F1ExternalPopupTest" [F2]="F2RotationTest" [F3]="F3RecreateTest" [F4]="F4BackOrderTest" [F5]="F5ImeResizeTest" [F6]="F6ViewportUnitsTest"
  [C1]="C1FileChooserTest" [C2]="C2MicGrantTest" [C3]="C3MicDenyTest" [C4]="C4ClipboardTest" [C5]="C5FixtureDownloadTest"
  [R1]="R1ServedRevisionTest"
  [W1]="W1NotifyTest" [W2]="W2TapNotificationTest" [W3]="W3ToggleTest PushReconciliationTest" [W4]="W4PermissionOffTest"
  [O1]="O1SetupToOnboardingTest O1MainStartShowsOnboardingTest O1ButtonsTest O1StartTest O1WatchNotificationTest"
  [O2]="O2PermissionRequestTest"
  [U1]="U1UpgradePathTest U2SettingsChangeTest"
  [Sstore]="SStoreWriteTest SStoreReadTest" [Scookie]="SCookieWipeTest"
  [S]="S1MainFrameRetryTest S2PendingPaneSetupTest S3ServerChangeRecoveryTest S4DownloadNamesTest S4DownloadRedirectTest S5SignOutWipeTest S6AlertSwitchSemanticsTest"
  [P0]="P0RenderProbeFixtureTest" [P1]="P1RenderProbeLiveTest"
)
QA_FULL_ORDER="L1 L2 L3 L4 G1 A1 A2 F1 F2 F3 F4 F5 F6 C1 C2 C3 C4 C5 R1 W1 W2 W3 W4 O1 O2 U1 Sstore Scookie S"
QA_SUBSET_ORDER="F5 F6 W1 W2 W3 W4 O1 U1"
# The single classification of "runs against the owner's live herdr": phase selection (fast tier drops them)
# and evidence collection (nothing of them is ever collected or uploaded) both read this list.
QA_LIVE_GROUPS="L1 L2 L3 L4 P1"
# Debug runs that name phases may also run the render probes P0 (fixture page) and P1 (live page).
QA_PROBE_GROUPS="P0 P1"
QA_DEVICE_OUT=/sdcard/Download/qa

qa_live_classes() {
  local g
  for g in $QA_LIVE_GROUPS; do printf '%s ' ${QA_CLASSES[$g]}; done
  echo
}

qa_is_live_class() {
  local c
  for c in $(qa_live_classes); do [ "$c" = "$1" ] && return 0; done
  return 1
}

# Deletes everything the live classes could have left in a host directory: the L1 pixel gate image, every
# <class>-* file (failure screenshots, facts, logcat, events) and the legacy L1-L4 name patterns. The plain
# phase output <class>.txt (am instrument result lines) stays.
qa_purge_live_dir() {
  local dir=$1 c
  [ -d "$dir" ] || return 0
  rm -f "$dir"/l1.png "$dir"/L[1-4]*-fail.png "$dir"/L[1-4]*Test-*.txt
  for c in $(qa_live_classes); do rm -f "$dir/$c"-* "$dir/$c".png; done
}

# Allowed phase groups for an API level and tier; $3 = 1 when the run names its phases (QA_PHASES).
qa_allowed() {
  local api=$1 tier=$2 named=$3 list g out=""
  if [ "$api" = "34" ]; then list=$QA_FULL_ORDER; else list=$QA_SUBSET_ORDER; fi
  if [ "$tier" = "fast" ]; then
    for g in $list; do [[ " $QA_LIVE_GROUPS " == *" $g "* ]] || out+="$g "; done
    list="${out% }"
  fi
  if [ "$tier" = "full" ] && [ "$named" = "1" ]; then list="$QA_FULL_ORDER $QA_PROBE_GROUPS"; fi
  echo "$list"
}

# QA_PHASES with whitespace squeezed ("" when blank).
qa_normalize_phases() {
  local -a t=()
  read -r -a t <<<"${QA_PHASES:-}"
  echo "${t[*]:-}"
}

# Every requested phase id must be a supported id of the tier/API (or Warmup): a typo must not turn into a
# green run that executed nothing. Prints the problem and returns 1.
qa_validate_phase_ids() {
  local api=$1 tier=$2 phases allowed tok named=0
  phases=$(qa_normalize_phases)
  [ -z "$phases" ] && return 0
  named=1
  allowed=$(qa_allowed "$api" "$tier" "$named")
  for tok in $phases; do
    [[ "$tok" =~ ^[A-Za-z0-9]+$ ]] || { echo "invalid phase id: '$tok'" >&2; return 1; }
    [ "$tok" = "Warmup" ] && continue
    [[ " $allowed " == *" $tok "* ]] || { echo "unknown or unavailable phase id for api $api, tier $tier: '$tok' (available: $allowed Warmup)" >&2; return 1; }
  done
  # Warmup alone selects no group and would run only the keyboard warm-up.
  local groups=0
  for tok in $phases; do [ "$tok" = "Warmup" ] || groups=1; done
  [ "$groups" -eq 1 ] || { echo "phases name only Warmup: no test group selected" >&2; return 1; }
  return 0
}

# Dispatch inputs (workflow_dispatch free text) -> validated workflow outputs on stdout (key=value lines for
# $GITHUB_OUTPUT). Everything that later reaches a shell through `script:` / `emulator-options:` is checked here
# against a strict shape or allow-list; the workflow only ever substitutes these validated values.
# Env: INPUT_APIS INPUT_GPUS INPUT_EMU_BUILD QA_PHASES (all optional), TIER.
qa_validate_dispatch() {
  local apis gpus emu verbose=false api
  apis=${INPUT_APIS:-}; [ -n "$apis" ] || apis='[34, 35]'
  gpus=${INPUT_GPUS:-}; [ -n "$gpus" ] || gpus='["auto"]'
  emu=${INPUT_EMU_BUILD:-}
  apis=$(jq -c 'if type == "array" and length > 0 and length <= 4 and all(.[]; type == "number" and . == floor and . >= 21 and . <= 99) then . else error("apis must be a JSON list of integer API levels") end' <<<"$apis") \
    || { echo "invalid apis input: expected a JSON list of integer API levels, e.g. [34, 35]" >&2; return 1; }
  gpus=$(jq -c 'if type == "array" and length > 0 and length <= 4 and all(.[]; type == "string" and (. as $x | ["auto","swiftshader_indirect","swangle_indirect","angle_indirect","host","guest"] | index($x) != null)) then . else error("gpus") end' <<<"$gpus") \
    || { echo "invalid gpus input: expected a JSON list of auto|swiftshader_indirect|swangle_indirect|angle_indirect|host|guest" >&2; return 1; }
  [[ "$emu" =~ ^[0-9]*$ ]] || { echo "invalid emu-build input: digits only" >&2; return 1; }
  if [ -n "${INPUT_GPUS:-}" ]; then verbose=true; fi
  if [ "${TIER:-full}" = "full" ]; then
    for api in $(jq -r '.[]' <<<"$apis"); do
      qa_validate_phase_ids "$api" full || return 1
    done
  fi
  echo "apis=$apis"
  echo "gpus=$gpus"
  echo "emu_build=$emu"
  echo "verbose=$verbose"
  # Phases only exist (and are only validated) for a full run; never emit unvalidated text as an output.
  if [ "${TIER:-full}" = "full" ]; then echo "phases=$(qa_normalize_phases)"; else echo "phases="; fi
}

# Source-derived expected test count per class ("<Class> <n>" lines): the @Test methods of every top-level
# class in the instrumented sources (minus @Ignore'd ones), so a removed, renamed or ignored method shows up as a
# count mismatch instead of silently shrinking coverage.
qa_expected_counts() {
  local dir=${1:-$QA_TEST_SRC}
  awk '
    /^(open |abstract |private |internal )?class [A-Za-z0-9_]+/ {
      match($0, /class [A-Za-z0-9_]+/); cls = substr($0, RSTART + 6, RLENGTH - 6); seen[cls] = 1
    }
    /^[[:space:]]*@Test([[:space:](]|$)/ { n[cls]++ }
    /^[[:space:]]*@Ignore([[:space:](]|$)/ { ign[cls]++ }
    END { for (c in n) print c, n[c] - ign[c] }
  ' "$dir"/*.kt | sort
}

# Executed-test count of an `am instrument` result file: N of the `OK (N test[s])` line, 0 when there is none.
qa_executed_count() {
  local n
  n=$(sed -n -E 's/^OK \(([0-9]+) tests?\).*/\1/p' "$1" 2>/dev/null | sed -n 1p)
  echo "${n:-0}"
}

# A phase passes only with a successful runner exit, no FAILURES!!!, a positive executed count and (when an
# expected count is given) exactly that many executed tests. Prints the reason on failure.
# usage: qa_check_phase <class> <result-file> <runner-exit-status> [expected-count]
qa_check_phase() {
  local cls=$1 file=$2 rc=$3 expected=${4:-} n
  if [ "$rc" -ne 0 ]; then echo "runner exited with status $rc"; return 1; fi
  if grep -q 'FAILURES!!!' "$file" 2>/dev/null; then echo "the runner reported FAILURES"; return 1; fi
  n=$(qa_executed_count "$file")
  if [ "$n" -le 0 ]; then echo "no 'OK (N tests)' line with N > 0 (executed=$n)"; return 1; fi
  if [ -n "$expected" ] && [ "$n" -ne "$expected" ]; then echo "executed $n tests, expected $expected for $cls"; return 1; fi
  return 0
}

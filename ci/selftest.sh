#!/usr/bin/env bash
# Self-test of the CI harness helpers (no emulator, no adb, no secrets): result acceptance, phase-id and
# dispatch-input validation, the live-evidence purge, the expected test counts and the secret scan.
#
#   ci/selftest.sh
set -uo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=ci/qa-lib.sh
source ci/qa-lib.sh

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
check() { # description exit-status (0 = the expectation held)
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi
}
yes_() { "$@" >/dev/null 2>&1; echo $?; }       # exit status of a command expected to succeed
no_() { "$@" >/dev/null 2>&1; [ $? -ne 0 ]; echo $?; } # 0 when the command failed

# --- phase result acceptance ----------------------------------------------------------------
printf 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\n\nTime: 1.2\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n' >"$T/ok1.txt"
printf 'Time: 3\n\nOK (3 tests)\n' >"$T/ok3.txt"
printf 'Time: 0.01\n\nOK (0 tests)\n' >"$T/zero.txt"
printf 'Time: 1\n\nFAILURES!!!\nTests run: 1,  Failures: 1\n' >"$T/fail.txt"
printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n' >"$T/none.txt"
check "executed count of 'OK (1 test)' is 1" "$([ "$(qa_executed_count "$T/ok1.txt")" = 1 ]; echo $?)"
check "executed count of 'OK (3 tests)' is 3" "$([ "$(qa_executed_count "$T/ok3.txt")" = 3 ]; echo $?)"
check "executed count of 'OK (0 tests)' is 0" "$([ "$(qa_executed_count "$T/zero.txt")" = 0 ]; echo $?)"
check "accepted: OK (1 test), exit 0, expected 1" "$(yes_ qa_check_phase X "$T/ok1.txt" 0 1)"
check "rejected: OK (0 tests)" "$(no_ qa_check_phase X "$T/zero.txt" 0 1)"
check "rejected: OK (0 tests) even without an expected count" "$(no_ qa_check_phase X "$T/zero.txt" 0)"
check "rejected: FAILURES!!!" "$(no_ qa_check_phase X "$T/fail.txt" 0 1)"
check "rejected: no result line at all (crash)" "$(no_ qa_check_phase X "$T/none.txt" 0 1)"
check "rejected: OK but runner exit status 1" "$(no_ qa_check_phase X "$T/ok1.txt" 1 1)"
check "rejected: OK but runner exit status 124 (timeout)" "$(no_ qa_check_phase X "$T/ok1.txt" 124 1)"
check "rejected: 1 test executed, 3 expected" "$(no_ qa_check_phase X "$T/ok1.txt" 0 3)"
check "rejected: missing result file" "$(no_ qa_check_phase X "$T/absent.txt" 0 1)"

# --- expected per-class counts from the real sources ---------------------------------------
counts=$(qa_expected_counts "$QA_TEST_SRC")
check "expected counts: U2SettingsChangeTest has 3 tests" "$(grep -qx 'U2SettingsChangeTest 3' <<<"$counts"; echo $?)"
missing=0
for g in "${!QA_CLASSES[@]}"; do
  case "$g" in S) [ -f "$QA_TEST_SRC/ShellFixTests.kt" ] || continue ;; esac
  for c in ${QA_CLASSES[$g]}; do grep -q "^$c [1-9]" <<<"$counts" || { echo "  class without @Test count: $c"; missing=1; }; done
done
check "every class of every group has a positive @Test count in the sources" "$missing"
check "ImeWarmupTest and TerminalDiagTest have counts" "$(grep -q '^ImeWarmupTest [1-9]' <<<"$counts" && grep -q '^TerminalDiagTest [1-9]' <<<"$counts"; echo $?)"

# --- phase ids -------------------------------------------------------------------------------
QA_PHASES="G1 W3" check "known ids accepted on api 34 full" "$(QA_PHASES="G1 W3" yes_ qa_validate_phase_ids 34 full)"
check "unknown id rejected" "$(QA_PHASES="ZZ9" no_ qa_validate_phase_ids 34 full)"
check "one unknown id among known ones rejected" "$(QA_PHASES="G1 ZZ9" no_ qa_validate_phase_ids 34 full)"
check "shell metacharacters rejected" "$(QA_PHASES='G1;id' no_ qa_validate_phase_ids 34 full)"
check "Warmup alone rejected (selects no group)" "$(QA_PHASES="Warmup" no_ qa_validate_phase_ids 34 full)"
check "Warmup plus a group accepted" "$(QA_PHASES="Warmup G1" yes_ qa_validate_phase_ids 34 full)"
check "live group rejected on the fast tier" "$(QA_PHASES="L1" no_ qa_validate_phase_ids 34 fast)"
check "fixture group accepted on the fast tier" "$(QA_PHASES="G1" yes_ qa_validate_phase_ids 34 fast)"
check "fast tier on api 35 only has the subset (G1 rejected)" "$(QA_PHASES="G1" no_ qa_validate_phase_ids 35 fast)"
check "blank QA_PHASES is a normal run" "$(QA_PHASES="   " yes_ qa_validate_phase_ids 34 full)"
check "probe ids need a named full run" "$(QA_PHASES="P1" yes_ qa_validate_phase_ids 34 full)"

# --- dispatch inputs -------------------------------------------------------------------------
check "defaults validate" "$(TIER=full yes_ qa_validate_dispatch)"
out=$(TIER=full qa_validate_dispatch); check "defaults produce the default matrix" "$(grep -qx 'apis=\[34,35\]' <<<"$out"; echo $?)"
check "apis with a shell payload rejected" "$(INPUT_APIS='["34; id"]' no_ qa_validate_dispatch)"
check "apis that is not a list rejected" "$(INPUT_APIS='34' no_ qa_validate_dispatch)"
check "gpus outside the allow-list rejected" "$(INPUT_GPUS='["swangle_indirect; id"]' no_ qa_validate_dispatch)"
out=$(INPUT_GPUS='["swiftshader_indirect"]' TIER=full qa_validate_dispatch); check "allowed gpus accepted and turn verbose on" "$(grep -qx 'verbose=true' <<<"$out"; echo $?)"
check "emu-build must be digits" "$(INPUT_EMU_BUILD='123 -x' no_ qa_validate_dispatch)"
check "unknown phases fail the dispatch validation" "$(QA_PHASES='NOPE' TIER=full no_ qa_validate_dispatch)"

# --- live evidence purge ---------------------------------------------------------------------
mkdir -p "$T/out"
for f in l1.png L1LiveLoadTest.txt L1LiveLoadTest-fail.png L1LiveLoadTest-facts.txt L2BadTokenTest-log-all.txt \
  L4LiveDownloadTest-device.png P1RenderProbeLiveTest.txt P1RenderProbeLiveTest-log-all.txt \
  P1RenderProbeLiveTest-facts.txt P1RenderProbeLiveTest-device.png P1RenderProbeLiveTest-fail.png \
  G1GuardFixtureTest-fail.png G1GuardFixtureTest.txt P0RenderProbeFixtureTest-facts.txt W3-host.txt; do : >"$T/out/$f"; done
qa_purge_live_dir "$T/out"
left=$(cd "$T/out" && ls | tr '\n' ' ')
check "purge keeps exactly the plain phase outputs and the fixture evidence" "$([ "$left" = "G1GuardFixtureTest-fail.png G1GuardFixtureTest.txt L1LiveLoadTest.txt P0RenderProbeFixtureTest-facts.txt P1RenderProbeLiveTest.txt W3-host.txt " ]; echo $?)"
check "P1RenderProbeLiveTest is classified live" "$(qa_is_live_class P1RenderProbeLiveTest; echo $?)"
check "G1GuardFixtureTest is not live" "$(! qa_is_live_class G1GuardFixtureTest; echo $?)"

# --- secret scan -----------------------------------------------------------------------------
export CF_ID=idvalue-0123456789 CF_SECRET=secretvalue-abcdef BASE_URL=https://herdr.private.example/
mkdir -p "$T/clean" "$T/dirty1" "$T/dirty2" "$T/dirty3" "$T/dirty4" "$T/dirty5"
echo 'phase ok, nothing here' >"$T/clean/a.txt"
echo "log $CF_SECRET" >"$T/dirty1/a.txt"
echo "log $CF_ID" >"$T/dirty2/a.txt"
echo "log herdr.private.example" >"$T/dirty3/a.txt"
echo 'Cookie: CF_Authorization=abc' >"$T/dirty4/a.txt"
echo 'token eyJhbGciOiJIUzI1NiJ9.payload' >"$T/dirty5/a.txt"
check "scan passes on a clean directory" "$(yes_ ci/secret-scan.sh "$T/clean")"
check "scan passes when a path does not exist" "$(yes_ ci/secret-scan.sh "$T/clean" "$T/nowhere")"
for d in dirty1 dirty2 dirty3 dirty4 dirty5; do check "scan fails: $d" "$(no_ ci/secret-scan.sh "$T/clean" "$T/$d")"; done
check "scan refuses to run without the secrets (exit 2)" "$(env -u CF_SECRET ci/secret-scan.sh "$T/clean" >/dev/null 2>&1; [ $? -eq 2 ]; echo $?)"
mkdir -p "$T/apkdir" && (cd "$T/apkdir" && echo "x $CF_SECRET" >f.txt && zip -q -r ../bad.apk f.txt 2>/dev/null)
if [ -f "$T/bad.apk" ]; then check "scan fails on a secret inside an APK" "$(no_ ci/secret-scan.sh --apk "$T/bad.apk" "$T/clean")"; fi

if [ "$fails" -ne 0 ]; then echo "ci selftest: $fails check(s) FAILED"; exit 1; fi
echo "ci selftest: all checks passed"

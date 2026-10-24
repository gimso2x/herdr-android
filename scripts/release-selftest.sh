#!/usr/bin/env bash
# Self-test of scripts/release.sh. Exported shell functions shadow git, gh, sed -i and sleep for the child
# script, so nothing in the repository is edited, no tag is created and nothing is pushed.
#
#   scripts/release-selftest.sh
#
# Checks: a non-main branch, a detached HEAD and uncommitted changes are refused before any edit; an origin/main that HEAD does not
# contain is refused before any edit; a failed push takes the release commit and tag back; the happy path
# (positive control) reaches an explicit atomic push of main and the tag.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
LOG=$(mktemp)
export LOG
trap 'rm -f "$LOG"' EXIT

git() {
  echo "git $*" >>"$LOG"
  case "$1" in
    rev-parse)
      case "$*" in
        *--abbrev-ref*) echo "$FAKE_BRANCH" ;;
        *--verify*) return 1 ;;
        *) echo 0123456789abcdef0123456789abcdef01234567 ;;
      esac
      ;;
    status) if [ "${FAKE_DIRTY:-0}" = 1 ]; then echo " M x"; fi ;;
    ls-remote) ;;
    fetch) return 0 ;;
    merge-base) return "${FAKE_ANCESTOR_RC:-0}" ;;
    add | commit | tag | reset) return 0 ;;
    push) return "${FAKE_PUSH_RC:-0}" ;;
    *) echo "UNEXPECTED git $*" >>"$LOG"; return 99 ;;
  esac
}
sed() {
  case "$1" in
    -i) echo "sed -i" >>"$LOG" ;;
    *) command sed "$@" ;;
  esac
}
gh() { echo "gh $*" >>"$LOG"; return 0; }
sleep() { :; }
export -f git sed gh sleep

fails=0
check() { # description condition-exit-status
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi
}
logged() { grep -q -E -- "$1" "$LOG"; }
no_edits() { ! logged '^(git (add|commit|tag|push|reset)|sed -i)'; }

run() { # env assignments are inherited from the caller
  : >"$LOG"
  OUT=$(bash "$here/release.sh" 9.9.9 2>&1)
  RC=$?
}

FAKE_BRANCH=feature/x FAKE_ANCESTOR_RC=0 FAKE_PUSH_RC=0 run
check "non-main branch: exit status 1" "$([ "$RC" -eq 1 ]; echo $?)"
check "non-main branch: says to run from main" "$(grep -q 'run from main' <<<"$OUT"; echo $?)"
check "non-main branch: no commit, tag, push, reset or version edit" "$(no_edits; echo $?)"
check "non-main branch: refused before any network lookup" "$(! logged '^git (fetch|ls-remote)'; echo $?)"

FAKE_BRANCH=HEAD FAKE_ANCESTOR_RC=0 FAKE_PUSH_RC=0 run
check "detached HEAD: refused, no edits" "$([ "$RC" -eq 1 ] && no_edits; echo $?)"

FAKE_BRANCH=main FAKE_DIRTY=1 FAKE_ANCESTOR_RC=0 FAKE_PUSH_RC=0 run
check "uncommitted changes: refused, says so, no edits" "$([ "$RC" -eq 1 ] && grep -q 'uncommitted changes' <<<"$OUT" && no_edits; echo $?)"

FAKE_BRANCH=main FAKE_ANCESTOR_RC=1 FAKE_PUSH_RC=0 run
check "origin/main not an ancestor of HEAD: exit status 1" "$([ "$RC" -eq 1 ]; echo $?)"
check "origin/main not an ancestor of HEAD: says so" "$(grep -q 'does not contain origin/main' <<<"$OUT"; echo $?)"
check "origin/main not an ancestor of HEAD: no edits" "$(no_edits; echo $?)"
check "origin ancestry was checked against origin/main" "$(logged '^git merge-base --is-ancestor origin/main HEAD'; echo $?)"

FAKE_BRANCH=main FAKE_ANCESTOR_RC=0 FAKE_PUSH_RC=1 run
check "push failure: exit status 1" "$([ "$RC" -eq 1 ]; echo $?)"
check "push failure: the version edit and commit had happened" "$(logged '^sed -i' && logged '^git commit'; echo $?)"
check "push failure: local tag deleted" "$(logged '^git tag -d v9.9.9'; echo $?)"
check "push failure: reset to the commit before the release" "$(logged '^git reset -q --hard 0123456789abcdef'; echo $?)"
check "push failure: rollback comes after the push" "$([ "$(grep -n -E '^git push' "$LOG" | cut -d: -f1 | sed -n 1p)" -lt "$(grep -n -E '^git reset' "$LOG" | cut -d: -f1 | sed -n 1p)" ]; echo $?)"

FAKE_BRANCH=main FAKE_ANCESTOR_RC=0 FAKE_PUSH_RC=0 run
check "positive control: pushes main and the tag explicitly and atomically" "$(logged '^git push --atomic origin main refs/tags/v9.9.9$'; echo $?)"
check "positive control: no rollback" "$(! logged '^git (reset|tag -d)'; echo $?)"
check "positive control: reaches the run lookup" "$(logged '^gh run list'; echo $?)"

if [ "$fails" -ne 0 ]; then echo "release-selftest: $fails check(s) FAILED"; exit 1; fi
echo "release-selftest: all checks passed"

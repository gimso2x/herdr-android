#!/usr/bin/env bash
# Smoke test of the signed, R8-minified release APK through its real setup flow. Run as the
# android-emulator-runner `script` (the emulator is already booted):
#
#   ci/release-smoke.sh <app-release.apk>
#
# Env: CF_ID, CF_SECRET (CI Access service token), SMOKE_BAD_TOKEN=1 to type a wrong secret and
# require the rejection text instead. Output files go to release-smoke/ (uploaded as an artifact):
# only the summary (plain pass/fail notes), plus logcat in bad_token mode. The happy path talks to the
# live herdr, so its screenshot and logcat stay on the runner and are never uploaded (the repository
# is public). UI dumps contain the typed client id, so they live in a temp dir too. No `set -x`; nothing secret is echoed.
set -uo pipefail
cd "$(dirname "$0")/.."

APK="${1:?usage: ci/release-smoke.sh <app-release.apk>}"
PKG=com.gimso2x.herdr
OUT=release-smoke
BAD="${SMOKE_BAD_TOKEN:-0}"
CF_ID="${CF_ID:-}"
CF_SECRET="${CF_SECRET:-}"
WRONG_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
DEV_DUMP=/sdcard/herdr-smoke.xml
REJECTED_TEXT='토큰이 거부되었습니다'

mkdir -p "$OUT"
TMP=$(mktemp -d)
SUMMARY="$OUT/summary.txt"
: >"$SUMMARY"
note() { echo "$*" | tee -a "$SUMMARY"; }

cleanup() {
  local rc=$?
  if [ "$BAD" = "1" ]; then
    adb logcat -d >"$OUT/logcat.txt" 2>&1 || true
  fi
  if [ "$rc" -ne 0 ] && [ -s "$TMP/ui.xml" ]; then ui shape >"$OUT/ui-shape.txt" 2>&1 || true; fi
  adb shell pm clear "$PKG" >/dev/null 2>&1 || true
  adb shell rm -f "$DEV_DUMP" >/dev/null 2>&1 || true
  rm -rf "$TMP"
  exit "$rc"
}
trap cleanup EXIT

# Printed to the job log only (never the uploaded summary) when a step fails: counts and names, no URLs or text.
failure_facts() {
  local acts log
  acts=$(adb shell dumpsys activity activities 2>/dev/null | tr -d '\r') || true
  log=$(adb logcat -d 2>/dev/null) || true
  echo "--- failure facts"
  grep -o -E 'ResumedActivity: ActivityRecord\{[^ ]+ u0 [^ ]+' <<<"$acts" | sed -E 's/.* u0 //' | sed -n 1p
  echo "uiautomator dump says: $(timeout 30 adb shell uiautomator dump "$DEV_DUMP" 2>&1 | tr -d "\r" | cut -c1-120)"
  grep -o -E 'net::ERR_[A-Z_]+|FATAL EXCEPTION|ANR in [A-Za-z.]+|Choreographer: Skipped [0-9]+ frames' <<<"$log" | sort | uniq -c
  adb exec-out screencap -p >"$TMP/fail.png" 2>/dev/null && python3 ci/pane_metrics.py --screen "$TMP/fail.png" || true
  dumpui && echo "a11y nodes in the dump: $(grep -o '<node ' "$TMP/ui.xml" | wc -l)"
  echo "---"
}

die() { note "FAIL: $*"; failure_facts; exit 1; }

# --- uiautomator dump queries (python only parses the XML; values are never printed) ----------
dumpui() {
  timeout 30 adb shell uiautomator dump "$DEV_DUMP" >/dev/null 2>&1 && adb pull "$DEV_DUMP" "$TMP/ui.xml" >/dev/null 2>&1
}

ui() { # <mode> [arg]
  python3 - "$TMP/ui.xml" "$@" <<'PY'
import re, sys
import xml.etree.ElementTree as ET

path, mode, *a = sys.argv[1:]
nodes = list(ET.parse(path).getroot().iter("node"))

def box(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2

def centre(n):
    x1, y1, x2, y2 = box(n)
    return (x1 + x2) // 2, (y1 + y2) // 2

if mode == "edits":  # centre and text length of every EditText, in document order
    for n in nodes:
        if n.get("class") == "android.widget.EditText":
            print(*centre(n), len(n.get("text", "")))
elif mode == "node":  # first node whose text is exactly arg: centre + enabled
    for n in nodes:
        if n.get("text") == a[0]:
            print(*centre(n), n.get("enabled"))
            break
    else:
        sys.exit(1)
elif mode == "has":  # any text/content-desc containing arg
    sys.exit(0 if any(a[0] in (n.get("text", "") + n.get("content-desc", "")) for n in nodes) else 1)
elif mode == "webview":  # number of described descendants of the first WebView
    wv = [n for n in nodes if n.get("class") == "android.webkit.WebView"]
    if not wv:
        sys.exit(1)
    print(sum(1 for d in wv[0].iter("node") if d is not wv[0] and (d.get("text") or d.get("content-desc"))))
elif mode == "shape":  # classes, bounds and text lengths only
    for n in nodes:
        print(n.get("class"), n.get("bounds"), len(n.get("text", "")), "enabled=" + str(n.get("enabled")))
PY
}

wait_for() { # <seconds> <what> <command...>
  local limit=$1 what=$2
  shift 2
  local end=$((SECONDS + limit))
  while [ "$SECONDS" -lt "$end" ]; do
    if "$@"; then return 0; fi
    sleep 1
  done
  note "TIMEOUT after ${limit}s waiting for: $what"
  return 1
}

# Resumed activity facts come from dumpsys; captured first so grep never closes a pipe on adb.
resumed() { # <ActivityName>
  local out
  out=$(adb shell dumpsys activity activities 2>/dev/null | tr -d '\r')
  grep -E 'ResumedActivity' <<<"$out" | grep -E "$PKG/(\.|$PKG\.)$1([^A-Za-z]|\$)" >/dev/null
}

# `input text` through the device shell. Safe characters go as runs, space as %s, anything else
# single-quoted on its own, '%' as shift+5; non-ASCII cannot be sent and is refused (no value shown).
type_text() { # <name of the env var holding the value>
  local cmd
  cmd=$(TYPE_VAL="${!1}" python3 - <<'PY'
import os, sys

safe = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-@:/+=,~")
parts, run = [], ""
for ch in os.environ["TYPE_VAL"]:
    if ch in safe:
        run += ch
        continue
    if run:
        parts.append("input text '%s'" % run)
        run = ""
    if ch == " ":
        parts.append("input text '%s'")
    elif ch == "%":
        parts.append("input keycombination KEYCODE_SHIFT_LEFT KEYCODE_5")
    elif 32 < ord(ch) < 127:
        parts.append("input text '%s'" % ch.replace("'", "'\\''"))
    else:
        sys.exit(3)
if run:
    parts.append("input text '%s'" % run)
print("; ".join(parts))
PY
  ) || return 1
  adb shell "$cmd" >/dev/null 2>&1
}

edit_count() { dumpui && [ "$(ui edits | wc -l)" -ge 2 ]; }

# Tap the Nth (1-based) EditText of a fresh dump, type the value, return the length the field shows.
fill_field() { # <index> <env var name> <expected length>
  dumpui || return 1
  local line x y
  line=$(ui edits | sed -n "${1}p")
  [ -n "$line" ] || return 1
  read -r x y _ <<<"$line"
  adb shell input tap "$x" "$y"
  type_text "$2" || return 1
  dumpui || return 1
  line=$(ui edits | sed -n "${1}p")
  read -r _ _ shown <<<"$line"
  [ "$shown" = "$3" ]
}

# --- preconditions ----------------------------------------------------------------------------
[ -s "$APK" ] || die "apk not found"
[ -n "$CF_ID" ] || die "CF_ID is missing"
if [ "$BAD" = "1" ]; then
  SECRET_VALUE="$WRONG_SECRET"
else
  [ -n "$CF_SECRET" ] || die "CF_SECRET is missing"
  SECRET_VALUE="$CF_SECRET"
fi
ID_VALUE="$CF_ID"
note "mode=$([ "$BAD" = "1" ] && echo bad_token || echo happy) id_len=${#ID_VALUE} secret_len=${#SECRET_VALUE}"

# --- install + prepare ------------------------------------------------------------------------
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put global hide_error_dialogs 1

install_out=$(adb install -r "$APK" 2>&1) || { echo "$install_out"; die "adb install failed"; }
grep -q '^Success' <<<"$install_out" || die "adb install did not print Success"
pkg_dump=$(adb shell dumpsys package "$PKG" | tr -d '\r')
version_name=$(grep -o -E 'versionName=[^ ]+' <<<"$pkg_dump" | sed -n 1p)
version_code=$(grep -o -E 'versionCode=[0-9]+' <<<"$pkg_dump" | sed -n 1p)
note "installed $version_name $version_code"
[ -n "$version_name" ] || die "package not installed after adb install"
# The one-time notification prompt is a separate task that would take the resumed state from
# MainActivity; the permission is granted up front (the prompt itself is covered by the instrumented QA).
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || die "pm grant failed"

adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || die "am start failed"

# --- setup screen -----------------------------------------------------------------------------
setup_up() { dumpui && ui node '연결' >/dev/null; }
wait_for 30 'setup screen button "연결"' setup_up || die "setup screen did not appear"
wait_for 10 'two setup fields' edit_count || die "setup fields not found"
resumed SetupActivity || die "SetupActivity is not the resumed activity"
note "setup screen shown (SetupActivity resumed)"

fill_field 1 ID_VALUE "${#ID_VALUE}" || die "client id field does not hold the typed value (length mismatch)"
fill_field 2 SECRET_VALUE "${#SECRET_VALUE}" || die "client secret field does not hold the typed value (length mismatch)"
note "both fields filled (lengths verified from the dump)"

# Close the soft keyboard (BACK only closes the IME while it is shown) so the button is reachable.
ime_out=$(adb shell dumpsys input_method 2>/dev/null | tr -d '\r')
if grep -q 'mInputShown=true' <<<"$ime_out"; then
  adb shell input keyevent KEYCODE_BACK
fi

button_ready() {
  dumpui || return 1
  local line enabled
  line=$(ui node '연결') || return 1
  read -r _ _ enabled <<<"$line"
  [ "$enabled" = "true" ]
}
wait_for 15 'enabled "연결" button' button_ready || die "connect button is not enabled after typing"
read -r bx by _ <<<"$(ui node '연결')"
adb shell input tap "$bx" "$by"
note "tapped 연결"

# --- bad token: the rejection text must show ---------------------------------------------------
if [ "$BAD" = "1" ]; then
  rejected() { dumpui && ui has "$REJECTED_TEXT"; }
  wait_for 60 "rejection text" rejected || die "rejection text '$REJECTED_TEXT' not shown"
  resumed SetupActivity || die "SetupActivity is not resumed after the rejection"
  if resumed MainActivity; then die "MainActivity resumed with a wrong token"; fi
  note "REJECTION SEEN: setup screen shows '$REJECTED_TEXT'"
  fatal=$(adb logcat -d | grep -c -E 'FATAL EXCEPTION|AndroidRuntime: Process: com.gimso2x.herdr' || true)
  note "FATAL count $fatal"
  [ "$fatal" -eq 0 ] || die "FATAL in logcat"
  note "SMOKE OK (bad_token)"
  exit 0
fi

# --- happy path: MainActivity resumed, WebView with content -----------------------------------
main_ready() { resumed MainActivity && dumpui && ui webview >/dev/null; }
wait_for 60 'MainActivity resumed with an android.webkit.WebView node' main_ready \
  || die "MainActivity did not resume with a WebView"
note "MainActivity resumed, WebView node present"

# The WebView node exists while the page may still be loading: wait for described web content.
page_ready() { dumpui && [ "$(ui webview)" -gt 0 ]; }
wait_for 60 'web content under the WebView' page_ready || die "WebView shows no web content"
ui has 'herdr 연결' && die "setup screen is still visible"
ui has 'Cloudflare' && die "a Cloudflare page is showing instead of herdr"
ui has 'Sign in' && die "a login page is showing instead of herdr"
note "web content nodes under WebView: $(ui webview)"
resumed MainActivity || die "MainActivity no longer resumed"

adb exec-out screencap -p >"$TMP/release-smoke.png" || die "screencap failed"
png_bytes=$(stat -c %s "$TMP/release-smoke.png")
note "screenshot release-smoke.png bytes=$png_bytes"
# The emulator screen is only 320x640 and the dark UI compresses well: a real herdr screenshot is ~14 KB.
[ "$png_bytes" -gt 5000 ] || die "screenshot is suspiciously small"
[ "$(head -c 4 "$TMP/release-smoke.png" | tail -c 3)" = "PNG" ] || die "screenshot is not a PNG"

# --- pane area: tall and not blank -----------------------------------------------------------------
# The v0.1.0/v0.1.1 bug (every viewport-height unit computed to 0 in the WebView, so the page's
# `.app` shell and with it the terminal/chat area had no height) left header, banner and tabs drawn
# over a blank pane area, which the checks above cannot see. The release APK has no devtools, so the
# DOM is judged from outside: a node under the WebView taller than 100px (not the page container) whose
# screenshot rectangle is not blank (ci/pane_metrics.py). Booleans and counts only; the screenshots
# stay on the runner.
pane_ok() {
  dumpui && adb exec-out screencap -p >"$TMP/pane.png" \
    && python3 ci/pane_metrics.py "$TMP/ui.xml" "$TMP/pane.png" >"$TMP/pane.txt"
}
pane_seen=0
wait_for 60 'a visible, non-blank pane area under the WebView' pane_ok && pane_seen=1
dumpui && adb exec-out screencap -p >"$TMP/pane.png" && python3 ci/pane_metrics.py "$TMP/ui.xml" "$TMP/pane.png" --shape >"$TMP/pane-shape.txt" || true
# Lengths and bounds only; printed to the job log, not written to the uploaded summary.
grep '^shape ' "$TMP/pane-shape.txt" || true
note "$(grep '^pane-metrics:' "$TMP/pane-shape.txt" || echo 'pane-metrics: unavailable')"
[ "$pane_seen" -eq 1 ] || die "the pane area is missing or blank (no tall, non-blank node under the WebView)"
note "pane area visible and non-blank"

adb shell pidof "$PKG" >/dev/null || die "app process is not alive"
fatal=$(adb logcat -d | grep -c -E 'FATAL EXCEPTION|AndroidRuntime: Process: com.gimso2x.herdr' || true)
note "FATAL count $fatal"
[ "$fatal" -eq 0 ] || die "FATAL in logcat"

clear_out=$(adb shell pm clear "$PKG" 2>&1 | tr -d '\r')
note "pm clear: $clear_out"
[ "$clear_out" = "Success" ] || die "pm clear failed"
note "SMOKE OK (happy)"

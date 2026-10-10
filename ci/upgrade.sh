#!/usr/bin/env bash
# Runs inside the emulator-runner `script`: install the from-APK, update in place to the to-APK,
# and check that firstInstallTime is kept and versionCode increased.
# Usage: ci/upgrade.sh <from.apk> <to.apk>
set -euo pipefail

PKG=com.gimso2x.herdr
FROM_APK=$1
TO_APK=$2

field() { # <dumpsys field name>; sed (not grep -m1/head) reads all input so pipefail never sees SIGPIPE
  adb shell dumpsys package "$PKG" | tr -d '\r' | grep -o -E "$1=[^ ]+( [0-9:]+)?" | cut -d= -f2- | sed -n 1p
}

adb install "$FROM_APK"
first_before=$(field firstInstallTime)
code_before=$(field versionCode)

out=$(adb install -r "$TO_APK" 2>&1) || { echo "$out"; echo "FAIL: adb install -r failed" >&2; exit 1; }
echo "$out"
echo "$out" | grep -q '^Success' || { echo "FAIL: install -r did not print Success" >&2; exit 1; }

first_after=$(field firstInstallTime)
code_after=$(field versionCode)

echo "firstInstallTime before=$first_before"
echo "versionCode before=$code_before"
echo "firstInstallTime after=$first_after"
echo "versionCode after=$code_after"

[ -n "$first_before" ] && [ "$first_before" = "$first_after" ] || { echo "FAIL: firstInstallTime changed" >&2; exit 1; }
[[ "$code_before" =~ ^[0-9]+$ && "$code_after" =~ ^[0-9]+$ ]] && [ "$code_after" -gt "$code_before" ] || { echo "FAIL: versionCode not greater" >&2; exit 1; }
echo "UPGRADE OK"

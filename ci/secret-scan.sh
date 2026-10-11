#!/usr/bin/env bash
# Secret scan of everything a workflow is about to upload from this public repository.
#
#   ci/secret-scan.sh [--apk FILE]... PATH...
#
# Env (all required): CF_ID, CF_SECRET (the CI Cloudflare Access service token), BASE_URL (the private server).
# Fails (exit 1) when any scanned file contains
#   - the client secret, the client id or the server host (literal match), or
#   - an Access cookie / JWT shape: `CF_Authorization=` or a base64url JSON header (`eyJ` + 10 more characters).
# --apk files are unzipped and scanned for the literals only (compiled test fixtures legitimately hold JWT-like
# strings). Exit 2 when the inputs are missing (the scan must never silently pass). Prints counts, never matches.
set -uo pipefail

apks=()
paths=()
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) apks+=("${2:?--apk needs a file}"); shift 2 ;;
    *) paths+=("$1"); shift ;;
  esac
done

if [ -z "${CF_ID:-}" ] || [ -z "${CF_SECRET:-}" ] || [ -z "${BASE_URL:-}" ]; then
  echo 'secret scan inputs are missing (CF_ID, CF_SECRET, BASE_URL)' >&2
  exit 2
fi
BASE_HOST=${BASE_URL#*://}
BASE_HOST=${BASE_HOST%%/*}
if [ -z "$BASE_HOST" ]; then echo 'secret scan: BASE_URL has no host' >&2; exit 2; fi

files=0
shapes=0
if [ "${#paths[@]}" -gt 0 ]; then
  files=$( { grep -r -l -F -e "$CF_SECRET" -e "$CF_ID" -e "$BASE_HOST" "${paths[@]}" 2>/dev/null || true; } | wc -l)
  shapes=$( { grep -r -l -E -e 'CF_Authorization=' -e 'eyJ[A-Za-z0-9_-]{10,}' "${paths[@]}" 2>/dev/null || true; } | wc -l)
fi
apk=0
for a in "${apks[@]}"; do
  if [ -f "$a" ]; then
    n=$(unzip -p "$a" 2>/dev/null | grep -c -F -e "$CF_SECRET" -e "$CF_ID" -e "$BASE_HOST" || true)
    apk=$((apk + n))
  fi
done
total=$((files + shapes + apk))
echo "secret-scan files=$files cookie-or-jwt-files=$shapes apk=$apk"
[ "$total" -eq 0 ]

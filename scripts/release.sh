#!/usr/bin/env bash
# Cut a release: bump the version, commit, tag, push, wait for the tag build, download the APK.
# Usage: scripts/release.sh <version>      e.g. scripts/release.sh 0.1.0
set -euo pipefail

REPO=gimso2x/herdr-android
VERSION=${1:-}
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "usage: $0 <version> (e.g. 0.1.0)" >&2; exit 2; }
TAG="v$VERSION"

cd "$(dirname "$0")/.."

# Guards run BEFORE anything is edited.
if [ -n "$(git status --porcelain)" ]; then
  echo "error: uncommitted changes; commit or remove them first" >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "error: tag exists locally: $TAG" >&2
  exit 1
fi
if [ -n "$(git ls-remote --tags origin "$TAG")" ]; then
  echo "error: tag exists on origin: $TAG" >&2
  exit 1
fi

GRADLE=app/build.gradle
code=$(sed -n -E 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+)[[:space:]]*$/\1/p' "$GRADLE")
[[ "$code" =~ ^[0-9]+$ ]] || { echo "error: cannot read versionCode from $GRADLE" >&2; exit 1; }
sed -i -E \
  -e "s/^([[:space:]]*versionCode[[:space:]]+)[0-9]+/\1$((code + 1))/" \
  -e "s/^([[:space:]]*versionName[[:space:]]+)\"[^\"]*\"/\1\"$VERSION\"/" \
  "$GRADLE"
git add "$GRADLE"
git commit -q -m "chore(release): $VERSION"
SHA=$(git rev-parse HEAD)
git tag -a "$TAG" -m "$TAG"
git push --follow-tags origin main
echo "released commit $SHA as $TAG (versionCode $((code + 1)))"

# Run discovery (not a status poll): at most 12 lookups, 10 s apart, for the tag's own run.
RUN=""
for _ in $(seq 1 12); do
  RUN=$(gh run list --repo "$REPO" --workflow android.yml --branch "$TAG" --commit "$SHA" --json databaseId -q '.[0].databaseId' || true)
  [ -n "$RUN" ] && break
  sleep 10
done
[ -n "$RUN" ] || { echo "error: no android.yml run found for $TAG after 12 lookups" >&2; exit 1; }
echo "tag run: https://github.com/$REPO/actions/runs/$RUN"
gh run watch "$RUN" --repo "$REPO" --exit-status

mkdir -p "dist/$VERSION"
gh release download "$TAG" --repo "$REPO" -p app-release.apk -D "dist/$VERSION/" --clobber
echo "release: https://github.com/$REPO/releases/tag/$TAG"
sha256sum "dist/$VERSION/app-release.apk"

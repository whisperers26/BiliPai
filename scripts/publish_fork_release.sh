#!/usr/bin/env bash
# Builds the signed release APK from mymain and publishes it as a GitHub Release on the fork,
# where the in-app update checker (AppUpdateChecker) looks for new versions.
#
# Prerequisites:
#   - bili.release.* signing properties in ~/.gradle/gradle.properties (see app/build.gradle.kts)
#   - gh signed in to the whisperers26 account
#   - versionName / versionCode already bumped and pushed on mymain
set -euo pipefail

REPO="whisperers26/BiliPai"
RELEASE_BRANCH="mymain"

cd "$(git rev-parse --show-toplevel)"

branch="$(git rev-parse --abbrev-ref HEAD)"
if [ "$branch" != "$RELEASE_BRANCH" ]; then
  echo "Releases are published from $RELEASE_BRANCH only (current branch: $branch)." >&2
  exit 1
fi

git fetch --quiet origin "$RELEASE_BRANCH"
head_sha="$(git rev-parse HEAD)"
if [ "$head_sha" != "$(git rev-parse "origin/$RELEASE_BRANCH")" ]; then
  echo "HEAD is not in sync with origin/$RELEASE_BRANCH; push or pull first." >&2
  exit 1
fi
if ! git diff --quiet HEAD -- app; then
  echo "app/ has uncommitted changes; commit or stash them before releasing." >&2
  exit 1
fi

version_name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
version_code="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -n 1)"
tag="v$version_name"
echo "Releasing $tag (versionCode $version_code) from $head_sha"

if gh release view "$tag" -R "$REPO" >/dev/null 2>&1; then
  echo "Release $tag already exists on $REPO; bump versionName/versionCode first." >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  for candidate in "/c/Program Files/Android/Android Studio/jbr" "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [ -d "$candidate" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

./gradlew :app:assembleRelease --console=plain \
  "-Pbili.build.commitSha=$head_sha" \
  "-Pbili.build.gitRef=refs/heads/$RELEASE_BRANCH" \
  "-Pbili.build.releaseTag=$tag"

apk="app/build/outputs/bilipai/release/BiliPai-$version_name.apk"
if [ ! -f "$apk" ]; then
  echo "Expected APK not found: $apk" >&2
  exit 1
fi

sdk_dir="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | sed 's/\\:/:/g; s/\\\\/\//g')}"
apksigner="$(ls -d "$sdk_dir"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)apksigner"
[ -f "$apksigner.bat" ] && apksigner="$apksigner.bat"
if [ -e "$apksigner" ]; then
  "$apksigner" verify --print-certs "$apk" | grep -v '^WARNING' | head -n 2
else
  echo "apksigner not found; skipping signature check." >&2
fi

dist_dir="build/fork-release/$tag"
rm -rf "$dist_dir"
mkdir -p "$dist_dir"
cp "$apk" "$dist_dir/"

# build-metadata.json lets the update checker compare versionCode instead of version names.
DIST_DIR="$dist_dir" VERSION_NAME="$version_name" VERSION_CODE="$version_code" \
HEAD_SHA="$head_sha" RELEASE_TAG="$tag" RELEASE_BRANCH="$RELEASE_BRANCH" python - <<'PY'
import datetime
import hashlib
import json
import os
from pathlib import Path

dist_dir = Path(os.environ["DIST_DIR"])
apks = sorted(dist_dir.glob("*.apk"))
artifacts = []
checksum_lines = []
for apk in apks:
    sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    artifacts.append({"name": apk.name, "sha256": sha256, "sizeBytes": apk.stat().st_size})
    checksum_lines.append(f"{sha256}  {apk.name}")

payload = {
    "schemaVersion": 1,
    "appId": "com.android.purebilibili",
    "versionName": os.environ["VERSION_NAME"],
    "versionCode": int(os.environ["VERSION_CODE"]),
    "gitCommitSha": os.environ["HEAD_SHA"],
    "gitRef": "refs/heads/" + os.environ["RELEASE_BRANCH"],
    "workflowRunId": "",
    "workflowRunUrl": "",
    "releaseTag": os.environ["RELEASE_TAG"],
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "artifacts": artifacts,
}
(dist_dir / "build-metadata.json").write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
(dist_dir / "checksums.txt").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
PY

notes_file="$dist_dir/release-notes.md"
awk -v heading="## $tag " 'index($0, heading) == 1 { found = 1; next } found && /^## / { exit } found { print }' CHANGELOG.md \
  | sed '/^---$/d' > "$notes_file"
if [ ! -s "$notes_file" ]; then
  echo "Built from $RELEASE_BRANCH @ ${head_sha:0:9}." > "$notes_file"
fi

gh release create "$tag" -R "$REPO" \
  --target "$head_sha" \
  --title "$tag" \
  --notes-file "$notes_file" \
  --latest \
  "$dist_dir"/*.apk "$dist_dir/build-metadata.json" "$dist_dir/checksums.txt"

echo "Published https://github.com/$REPO/releases/tag/$tag"

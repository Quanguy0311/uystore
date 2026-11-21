#!/bin/sh
set -eu

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    echo "usage: scripts/release.sh <release-notes.md>" >&2
    exit 2
fi
notes=$1
cd "$(dirname "$0")/.."

gh=${GH:-gh}
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
tag="v$version"
cert=$(sed -n 's/.*CERT = "\([0-9a-f]*\)".*/\1/p' app/src/main/java/io/github/wingedpanther/openshelf/Data.kt)
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}
apksigner=$(ls "$sdk"/build-tools/*/apksigner | sort -V | tail -1)

[ -z "$(git status --porcelain)" ] || { echo "working tree has changes" >&2; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "not on main" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { echo "$tag already exists" >&2; exit 1; }

./gradlew --no-daemon -q ${GRADLE_ARGS:-} assembleRelease
apk=${APK:-app/build/outputs/apk/release/app-release.apk}

"$apksigner" verify --print-certs "$apk" | grep -qi "SHA-256 digest: $cert" || { echo "APK is not signed with the release key" >&2; exit 1; }

mkdir -p dist
cp "$apk" "dist/OpenShelf-$version.apk"
cp "$apk" dist/OpenShelf.apk
shasum -a 256 "dist/OpenShelf-$version.apk"

printf "Release %s to GitHub? [y/N] " "$tag"
read -r answer
[ "$answer" = y ] || exit 0

git tag "$tag"
git push origin "$tag"
"$gh" release create "$tag" "dist/OpenShelf-$version.apk" dist/OpenShelf.apk --title "Open Shelf $version" --notes-file "$notes"

#!/usr/bin/env bash
# Build a signed release and publish it to GitHub Releases so installed apps offer the update.
# Usage: ./release.sh            (uses versionName/versionCode from app/build.gradle.kts)
# Bump versionCode + versionName in app/build.gradle.kts before each release.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/wasla-toolchain/jdk-17.0.20.1+1}"

[ -f keystore.properties ] || { echo "keystore.properties missing — releases must be signed with the same key"; exit 1; }
NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
CODE=$(grep -oP 'versionCode = \K\d+' app/build.gradle.kts)
REPO="$(grep -oP '^ghOwner=\K.*' gradle.properties)/$(grep -oP '^ghRepo=\K.*' gradle.properties)"

./gradlew assembleRelease -q
APK="CarInfo-$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

# The app reads "(code N)" from the release name and verifies the sha256 line in the notes.
gh release create "v$NAME" "$APK" --repo "$REPO" \
  --title "Car Info $NAME (code $CODE)" \
  --notes "${NOTES:-Car Info $NAME}

sha256: $SHA"
echo "Published v$NAME (code $CODE) to $REPO"

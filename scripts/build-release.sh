#!/usr/bin/env bash
# Builds the two platform-signed release APKs (MyEX2Center + EX2 Store) into dist/
# and prints their SHA-256 for the GitHub Release notes and apps.json.
#
# Needs: signing/platform.p12 (never committed), JDK 17-21, Android SDK build-tools (apksigner).
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home}"
export JAVA_HOME
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)"

[ -f signing/platform.p12 ] || { echo "signing/platform.p12 not found" >&2; exit 1; }

./gradlew --quiet clean :app:assembleRelease :store:assembleRelease

mkdir -p dist
version_of() { "$AAPT2" dump badging "$1" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1; }
publish() { # <apk> <name>
  local apk="$1" name="$2" ver out
  ver="$(version_of "$apk")"
  local manifest; manifest="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$apk")"
  [[ "$manifest" == *'android.uid.system'* ]] \
    || { echo "$name: missing sharedUserId=android.uid.system" >&2; exit 1; }
  "$APKSIGNER" verify "$apk" >/dev/null
  out="dist/$name-$ver.apk"
  cp "$apk" "$out"
  printf '%-28s %s\n' "$(basename "$out")" "$(shasum -a 256 "$out" | cut -d' ' -f1)"
}
echo "Signer: $("$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk | sed -n 's/.*SHA-256 digest: //p')"
publish app/build/outputs/apk/release/app-release.apk     MyEX2Center
publish store/build/outputs/apk/release/store-release.apk EX2Store

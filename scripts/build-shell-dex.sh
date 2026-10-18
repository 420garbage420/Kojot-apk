#!/usr/bin/env bash
# Builds shell/classes.dex for the on-device compiler (run on a host with JDK + Android SDK).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then echo "Set ANDROID_HOME"; exit 1; fi
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
echo "Using build-tools: $BT"
echo "Using platform:    $PLATFORM"
TMP="$(mktemp -d)"
javac --release 11 -classpath "$PLATFORM/android.jar" -d "$TMP" \
  "$ROOT/shell-src/com/kojot/shell/WebShellActivity.java"
"$BT/d8" --min-api 28 --output "$TMP" "$TMP"/com/kojot/shell/*.class
mkdir -p "$ROOT/android/app/src/main/assets/compiler_tools/shell"
cp "$TMP/classes.dex" "$ROOT/android/app/src/main/assets/compiler_tools/shell/classes.dex"
cp "$PLATFORM/android.jar" "$ROOT/android/app/src/main/assets/compiler_tools/android.jar"
if [[ -f "$BT/aapt2" ]]; then
  cp "$BT/aapt2" "$ROOT/android/app/src/main/assets/compiler_tools/aapt2"
  chmod +x "$ROOT/android/app/src/main/assets/compiler_tools/aapt2"
fi
if [[ -f "$BT/d8" ]]; then
  cp "$BT/d8" "$ROOT/android/app/src/main/assets/compiler_tools/d8"
  chmod +x "$ROOT/android/app/src/main/assets/compiler_tools/d8"
fi
echo "OK → assets/compiler_tools/shell/classes.dex"
rm -rf "$TMP"

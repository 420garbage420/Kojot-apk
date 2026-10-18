# Kojot on-device compiler tools

Place the following **arm64-v8a** binaries and SDK files here before building the Kojot APK
(they are extracted into the app’s private `filesDir/compiler_tools/` on first launch):

| File | Source |
|------|--------|
| `aapt2` | Android SDK `build-tools/*/aapt2` (linux arm64 or static build) |
| `d8` | Android SDK `build-tools/*/d8` (optional) |
| `android.jar` | Android SDK `platforms/android-34/android.jar` |
| `shell/classes.dex` | Prebuilt WebView shell (see `shell-src/`) |
| `debug.keystore` | Already bundled (android/androiddebugkey) |

## Building shell/classes.dex

```bash
# From a machine with Android SDK:
$ANDROID_HOME/build-tools/34.0.0/d8 --output shell/ \
  path/to/WebShellActivity.class
```

Or use the helper script at `scripts/build-shell-dex.sh`.

Without `aapt2` + `android.jar` + `shell/classes.dex`, `compile_app` returns a clear error.

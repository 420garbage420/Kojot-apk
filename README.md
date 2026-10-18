# Kojot

On-device local agent for Android. Runs any GGUF model via llama-server, with optional
edge memory (nomic-embed), a Monaco code editor, and an on-device APK compiler.

## Package

- Application ID: `com.kojot.app`
- Launcher name: **Kojot**
- Icon: voxel coyote (adaptive, API 26+)

## Build (host)

```bash
# Optional: stage aapt2 / android.jar / shell dex for the in-app compiler
./scripts/build-shell-dex.sh

# CI (GitHub Actions) builds llama-server + packages the APK
# Or locally:
cd android && gradle assembleDebug
```

## On-device APK compiler

1. Put `aapt2`, `android.jar`, and `shell/classes.dex` under
   `android/app/src/main/assets/compiler_tools/` (see README there).
2. In the app: **Settings → Tools → APK compiler → Build APK from workspace**.
3. Or ask the agent: `compile the current project into an apk`.

Web projects (`index.html`) are wrapped in a minimal WebView shell.
Android projects need `AndroidManifest.xml` (+ optional `res/`, `assets/`).

## Models

- Main model: any `.gguf` (picked on first launch → `filesDir/model.gguf`)
- Embeddings (optional): `nomic-embed-text` → `filesDir/nomic.gguf` (port 8081)

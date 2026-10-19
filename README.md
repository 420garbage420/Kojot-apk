# Kojot

On-device local agent for Android. Runs any GGUF model via llama-server, with optional
edge memory (nomic-embed) and a Monaco code editor.

The on-device APK compiler has been removed from this app and archived under
`compiler-standalone/` for use in a separate project.

## Agent reliability (AgentDeck-inspired)

The mobile web UI (`www/index.html`) includes guardrails tuned for small on-device models:

1. **Context sanitizer (`scrubJunk`)** — strips nulls, zero-width chars, ANSI codes, and
   collapses excess blank lines before tool results enter KV cache / `apiMsgs`.
2. **Continuity governor** — circuit-breaks identical tool calls that fail twice or
   repeat five times, returning a synthetic mandate instead of spinning.
3. **Task ledger** — preserves the standing user goal across "continue" / step-limit
   pauses so the model resumes instead of restarting.
4. **Myers diff for `edit_file`** — approval cards show a red/green line diff of
   search vs replace.
5. **Message actions** — Copy + Download `.md` under completed assistant replies.

## Package

- Application ID: `com.kojot.app`
- Launcher name: **Kojot**
- Icon: voxel coyote (adaptive, API 26+)

## Build (host)

```bash
# CI (GitHub Actions) builds llama-server + packages the APK
# Or locally:
cd android && gradle assembleDebug
```

## Models

- Main model: any `.gguf` (picked on first launch → `filesDir/model.gguf`)
- Embeddings (optional): `nomic-embed-text` → `filesDir/nomic.gguf` (port 8081)

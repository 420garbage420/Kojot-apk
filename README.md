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

## Recent patches (build-33+)

- **No-copy models**: When the system can resolve a real filesystem path from the picked URI (common for Downloads / shared storage), the main model is used in-place. No multi-GB duplicate under `filesDir`.
- **Hot-switch main model**: Settings → Main Chat Model → Switch model. Does not wipe chats, memory, or preferences (unlike clearing app data).
- **Small-model tool-call repair**: Detects and rewrites common failure modes from quantized <7B models (Gemma 3 1B, Llama-3.2-1B, etc.): malformed JSON, trailing commas, XML/Python-style calls, preamble text, content-embedded tool calls. Low-latency local repair before the agent loop.
- **Tool-card animation fix**: Running/prep cards update in-place so the spinner and shimmer no longer restart every frame.
- **Process resilience (S26 Ultra / One UI)**: Longer partial wake-lock while the server is alive, one-shot battery-optimization exemption request, `largeHeap`, `singleTask`, hardware acceleration. Reduces Android process kills during long agent runs.

## Capability depth (this build)

- **Resume agent runs** — Interrupted tasks snapshot to `lc.resume`; hero shows Resume/Dismiss (6h TTL).
- **Tool result cache** — Read-only tools (`list_dir`, `read_file`, memory, …) cached 5 minutes (toggle in Settings).
- **Project mode** — Name + conventions injected every turn via Settings → Active Project.
- **Approval memory** — Approved destructive tool patterns remembered (Clear in Agent depth).
- **Performance profile** — Efficiency / Balanced / Max; soft thermal & battery notes injected when hot or low battery.
- **Auto-coder routing** — Coding-intent messages soft-swap to coder model when installed.
- **Offline web fallback** — `web_search` falls back to local memory recall when network/API fails.
- **Diff-first write_file** — Content preview on write cards; edit_file keeps Myers diff.
- **Monaco constrained JSON** — `response_format` json_schema on AI edit path.
- **Small-model tool repair** — Malformed / XML / Python-style tool args repaired locally.

## Foreground service (model process)

`LlamaService` is a sticky foreground service that owns `llama-server` (port 8080)
and the embedding server (port 8081). The Activity binds to it for control (start /
restart / swap) but **does not kill** the servers on `onDestroy`, so agent runs
survive leaving the app or system reclaiming the Activity.

- Notification channel: "Kojot model server" (low importance, ongoing)
- Action on notification: Stop
- FGS type: `specialUse` (on-device LLM inference)
- Partial wake-lock held while the service is running


## PDF forms + OCR

- **PDF panel** (sidebar → PDF Forms): view pages (pdf.js), list/edit AcroForm fields (pdf-lib), save filled copy.
- **Agent tools**: `pdf_info`, `list_pdf_fields`, `fill_pdf_fields`, `read_pdf_text`, `ocr_pdf`, `ocr_image`.
- **AI Fill**: constrained JSON field updates from the local model (same idea as Monaco edit chains).
- **Signature**: draw on pad → PNG stamp on page (save to persist).
- **OCR**: optional ML Kit text recognition (`com.google.mlkit:text-recognition`) for scans and images.
- pdf.js / pdf-lib load from CDN on first open (network once).

## Recent (this build)

- **Optional OCR model path** — Bind a GGUF to the OCR slot from Models folder; AI Fill still falls back to on-device ML Kit until a vision/OCR engine is wired. Path is recorded and shown in Settings.
- **Power user: models by file type** — Map extensions (e.g. `.py`) to specialized no-copy GGUFs. Monaco AI edit chains soft-swap to that model when Power user is on.
- **Syntax check by file type** — Editor validates JSON, JS, TS, HTML/XML, Python (brackets + indent + triple-quotes), Kotlin/Java/C-family, CSS, YAML with language-appropriate rules.
- **OCR layout reconstruction** — After OCR, line boxes are clustered into rows/columns; markdown tables and structured layout are cached on the PDF object and fed into AI Fill.

- **Vision OCR runner (:8082)** — Binding an OCR GGUF starts a second llama-server. Place a matching `*mmproj*.gguf` beside the model for multimodal image OCR. PdfTools prefers vision text when the server is healthy, keeps ML Kit line boxes for layout, and falls back to ML Kit entirely if the server is down or mmproj is missing.

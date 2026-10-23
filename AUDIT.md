# Kojot Lite build-27: audit & change report (updated, round 2 at the bottom)

Everything below was checked either **in a real headless Chromium** (mobile-emulated 412×915 @3.5x, the real `index.html`, a stubbed native bridge, a fake streaming llama-server, real Monaco) or **by compiling/running the Kotlin** (Kotlin 1.9.24 against Android 34; `FileTools` executed on a real filesystem with a real `org.json`). Nothing was run on a physical S26 Ultra; items that need a device are listed at the end.

## 1. What was actually causing the "seams"

| # | Finding | Evidence | Status |
|---|---|---|---|
| 1 | **Every turn re-processed ~100% of the prompt.** Task ledger + memory facts were written into the *system* message, so the prefix changed at token 0 each turn and llama.cpp's prompt cache never hit. | Shared prefix between consecutive requests: **6% → 98%** | Fixed: system prompt is now stable; per-turn context rides on the latest user message |
| 2 | **No context management.** Past the 8k ctx the chat dead-ended ("Couldn't reach the model. HTTP 400…") and every later message failed. | Reproduced (30-turn chat) | Fixed: reads `n_ctx`, trims oldest turns with hysteresis (stable prefix), friendly message + auto-retry budget, `finish_reason=length` → **Continue** button |
| 3 | **Editor Back gesture threw** `showEditor is not defined` (editor code lives in an IIFE; callers were outside it). FV's "Open in Code Editor" threw the same. | Reproduced via `__goBack()` | Fixed: exported; Back now uses a real overlay stack |
| 4 | **Sidebar → Code editor left the dim scrim on top of the editor**, swallowing touches (handler removed a class the scrim doesn't use). | Reproduced, `elementFromPoint` = scrim | Fixed |
| 5 | **Whole chat re-built and re-animated on every agent step.** | `rise` animation fired **188×** for 2 messages | Fixed: incremental render (4×) |
| 6 | **Stream yanked you to the bottom** when you tried to scroll up during generation. | Reproduced | Fixed: stick-to-bottom + "jump to latest" button |
| 7 | ~400 ms of dead time per tool step (hard 380 ms floor). | 550 ms → ~290 ms req→req | Fixed (120 ms) |
| 8 | **Link injection in `md()`**: `[x](https://a"onmouseover="…)` produced a live handler. The page owns a native bridge that can write/delete files, so this is a real hole (model output / web-search text can reach it). | Reproduced | Fixed. Also escaped forged-tool name/description |
| 9 | Editor opened a file with `setValue()`: **silently discarded unsaved edits and undo history**; no crash recovery; saves could clobber agent edits made meanwhile; writes weren't atomic. | Code + tests | Fixed (see §2) |
| 10 | `list_dir` sort comparator re-`stat`ed files on every comparison. | 4,000-entry folder **165 → 52 ms** on local disk (FUSE should gain more; not measured on device) | Fixed |
| 11 | Monaco worker URL was doubly wrong (relative path from a blob worker, and `vs/vs/`), so language workers could never load. | Seen as 404 `/vs/vs/language/...` | Fixed (absolute base from the llama-server) |
| 12 | `<input type=file>` (vision attach) and blob downloads need a `WebChromeClient`/handler that didn't exist; `alert/confirm/prompt` were also unsupported. | Code reading + known WebView behaviour | Fixed: `WebChromeClient` added; all 11 confirms / 5 prompts now use themed in-app dialogs; `alert()` → toast; "Download .md" writes into `Download/` |
| 13 | Resume from background blocked on a synchronous HTTP probe inside `Native.info()` and rebuilt the Settings UI. | Code reading | Fixed (throttled off-thread probe; UI only rebuilt if Settings is open) |
| 14 | Packaged web assets only refreshed when `index.html`'s byte-size changed (stale `vendor/` after updates). | Code reading | Fixed (keyed to app update time) |

Hypotheses I tested and **dropped**: smooth-scroll "scroll from top" after re-render (doesn't happen), `md()` and `save()` cost (negligible at normal sizes).

## 2. New: file viewer ported into the code editor
* **Explorer drawer** inside the editor built on a new shared `FileBrowser` (breadcrumbs, filter, sort, select mode, copy/cut/paste, rename, delete, new file/folder, long-press menu, paged lists (300/page), per-folder cache + scroll restore).
* **Multi-tab buffers** (per-tab undo, cursor, scroll; dirty dot; up to 12).
* **Quick open** (fuzzy file name) and **project text search** (new native `search_text`).
* **Crash-safe drafts** (IndexedDB) + session restore; Android killing the WebView no longer loses work.
* **External-change detection**: clean tabs auto-reload; dirty tabs get a Reload / Keep-mine bar; saves carry `expect_mtime` so agent edits are never silently overwritten.
* Touch key bar: arrows with hold-repeat, **Sel** mode, undo/redo, Home/End, comment, duplicate/delete line, more symbols; taps don't dismiss the keyboard. Tab bar hides when the keyboard is up.
* Find **and replace** (case/regex, counter); Go-to-line; font size; optional autosave; Monaco tuned for phones (no hover/suggest popups, accessibility mode off).
* AI edits now apply as a minimal range edit with their own undo step (cursor/scroll kept) instead of replacing the whole buffer.
* Chat code blocks have an **Edit** button → opens a scratch tab.
* Replies show **tok/s and context use**.

Test results: editor suite 25/25, explorer/key-bar suite 13/13, AI-edit chain verified, native `FileTools` 14/14.

## 3. Not done / needs your device
* Everything above is untested on real hardware: touch feel, Samsung keyboard/IME behaviour with Monaco, and the real FUSE speed-up.
* **File Viewer itself** is unchanged apart from fixing its editor hand-off (still renders up to 2,000 rows unpaged).
* Suggested next (not applied because they change behaviour and need on-device tuning):
  * Partial wake-lock is held the whole time the server is alive: release when idle (battery).
  * llama-server flags: `-fa` + `--cache-type-k/v q8_0` and 16k ctx would roughly double usable context in the same RAM.
  * PDF path ships whole files as base64 through `evaluateJavascript` (large PDFs spike memory); stream via `shouldInterceptRequest` instead.
  * "Remember my approval" for `forge_tool` is a blanket, permanent approval of arbitrary code; I'd exclude it.
  * `www/vs` (Monaco) is fetched by CI, not included in this zip.

## 4. Files changed
`www/index.html` (UI), `FileTools.kt` (stat, search_text, atomic write, `expect_mtime`, mtime in entries, faster listing), `MainActivity.kt` (WebChromeClient, non-blocking `info()`, asset refresh). `LlamaService.kt` / `MemoryTools.kt` / `PdfTools.kt` untouched.


---
## Round 2

### 0. The agent could not see any tool output (pre-existing, critical)
The model received `[Listed .: 4 found]`, `[Read file (63 chars)]`, `[Web search: 5 results used]` instead of the real results. `apiMsgs` kept results in full only for the *last* assistant message, but the agent loop starts a new assistant message for every step, so results were condensed the moment the model needed them. Reproduced on build-26, fixed: results stay full for every step since your latest message and are condensed only on later turns (context saving preserved). Regression test: the fake model echoes what it was given: build-26 **fails** (list_dir, read_file), build-27 passes. Web search flows through the same path.

### 1. Flash attention + quantized KV cache (on by default)
`-fa on -ctk q8_0 -ctv q8_0` unless you set something explicitly in Settings → Power (an explicit value, including `off`/`f16`, always wins). A quantized V cache requires flash attention, so FA is forced on in that case. Safety net: if the server dies on boot with these defaults it retries once with f16/auto, and remembers that if the retry works. Flags were checked against llama.cpp `master` (`-fa [on|off|auto]`, `-ctk`/`-ctv`). Default context stays 8192; with the q8 cache, 16384 costs about what 8192 used to, so raise it in Settings if you want longer chats.

### 2. "?" explanations
Every setting (64/64) has a small **?**; tap it for a short explanation toast (tap the toast to dismiss). Covers sampling, repetition/DRY/Mirostat, server (ctx, threads, batch, flash attention, KV types, mlock…), generation, tools, GitHub and web-search keys. Text lives in the `HELP` table in `index.html`.

### 3. PDFs
* Only the first **25 pages** of any PDF were ever rendered (hard cap). Now every page is reachable: pages render lazily as you scroll, off-screen canvases are released (150-page test: peak 14 canvases), the page label tracks your scroll, and your scroll position survives edits.
* The pdf.js **worker never loaded** (it was probed with `fetch` on `file://`, which isn't supported), so parsing ran on the UI thread and froze the app. It now runs in a real background worker.
* Files no longer travel through the JS bridge as one giant base64 string: the native layer streams them at `https://kojot.fs/<path>` (same workspace jail and file gates as every other file op; falls back to the old path on an older native layer). Used by the viewer, text extraction, conversions and image preview.
* Not changed: pdf-lib still parses the whole document on open (needed for form-filling/stamping); on very large PDFs a lazy load would be the next step.

### 4. File-system buttons
In build-26 Rename/New folder/Delete (and ~20 other actions) called `prompt()`/`confirm()`, which do nothing in an Android WebView. Round 1 replaced them with in-app dialogs; every File Viewer toolbar button is now tested (new folder, rename, copy/paste, delete). Rename was also hard to find (only via checkbox + toolbar), so the file sheet now has **Rename, Copy, Cut, Copy path**, and folders get a sheet without the file-only actions. A smoke test that clicks every button on the sidebar, chat, File Viewer, editor and Settings found no button that throws.

### Files changed in round 2
`www/index.html`, `LlamaService.kt` (FA/KV defaults + fallback), `MainActivity.kt` (`kojot.fs` stream, shared `fileTools()`).
Verification: Kotlin compiles against Android 34 / Kotlin 1.9.24; JS suites: editor 25/25, explorer 13/13, File Viewer 7/7 + 6/6, tool delivery 3/3, help 64/64. Not run on a physical device.

package com.kojot.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var tap: (() -> Unit)? = null
    private lateinit var msg: TextView
    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("lc", Context.MODE_PRIVATE) }
    /** Prefer external path (no copy). Never require a second copy under filesDir. */
    private fun resolveModel(prefKey: String, fallbackName: String): File {
        val external = prefs.getString(prefKey, null)
        if (!external.isNullOrBlank()) {
            val f = File(external)
            if (f.isFile && f.canRead()) return f
        }
        return File(filesDir, fallbackName)
    }
    private fun modelFile(): File = resolveModel("model_path", "model.gguf")
    private fun coderModelFile(): File = resolveModel("coder_path", "coder.gguf")
    private fun embModelFile(): File = resolveModel("emb_path", "nomic.gguf")
    private val model get() = modelFile()
    private val embModel get() = embModelFile()
    private val coderModel get() = coderModelFile()

    /** Default: /sdcard/Download/Local Models — optional subfolders: llm, embed, coder, image, ocr, decision */
    private val modelSubdirs = listOf("llm", "embed", "coder", "image", "ocr", "decision")

    private fun modelsDir(): File {
        val saved = prefs.getString("models_dir", null)
        val root = if (!saved.isNullOrBlank()) File(saved) else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models")
        if (!root.exists()) try { root.mkdirs() } catch (_: Exception) {}
        // Ensure category folders exist (user can drop GGUFs into any of them)
        for (sub in modelSubdirs) {
            try { File(root, sub).mkdirs() } catch (_: Exception) {}
        }
        return root
    }

    private fun categoryOfModel(f: File, root: File): String {
        return try {
            val rel = f.parentFile?.canonicalPath?.removePrefix(root.canonicalPath)?.trimStart('/', '\\') ?: ""
            val top = rel.substringBefore('/').substringBefore('\\').lowercase()
            if (top in modelSubdirs) top else "root"
        } catch (_: Exception) { "root" }
    }
    private var memory: MemoryTools? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private fun acquireWakeLock(tag: String = "kojot:work", ms: Long = 60 * 60 * 1000L) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(ms)
            }
        } catch (_: Exception) {}
    }
    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(i)
            }
        } catch (_: Exception) {}
    }

    @Volatile private var backActive = false
    private var llama: LlamaService? = null
    private var bound = false
    private val activeSlot: String get() = llama?.activeSlot ?: "chat"
    private val embReady: Boolean get() = llama?.embReady == true

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LlamaService.LocalBinder
            llama = binder.getService()
            bound = true
            llama?.addReadyListener(readyCb)
            if (llama?.ready == true) {
                runOnUiThread { showWeb() }
            } else if (llama?.isChatAlive() != true && model.exists()) {
                prepareWww()
                llama?.startServers()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            llama?.removeReadyListener(readyCb)
            llama = null
            bound = false
        }
    }

    private val readyCb: (Boolean, String?) -> Unit = { ok, err ->
        runOnUiThread {
            // Always prefer local file:// UI — never depend on :8080 for chrome
            loadLocalUi(force = false)
            if (ok) {
                notifyModelLoading(false, "Model ready")
            } else {
                val msgText = err ?: "Model server failed"
                notifyModelLoading(false, msgText)
                if (web.visibility != View.VISIBLE) {
                    msg.visibility = View.VISIBLE
                    msg.text = msgText + if (hasCustomArgs()) "\n\nTap to reset server settings and retry." else "\n\nTap to retry."
                    tap = {
                        if (hasCustomArgs()) prefs.edit().remove("srv").remove("srv_coder").apply()
                        restartServer()
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backActive) {
            runOnUiThread { web.evaluateJavascript("window.__goBack()", null) }
        } else {
            super.onBackPressed()
        }
    }

    @Synchronized private fun mem(): MemoryTools {
        memory?.let { return it }
        try {
            return MemoryTools(File(filesDir, "memory.db")) { rootDir() }.also { memory = it }
        } catch (e: Throwable) {
            throw ToolError("memory engine failed to start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        msg = TextView(this).apply {
            setTextColor(0xFFECECEC.toInt()); textSize = 16f
            gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
            setOnClickListener { tap?.invoke() }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            // UI is loaded from filesDir; chat API is still http://127.0.0.1:8080
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setBackgroundColor(0xFF202020.toInt()); visibility = View.GONE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "file") return false
                    if (u.host == "127.0.0.1" && (u.port == 8080 || u.port == 8081 || u.port == 8082)) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    // Model server down — fall back to local UI so the app is never a blank error page
                    val url = request.url?.toString().orEmpty()
                    if (url.startsWith("http://127.0.0.1:8080")) {
                        Log.w("Kojot", "8080 unavailable (${error.description}); loading local UI")
                        view.post { loadLocalUi(force = true) }
                    }
                }
            }
            addJavascriptInterface(Bridge(), "Native")
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF202020.toInt()); addView(msg); addView(web)
        })
        try {
            memory = MemoryTools(File(filesDir, "memory.db")) { rootDir() }
        } catch (e: Exception) {
            Log.e("Kojot", "Memory init failed: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99) } catch (_: Exception) {}
        }
        if (!prefs.getBoolean("battery_asked", false)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            requestBatteryExemption()
        }
        if (model.exists()) {
            prepareWww()
            // Show local UI immediately — do not wait for llama-server (avoids ERR_CONNECTION_REFUSED)
            showWeb()
            bindAndStartService()
        } else pickModel()
    }

    private fun bindAndStartService() {
        LlamaService.start(this)
        if (!bound) {
            bindService(Intent(this, LlamaService::class.java), conn, Context.BIND_AUTO_CREATE)
        } else {
            // Already bound — just ensure processes are up
            prepareWww()
            if (llama?.isChatAlive() != true) llama?.startServers()
        }
    }

    private fun uiIndexFile(): File = File(filesDir, "www/index.html")

    /** Load the chat UI from app files (independent of llama-server). */
    private fun loadLocalUi(force: Boolean = false) {
        prepareWww()
        val index = uiIndexFile()
        if (!index.isFile) {
            msg.visibility = View.VISIBLE
            msg.text = "UI assets missing. Reinstall the app."
            return
        }
        msg.visibility = View.GONE
        web.visibility = View.VISIBLE
        val target = "file://${index.absolutePath}"
        val cur = web.url.orEmpty()
        if (force || cur.isBlank() || cur == "about:blank" || cur.startsWith("http://127.0.0.1")) {
            web.loadUrl(target)
        }
    }

    private fun showWeb() {
        loadLocalUi(force = false)
    }

    /** In-UI frosted toast instead of hiding the WebView (avoids "app minimized" feel). */
    private fun notifyModelLoading(loading: Boolean, message: String = "Model loading…") {
        runOnUiThread {
            // Keep WebView visible whenever possible — local UI, not 8080
            if (web.visibility != View.VISIBLE && model.exists()) {
                loadLocalUi(force = false)
            }
            val safe = JSONObject.quote(message)
            val js = "try{window.__modelLoading&&window.__modelLoading($loading,$safe)}catch(e){}"
            try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
            // Fallback native text only if web isn't up yet
            if (web.visibility != View.VISIBLE) {
                msg.visibility = View.VISIBLE
                msg.text = message
            }
        }
    }

    /**
     * Copy packaged www (index + Monaco vs/ + vendor) into filesDir.
     * Avoids deleteRecursively on every call so an open file:// UI is not wiped mid-session.
     */
    private fun prepareWww() {
        val www = File(filesDir, "www")
        val index = File(www, "index.html")
        val hasWww = try { (assets.list("www") ?: emptyArray()).isNotEmpty() } catch (_: Exception) { false }
        // Refresh when missing, or when packaged index is newer/different size
        val needCopy = !index.isFile || run {
            try {
                val assetLen = assets.open("www/index.html").use { it.available().toLong() }
                index.length() != assetLen
            } catch (_: Exception) {
                try {
                    assets.open("index.html").use { it.available().toLong() } != index.length()
                } catch (_: Exception) { true }
            }
        }
        if (!needCopy) return
        www.mkdirs()
        if (hasWww) {
            // Overlay copy (do not delete vs/ mid-flight)
            copyAssetDir("www", www)
        } else {
            try {
                assets.open("index.html").use { i -> File(www, "index.html").outputStream().use { i.copyTo(it) } }
            } catch (e: Exception) {
                Log.e("Kojot", "prepareWww failed: ${e.message}")
            }
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val list = assets.list(assetPath) ?: emptyArray()
        if (list.isEmpty()) {
            assets.open(assetPath).use { i -> dest.outputStream().use { i.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            copyAssetDir(childAsset, File(dest, name))
        }
    }

    private fun pickModel() {
        tap = { pickModel() }
        msg.text = "Tap to choose your model (.gguf file).\nAny GGUF model works — it's loaded locally."
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_MODEL)
    }

    private fun pickEmbedding() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_EMB)
    }

    private fun pickCoder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_CODER)
    }

    /**
     * Resolve a real filesystem path from a content/file URI so we never copy multi-GB GGUFs.
     * Samsung/SAF often hide MediaStore DATA — use DocumentsContract + recursive name search.
     */
    private fun resolveRealPath(uri: Uri, displayName: String? = null): String? {
        fun readable(f: File?): String? =
            try { if (f != null && f.isFile && f.canRead()) f.canonicalPath else null } catch (_: Exception) { null }

        try {
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                readable(File(uri.path ?: return null))?.let { return it }
            }

            // MediaStore / openable columns (often null on scoped storage)
            contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) readable(File(c.getString(idx) ?: ""))?.let { return it }
                }
            }
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    for (col in arrayOf("_data", "filePath", "path", android.provider.MediaStore.MediaColumns.DATA)) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) readable(File(c.getString(idx) ?: ""))?.let { return it }
                    }
                }
            }

            // DocumentsContract: primary:Download/Local Models/foo.gguf  or  raw:/storage/...
            val docId = try {
                android.provider.DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) { null }
            if (!docId.isNullOrBlank()) {
                when {
                    docId.startsWith("raw:") -> {
                        readable(File(docId.removePrefix("raw:")))?.let { return it }
                    }
                    docId.startsWith("msd:") -> {
                        // Some OEMs
                        readable(File(docId.substringAfter(':')))?.let { return it }
                    }
                    docId.contains(':') -> {
                        val rel = java.net.URLDecoder.decode(docId.substringAfter(':'), "UTF-8")
                            .replace(':', '/')
                        val primary = File(Environment.getExternalStorageDirectory(), rel)
                        readable(primary)?.let { return it }
                        // Also try as absolute if it already looks like one
                        if (rel.startsWith('/')) readable(File(rel))?.let { return it }
                    }
                }
            }

            // URI path segment decode (content://com.android.externalstorage.documents/document/primary%3A...)
            uri.lastPathSegment?.let { seg ->
                val decoded = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (_: Exception) { seg }
                if (decoded.contains(':')) {
                    val rel = decoded.substringAfter(':').replace(':', '/')
                    readable(File(Environment.getExternalStorageDirectory(), rel))?.let { return it }
                }
                if (decoded.startsWith("/storage/") || decoded.startsWith("/sdcard/")) {
                    readable(File(decoded))?.let { return it }
                }
            }

            // Name-based search: models folder (recursive) + Downloads
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                    ?.let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }
            if (!name.isNullOrBlank() && name.endsWith(".gguf", ignoreCase = true)) {
                // Direct candidates
                val direct = listOf(
                    File(modelsDir(), name),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models/$name"),
                    File(Environment.getExternalStorageDirectory(), "Download/$name"),
                    File(Environment.getExternalStorageDirectory(), "Download/Local Models/$name"),
                    File(Environment.getExternalStorageDirectory(), "Downloads/$name"),
                    File(Environment.getExternalStorageDirectory(), "Downloads/Local Models/$name")
                )
                for (f in direct) readable(f)?.let { return it }

                // Recursive under modelsDir (llm/embed/coder/ocr/…) and Download/Local Models
                fun findNamed(root: File, depth: Int = 0): File? {
                    if (depth > 4 || !root.isDirectory) return null
                    val kids = root.listFiles() ?: return null
                    for (k in kids) {
                        if (k.isFile && k.name.equals(name, ignoreCase = true)) return k
                    }
                    for (k in kids) {
                        if (k.isDirectory && !k.name.startsWith(".")) {
                            findNamed(k, depth + 1)?.let { return it }
                        }
                    }
                    return null
                }
                readable(findNamed(modelsDir()))?.let { return it }
                val dlLocal = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models")
                readable(findNamed(dlLocal))?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun bindExternalModel(r: Int, realPath: String, fileName: String?) {
        val name = fileName ?: File(realPath).name
        val ed = prefs.edit()
        when (r) {
            REQ_MODEL -> {
                ed.putString("model_path", realPath).putString("model_name", name)
                File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
            }
            REQ_CODER -> {
                ed.putString("coder_path", realPath).putString("coder_name", name)
                File(filesDir, "coder.gguf").let { if (it.exists()) it.delete() }
            }
            REQ_EMB -> {
                ed.putString("emb_path", realPath).putString("emb_name", name)
                File(filesDir, "nomic.gguf").let { if (it.exists()) it.delete() }
            }
        }
        ed.apply()
        runOnUiThread {
            msg.text = "Using from folder (no copy):\n$name"
            when (r) {
                REQ_MODEL -> start()
                REQ_EMB -> {
                    if (llama != null) llama?.restartServers() else bindAndStartService()
                }
                // Coder: path only — loads on demand via ensure_slot (avoids full restart OOM)
                REQ_CODER -> { /* no process start */ }
            }
            try {
                web.evaluateJavascript("try{window.ninfo&&window.ninfo();if(window.filesUI)filesUI()}catch(e){}", null)
            } catch (_: Exception) {}
        }
    }

    @Deprecated("plain Activity keeps this dependency-free")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (c != RESULT_OK || d?.data == null) {
            if (r == REQ_MODEL) msg.text = "No file chosen. Tap to try again."
            return
        }
        val uri = d.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}

        val fileName = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }

        when (r) {
            REQ_MODEL -> fileName?.let { prefs.edit().putString("model_name", it).apply() }
            REQ_EMB -> fileName?.let { prefs.edit().putString("emb_name", it).apply() }
            REQ_CODER -> fileName?.let { prefs.edit().putString("coder_name", it).apply() }
        }

        // Always try zero-copy for ALL model types (chat, coder, embedding)
        val realPath = resolveRealPath(uri, fileName)
        if (realPath != null) {
            bindExternalModel(r, realPath, fileName)
            return
        }

        // Last resort: refuse silent multi-GB copy — guide to in-app list (real paths)
        val dir = modelsDir().path
        runOnUiThread {
            msg.text = "Could not resolve a real path (no copy).\nPut the .gguf in:\n$dir\n(or llm/ embed/ coder/ ocr/ subfolders)\nthen use Settings → Models list → Chat/Coder/Embed/OCR buttons.\nSystem file picker often fails on Samsung SAF."
            try {
                web.evaluateJavascript(
                    "try{alert(${JSONObject.quote(
                        "Could not map that file to a real path (Samsung/Android blocks it).\\n\\n" +
                        "1. Copy or move the .gguf into:\\n$dir\\n" +
                        "   (subfolders llm, embed, coder, ocr are fine)\\n" +
                        "2. Open Settings → Models folder → Refresh\\n" +
                        "3. Tap Chat / Coder / Embed / OCR on the model card\\n\\n" +
                        "Kojot will not create a second multi-GB copy."
                    )})}catch(e){}",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    private fun activeModelFile(): File {
        return if (activeSlot == "coder" && coderModel.exists()) coderModel else model
    }

    private fun hasCustomArgs(): Boolean {
        val srvKey = if (activeSlot == "coder") "srv_coder" else "srv"
        val s = prefs.getString(srvKey, "{}") ?: "{}"
        return s != "{}" && s.isNotBlank()
    }

    private fun restartServer() {
        notifyModelLoading(true, "Restarting model…")
        prepareWww()
        if (llama != null) llama?.restartServers()
        else {
            bindAndStartService()
        }
    }

    private fun waitForService(timeoutMs: Long = 15000L): LlamaService? {
        if (llama != null) return llama
        prepareWww()
        bindAndStartService()
        val step = 200L
        var waited = 0L
        while (llama == null && waited < timeoutMs) {
            try { Thread.sleep(step) } catch (_: InterruptedException) { break }
            waited += step
        }
        return llama
    }

    private fun swapModel(slot: String): JSONObject {
        val svc = waitForService()
        if (svc == null) {
            return JSONObject().put("ok", false).put("error", "Server not ready — open the app fully, then try again").put("slot", activeSlot)
        }
        // Free vision OCR RAM before loading another GGUF (stops "app keeps stopping" OOM)
        try { svc.stopOcr() } catch (_: Exception) {}
        notifyModelLoading(true, if (slot == "coder") "Loading coder model…" else "Loading chat model…")
        val result = svc.swapModel(slot)
        notifyModelLoading(false, if (result.optBoolean("ok")) "Model ready" else (result.optString("error", "Swap failed")))
        if (result.optBoolean("ok")) {
            runOnUiThread { showWeb() }
        }
        return result
    }

    private fun start() {
        tap = null
        notifyModelLoading(true, "Loading model…")
        prepareWww()
        bindAndStartService()
    }

    private fun filesOk(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    private fun base(): String = Environment.getExternalStorageDirectory().path
    private fun rootDir(): File = File(prefs.getString("root", null) ?: base())

    private fun reply(id: Int, ok: Boolean, s: String) {
        runOnUiThread { web.evaluateJavascript("window.__nat($id,$ok,${JSONObject.quote(s)})", null) }
    }

    private fun http(a: JSONObject): JSONObject {
        val u = URL(a.getString("url"))
        if (u.protocol != "http" && u.protocol != "https") throw ToolError("only http(s) URLs are allowed")
        val c = u.openConnection() as HttpURLConnection
        c.requestMethod = a.optString("method", "GET").uppercase()
        c.connectTimeout = 15000; c.readTimeout = 25000
        a.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> c.setRequestProperty(k, h.optString(k)) } }
        val body = a.optString("body", "")
        if (body.isNotEmpty() && c.requestMethod != "GET") {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val st = if (code >= 400) c.errorStream else c.inputStream
        val txt = st?.bufferedReader()?.use { r ->
            val sb = StringBuilder(); val buf = CharArray(8192); var n: Int
            while (r.read(buf).also { n = it } > 0 && sb.length < 300_000) sb.append(buf, 0, n)
            sb.toString()
        } ?: ""
        return JSONObject().put("status", code).put("body", txt)
    }

    private val memoryOps = setOf(
        "save_fact", "recall_memory", "recall_files", "sync_file_index",
        "start_indexing", "get_indexing_status", "cancel_indexing",
        "list_facts", "clear_facts", "update_fact", "memory_stats",
        "get_user_anchor", "set_user_anchor"
    )

    private fun getVitals(): JSONObject {
        val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val bStatus = registerReceiver(null, ifilter)
        val level = bStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val tempC = (bStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val status = bStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL

        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalRamMb = mi.totalMem shr 20
        val availRamMb = mi.availMem shr 20

        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) shr 20

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                else -> "unknown"
            }
        } else "legacy"

        val stat = Environment.getDataDirectory()
        val freeDiskGb = String.format(java.util.Locale.US, "%.1f", stat.freeSpace / 1073741824.0)

        val txt = "Battery: ${batteryPct}% (${if (charging) "charging" else "discharging"}, ${tempC}°C) · RAM: ${availRamMb}/${totalRamMb}MB free (Kojot: ${heapUsedMb}MB) · Thermal: ${thermal} · Storage: ${freeDiskGb}GB free"

        return JSONObject()
            .put("text", txt)
            .put("data", JSONObject()
                .put("battery_pct", batteryPct)
                .put("temp_c", tempC)
                .put("charging", charging)
                .put("avail_ram_mb", availRamMb)
                .put("total_ram_mb", totalRamMb)
                .put("heap_used_mb", heapUsedMb)
                .put("thermal", thermal)
                .put("free_disk_gb", freeDiskGb)
            )
    }

    private fun dispatch(op: String, a: JSONObject): JSONObject = when (op) {
        "get_vitals" -> getVitals()
        "http" -> http(a)
        "delete_coder" -> {
            // Only drop the pointer / optional local copy — never delete the user's only GGUF in models folder
            prefs.edit().remove("coder_path").remove("coder_name").remove("srv_coder").apply()
            File(filesDir, "coder.gguf").let { if (it.exists()) it.delete() }
            if (activeSlot == "coder") swapModel("chat")
            JSONObject().put("ok", true).put("text", "coder model unbound (folder file kept)")
        }
        "delete_emb" -> {
            prefs.edit().remove("emb_path").remove("emb_name").apply()
            File(filesDir, "nomic.gguf").let { if (it.exists()) it.delete() }
            llama?.restartServers()
            JSONObject().put("ok", true).put("text", "embedding model unbound (folder file kept)")
        }
        "delete_ocr" -> {
            prefs.edit().remove("ocr_path").remove("ocr_name").remove("image_path").remove("image_name").apply()
            try { llama?.stopOcr() } catch (_: Exception) {}
            JSONObject().put("ok", true).put("text", "OCR model unbound (folder file kept; ML Kit still available)")
        }
        "pick_coder" -> {
            runOnUiThread { pickCoder() }
            JSONObject().put("text", "coder picker opened")
        }
        "pick_model" -> {
            runOnUiThread { pickModel() }
            JSONObject().put("text", "model picker opened")
        }
        "delete_model" -> {
            // Unbind only — does NOT delete the GGUF in the user's models folder
            prefs.edit().remove("model_path").remove("model_name").apply()
            File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
            // Stop processes so UI does not keep showing a dead server; do not wipe coder/emb
            try { llama?.stopServers() } catch (_: Exception) {}
            JSONObject().put("ok", true).put("text", "main model unbound (folder file kept; chats/memory kept)")
        }
        "set_models_dir" -> {
            val p = a.optString("path").ifBlank {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models").path
            }
            val d = File(p)
            if (!d.exists()) try { d.mkdirs() } catch (_: Exception) {}
            if (!d.isDirectory) throw ToolError("not a folder: $p")
            prefs.edit().putString("models_dir", d.canonicalPath).apply()
            // recreate category subfolders under the new root
            modelsDir()
            JSONObject().put("text", d.canonicalPath).put("path", d.canonicalPath)
                .put("subdirs", org.json.JSONArray(modelSubdirs))
        }
        "get_models_dir" -> {
            val d = modelsDir()
            JSONObject().put("text", d.path).put("path", d.path)
                .put("subdirs", org.json.JSONArray(modelSubdirs))
        }
        "list_models_dir" -> {
            val d = modelsDir()
            if (!d.isDirectory) throw ToolError("models folder missing: ${d.path}")
            val found = ArrayList<File>()
            fun walk(dir: File, depth: Int) {
                if (depth > 3) return
                val kids = dir.listFiles() ?: return
                for (c in kids) {
                    if (c.isDirectory) walk(c, depth + 1)
                    else if (c.isFile && c.name.endsWith(".gguf", true)) found.add(c)
                }
            }
            walk(d, 0)
            found.sortBy { it.absolutePath.lowercase() }
            val arr = org.json.JSONArray()
            found.forEach { f ->
                val cat = categoryOfModel(f, d)
                // Suggested slot from folder name
                val suggest = when (cat) {
                    "embed" -> "emb"
                    "coder" -> "coder"
                    "llm", "decision" -> "chat"
                    "image", "ocr" -> cat // not bound as chat by default
                    else -> "chat"
                }
                arr.put(JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("size", f.length())
                    .put("mb", f.length() / 1048576.0)
                    .put("category", cat)
                    .put("suggest_slot", suggest))
            }
            JSONObject().put("text", "${found.size} GGUF under ${d.path}")
                .put("path", d.path)
                .put("subdirs", org.json.JSONArray(modelSubdirs))
                .put("models", arr)
        }
        "select_model" -> {
            val path = a.optString("path")
            var slot = a.optString("slot", "chat") // chat | coder | emb
            val f = File(path)
            if (!f.isFile || !f.canRead()) throw ToolError("not readable: $path")
            // Auto-slot from parent folder when caller passes "auto"
            if (slot == "auto" || slot.isBlank()) {
                val cat = categoryOfModel(f, modelsDir())
                slot = when (cat) {
                    "embed" -> "emb"
                    "coder" -> "coder"
                    "ocr" -> "ocr"
                    "image" -> "image"
                    else -> "chat"
                }
            }
            when (slot) {
                "coder" -> {
                    // Path only — process loads on demand via ensure_slot/swap (no full restart).
                    prefs.edit().putString("coder_path", f.canonicalPath).putString("coder_name", f.name).apply()
                    File(filesDir, "coder.gguf").let { if (it.exists()) it.delete() }
                }
                "emb", "embedding" -> {
                    prefs.edit().putString("emb_path", f.canonicalPath).putString("emb_name", f.name).apply()
                    File(filesDir, "nomic.gguf").let { if (it.exists()) it.delete() }
                    runOnUiThread {
                        try { llama?.stopOcr() } catch (_: Exception) {}
                        // Embed needs its own process on :8081
                        if (llama != null) llama?.restartServers() else bindAndStartService()
                    }
                }
                "ocr", "image" -> {
                    // Path only — OCR server starts on demand (first ocr_pdf/ocr_image) to avoid OOM
                    // Always persist under ocr_* keys when binding OCR so Settings / ensureOcr see it.
                    val pathKey = if (slot == "ocr") "ocr_path" else "image_path"
                    val nameKey = if (slot == "ocr") "ocr_name" else "image_name"
                    prefs.edit().putString(pathKey, f.canonicalPath).putString(nameKey, f.name).apply()
                    // If user dropped a vision model in OCR folder, also set ocr_path for the runner
                    if (slot == "image") {
                        // leave ocr_path alone unless empty
                        if (prefs.getString("ocr_path", null).isNullOrBlank()) {
                            prefs.edit().putString("ocr_path", f.canonicalPath).putString("ocr_name", f.name).apply()
                        }
                    }
                }
                else -> {
                    prefs.edit().putString("model_path", f.canonicalPath).putString("model_name", f.name).apply()
                    File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
                    runOnUiThread {
                        try { llama?.stopOcr() } catch (_: Exception) {}
                        restartServer()
                    }
                }
            }
            if (slot == "ocr") {
                JSONObject().put("ok", true)
                    .put("text", "bound ocr → ${f.name} (vision :8082 if mmproj present; else ML Kit fallback)")
                    .put("path", f.canonicalPath)
            } else if (slot == "image") {
                JSONObject().put("ok", true)
                    .put("text", "recorded image → ${f.name}")
                    .put("path", f.canonicalPath)
            } else {
                JSONObject().put("ok", true).put("text", "bound $slot → ${f.name} (no copy)").put("path", f.canonicalPath)
            }
        }
        "swap_model" -> swapModel(a.optString("slot", "chat"))
        "ensure_slot" -> {
            val role = a.optString("slot", a.optString("role", "chat"))
            val svc = waitForService()
            if (svc == null) {
                JSONObject().put("ok", false).put("error", "Server not ready")
            } else {
                val label = when (role.lowercase()) {
                    "coder" -> "Switching to coder…"
                    "ocr", "vision" -> "Loading OCR vision…"
                    "chat_resume", "resume" -> "Restoring chat model…"
                    "embed" -> "Starting embed server…"
                    else -> "Switching to chat…"
                }
                notifyModelLoading(true, label)
                val result = try {
                    svc.ensureSlot(role)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "ensure_slot failed")
                }
                notifyModelLoading(
                    false,
                    if (result.optBoolean("ok")) (result.optString("text", "Model ready").ifBlank { "Model ready" })
                    else result.optString("error", "Switch failed")
                )
                if (result.optBoolean("ok") && role.lowercase() in setOf("chat", "coder", "chat_resume", "resume")) {
                    runOnUiThread { showWeb() }
                }
                result
            }
        }
        "auto_bind_models" -> {
            // Fill empty slots from category folders (first readable GGUF). Does not overwrite existing binds.
            val root = modelsDir()
            val bound = mutableListOf<String>()
            fun firstGguf(sub: String): File? {
                val dir = File(root, sub)
                if (!dir.isDirectory) return null
                return dir.listFiles()
                    ?.filter { it.isFile && it.canRead() && it.name.endsWith(".gguf", true) && !it.name.contains("mmproj", true) }
                    ?.minByOrNull { it.length() }
            }
            if (prefs.getString("model_path", null).isNullOrBlank()) {
                firstGguf("llm")?.let { f ->
                    prefs.edit().putString("model_path", f.canonicalPath).putString("model_name", f.name).apply()
                    bound.add("chat→${f.name}")
                }
            }
            if (prefs.getString("coder_path", null).isNullOrBlank()) {
                firstGguf("coder")?.let { f ->
                    prefs.edit().putString("coder_path", f.canonicalPath).putString("coder_name", f.name).apply()
                    bound.add("coder→${f.name}")
                }
            }
            if (prefs.getString("emb_path", null).isNullOrBlank()) {
                firstGguf("embed")?.let { f ->
                    prefs.edit().putString("emb_path", f.canonicalPath).putString("emb_name", f.name).apply()
                    bound.add("embed→${f.name}")
                }
            }
            if (prefs.getString("ocr_path", null).isNullOrBlank()) {
                (firstGguf("ocr") ?: firstGguf("image"))?.let { f ->
                    prefs.edit().putString("ocr_path", f.canonicalPath).putString("ocr_name", f.name).apply()
                    bound.add("ocr→${f.name}")
                }
            }
            // Only restart if we newly bound chat or embed (need processes)
            val needBoot = bound.any { it.startsWith("chat") || it.startsWith("embed") }
            if (needBoot) {
                runOnUiThread {
                    try { llama?.stopOcr() } catch (_: Exception) {}
                    if (llama != null) llama?.restartServers() else bindAndStartService()
                }
            }
            JSONObject().put("ok", true)
                .put("bound", org.json.JSONArray(bound))
                .put("text", if (bound.isEmpty()) "No empty slots to fill" else "Auto-bound: ${bound.joinToString(", ")}")
        }
        "set_coder_srv" -> {
            val s = a.optJSONObject("srv") ?: JSONObject()
            prefs.edit().putString("srv_coder", s.toString()).apply()
            JSONObject().put("text", "saved coder srv")
        }
        "grant" -> {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            JSONObject().put("text", "opened")
        }
        "setroot" -> {
            val d = File(a.optString("path").ifBlank { base() })
            if (!d.isDirectory) throw ToolError("not a folder: ${d.path}")
            prefs.edit().putString("root", d.canonicalPath).apply()
            JSONObject().put("text", d.canonicalPath)
        }
        "set_file_gates" -> {
            val arr = a.optJSONArray("paths") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) list.add(s)
            }
            prefs.edit().putString("file_gates", JSONArray(list as Collection<*>).toString()).apply()
            JSONObject().put("text", "gates saved (${list.size})").put("paths", JSONArray(list as Collection<*>))
        }
        "get_file_gates" -> {
            val raw = prefs.getString("file_gates", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            JSONObject().put("text", "gates=${arr.length()}").put("paths", arr)
        }
        "restart" -> {
            prefs.edit().putString("srv", (a.optJSONObject("srv") ?: JSONObject()).toString()).apply()
            runOnUiThread { restartServer() }
            JSONObject().put("text", "restarting")
        }
        "pick_embedding" -> {
            runOnUiThread { pickEmbedding() }
            JSONObject().put("text", "picker opened")
        }
        in memoryOps -> {
            if (op == "start_indexing") {
                acquireWakeLock("kojot:indexer", 2 * 60 * 60 * 1000L)
                mem().startIndexing(a.optBoolean("force", false), a.optInt("max", 10000)) {
                    releaseWakeLock()
                }
            } else {
                mem().run(op, a)
            }
        }
        in PdfTools.OPS -> {
            if (!filesOk()) throw ToolError("Files access isn't granted yet. Open Settings → Tools → Files and tap Grant.")
            // Demand-driven: pause chat/coder, start vision OCR if bound+mmproj, then run tool
            if (op == "ocr_pdf" || op == "ocr_image") {
                try {
                    notifyModelLoading(true, "Loading OCR vision…")
                    llama?.ensureSlot("ocr")
                    notifyModelLoading(false, "OCR ready")
                } catch (e: Exception) {
                    android.util.Log.w("Kojot", "ensureOcr: ${e.message}")
                    notifyModelLoading(false, "OCR fallback")
                }
            }
            val pdfResult = PdfTools(rootDir(), applicationContext).run(op, a)
            if (op == "ocr_pdf" || op == "ocr_image") {
                try {
                    notifyModelLoading(true, "Restoring model after OCR…")
                    llama?.ensureSlot("chat_resume")
                    notifyModelLoading(false, "Model ready")
                } catch (_: Exception) {}
            }
            pdfResult
        }
        "stop_ocr" -> {
            try { llama?.stopOcr() } catch (_: Exception) {}
            JSONObject().put("ok", true).put("text", "OCR server stopped")
        }
        "ensure_ocr" -> {
            val ok = try { llama?.ensureOcr(60_000L) == true } catch (_: Exception) { false }
            JSONObject().put("ok", ok).put("ocr_ready", ok)
                .put("text", if (ok) "OCR vision server ready" else "OCR not available (ML Kit fallback)")
        }
        else -> {
            if (!filesOk()) throw ToolError("Files access isn't granted yet. Open Settings → Tools → Files and tap Grant.")
            val gatesRaw = prefs.getString("file_gates", "[]") ?: "[]"
            val gatesArr = try { JSONArray(gatesRaw) } catch (_: Exception) { JSONArray() }
            val gates = mutableListOf<String>()
            for (gi in 0 until gatesArr.length()) {
                val s = gatesArr.optString(gi, "").trim()
                if (s.isNotEmpty()) gates.add(s)
            }
            FileTools(rootDir(), gates).run(op, a)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun setBackActive(active: Boolean) {
            backActive = active
        }

        @JavascriptInterface
        fun info(): String {
            memory?.checkEmbedding()
            val m = modelFile()
            val coder = coderModelFile()
            val emb = embModelFile()
            val modelExt = !prefs.getString("model_path", null).isNullOrBlank() && m.isFile && m.path == File(prefs.getString("model_path", "")!!).path
            val coderExt = !prefs.getString("coder_path", null).isNullOrBlank() && coder.isFile
            val embExt = !prefs.getString("emb_path", null).isNullOrBlank() && emb.isFile
            return JSONObject()
                .put("files", filesOk())
                .put("root", rootDir().path)
                .put("base", base())
                .put("models_dir", modelsDir().path)
                .put("srv", try { JSONObject(prefs.getString("srv", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("srv_coder", try { JSONObject(prefs.getString("srv_coder", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("embed", embReady || (memory?.embOnline == true))
                .put("embed_model", emb.isFile)
                .put("emb_name", prefs.getString("emb_name", null) ?: emb.name)
                .put("emb_path", if (emb.isFile) emb.path else null)
                .put("emb_external", embExt)
                .put("coder_model", coder.isFile)
                .put("coder_name", prefs.getString("coder_name", null) ?: coder.name)
                .put("coder_path", if (coder.isFile) coder.path else null)
                .put("coder_external", coderExt)
                .put("active_slot", activeSlot)
                .put("model_present", m.isFile)
                .put("model_name", prefs.getString("model_name", null) ?: m.name)
                .put("model_path", m.path)
                .put("model_external", modelExt || (!prefs.getString("model_path", null).isNullOrBlank() && m.canRead()))
                .put("server_ready", llama?.ready == true)
                .put("ocr_path", prefs.getString("ocr_path", null))
                .put("ocr_ready", llama?.ocrReady == true)
                .put("ocr_name", prefs.getString("ocr_name", null))
                .put("image_path", prefs.getString("image_path", null))
                .put("image_name", prefs.getString("image_name", null))
                .toString()
        }

        @JavascriptInterface
        fun call(id: Int, op: String, args: String) {
            thread {
                try { reply(id, true, dispatch(op, JSONObject(args)).toString()) }
                catch (e: Exception) { reply(id, false, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            try { llama?.removeReadyListener(readyCb) } catch (_: Exception) {}
            try { unbindService(conn) } catch (_: Exception) {}
            bound = false
        }
        // Do NOT stop LlamaService here — FGS keeps the model alive across Activity recreation
        memory?.close()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MODEL = 1
        private const val REQ_EMB = 2
        private const val REQ_CODER = 3
    }
}

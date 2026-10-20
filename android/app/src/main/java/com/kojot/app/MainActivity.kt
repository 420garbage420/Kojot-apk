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
    private val embModel get() = File(filesDir, "nomic.gguf")
    private val coderModel get() = File(filesDir, "coder.gguf")

    /** Prefer external path (no copy) when set and still readable; else fall back to copied filesDir/model.gguf */
    private fun modelFile(): File {
        val external = prefs.getString("model_path", null)
        if (!external.isNullOrBlank()) {
            val f = File(external)
            if (f.isFile && f.canRead()) return f
        }
        return File(filesDir, "model.gguf")
    }
    private val model get() = modelFile()
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
            if (ok) showWeb()
            else {
                web.visibility = View.GONE
                msg.visibility = View.VISIBLE
                msg.text = err ?: "Model server failed"
                if (hasCustomArgs()) {
                    msg.text = (msg.text.toString()) + "\n\nTap to reset server settings and retry."
                    tap = { prefs.edit().remove("srv").remove("srv_coder").apply(); restartServer() }
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
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            setBackgroundColor(0xFF202020.toInt()); visibility = View.GONE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.host == "127.0.0.1" && (u.port == 8080 || u.port == 8081)) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
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

    private fun showWeb() {
        msg.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl("http://127.0.0.1:8080/")
    }

    private fun prepareWww() {
        val www = File(filesDir, "www").apply { deleteRecursively(); mkdirs() }
        val hasWww = try { (assets.list("www") ?: emptyArray()).isNotEmpty() } catch (_: Exception) { false }
        if (hasWww) copyAssetDir("www", www)
        else assets.open("index.html").use { i -> File(www, "index.html").outputStream().use { i.copyTo(it) } }
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

    /** Try to resolve a real filesystem path from a content/file URI (avoids GB-scale copies). */
    private fun resolveRealPath(uri: Uri): String? {
        try {
            if ("file".equals(uri.scheme, ignoreCase = true)) return uri.path
            // MediaStore / Downloads style
            contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) {
                        val path = c.getString(idx)
                        if (!path.isNullOrBlank() && File(path).canRead()) return path
                    }
                }
            }
            // Some providers expose _data
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    for (col in arrayOf("_data", "filePath", "path")) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) {
                            val path = c.getString(idx)
                            if (!path.isNullOrBlank() && File(path).canRead()) return path
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    @Deprecated("plain Activity keeps this dependency-free")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        val u = d?.data
        if (c != RESULT_OK || u == null) {
            if (r == REQ_MODEL) msg.text = "No file chosen. Tap to try again."
            return
        }
        val uri = d?.data ?: return
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

        val label = when (r) {
            REQ_EMB -> "embedding model"
            REQ_CODER -> "coder model"
            else -> "model"
        }

        // Prefer zero-copy: if we can get a real path, point at it directly (saves GB of storage + IO)
        val realPath = resolveRealPath(uri)
        if (realPath != null && r == REQ_MODEL) {
            prefs.edit().putString("model_path", realPath).apply()
            // Clean any previous copied model to reclaim space
            File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
            runOnUiThread {
                msg.text = "Using model from directory (no copy):\n${File(realPath).name}"
                start()
            }
            return
        }
        if (realPath != null && r == REQ_CODER) {
            // For coder we still prefer a stable local file for swap reliability, but record path
            prefs.edit().putString("coder_path", realPath).apply()
        }

        val dest = when (r) {
            REQ_MODEL -> File(filesDir, "model.gguf")
            REQ_EMB -> embModel
            REQ_CODER -> coderModel
            else -> return
        }
        thread {
            try {
                val tmp = File(filesDir, "$label.part")
                val total = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                contentResolver.openInputStream(uri)!!.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20); var n = 0L; var last = 0L; var k: Int
                        while (i.read(buf).also { k = it } > 0) {
                            o.write(buf, 0, k); n += k
                            if (n - last > (32 shl 20)) {
                                last = n
                                runOnUiThread { msg.text = "Copying $label… ${n shr 20} / ${total shr 20} MB" }
                            }
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                if (r == REQ_MODEL) {
                    // Clear external path so we use the stable copy
                    prefs.edit().remove("model_path").apply()
                }
                runOnUiThread {
                    if (r == REQ_MODEL) start()
                    else if (r == REQ_EMB) {
                        llama?.restartServers()
                        msg.text = if (web.visibility == View.VISIBLE) msg.text else "Embedding model installed."
                    } else if (r == REQ_CODER) {
                        // Ensure service sees the new file; one restart is enough (no double-pick)
                        if (llama != null) llama?.restartServers()
                        else bindAndStartService()
                        msg.text = if (web.visibility == View.VISIBLE) "Coder model installed." else "Coder model installed."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { msg.text = "Copy failed: ${e.message}\nTap to retry." }
            }
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
        web.visibility = View.GONE
        msg.visibility = View.VISIBLE
        msg.text = "Restarting model…"
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
        runOnUiThread {
            web.visibility = View.GONE
            msg.visibility = View.VISIBLE
            msg.text = "Switching model…"
        }
        val result = svc.swapModel(slot)
        if (result.optBoolean("ok")) {
            runOnUiThread { showWeb() }
        } else {
            // Don't leave user stuck on error screen — restore web so they can back out
            runOnUiThread {
                showWeb()
            }
        }
        return result
    }

    private fun start() {
        tap = null
        msg.text = "Loading model…"
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
        "list_facts", "clear_facts", "memory_stats", "get_user_anchor", "set_user_anchor"
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
            if (coderModel.exists()) coderModel.delete()
            prefs.edit().remove("coder_name").remove("srv_coder").apply()
            if (activeSlot == "coder") swapModel("chat")
            JSONObject().put("ok", true).put("text", "coder model deleted")
        }
        "delete_emb" -> {
            if (embModel.exists()) embModel.delete()
            prefs.edit().remove("emb_name").apply()
            // embedding process is owned by LlamaService; restart to drop it
            llama?.restartServers()
            JSONObject().put("ok", true).put("text", "embedding model deleted")
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
            // Remove both external pointer and any local copy; does NOT wipe chats/prefs/memory
            prefs.edit().remove("model_path").remove("model_name").apply()
            File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
            llama?.stopServers()
            JSONObject().put("ok", true).put("text", "main model removed (data preserved)")
        }
        "swap_model" -> swapModel(a.optString("slot", "chat"))
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
            PdfTools(rootDir(), applicationContext).run(op, a)
        }
        else -> {
            if (!filesOk()) throw ToolError("Files access isn't granted yet. Open Settings → Tools → Files and tap Grant.")
            FileTools(rootDir()).run(op, a)
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
            val embPresent = embModel.exists()
            val m = modelFile()
            val external = prefs.getString("model_path", null)
            return JSONObject()
                .put("files", filesOk())
                .put("root", rootDir().path)
                .put("base", base())
                .put("srv", try { JSONObject(prefs.getString("srv", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("srv_coder", try { JSONObject(prefs.getString("srv_coder", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("embed", embReady || (memory?.embOnline == true))
                .put("embed_model", embPresent)
                .put("emb_name", prefs.getString("emb_name", null))
                .put("coder_model", coderModel.exists())
                .put("coder_name", prefs.getString("coder_name", null))
                .put("active_slot", activeSlot)
                .put("model_present", m.isFile)
                .put("model_name", prefs.getString("model_name", null) ?: m.name)
                .put("model_path", m.path)
                .put("model_external", !external.isNullOrBlank() && File(external).canRead())
                .put("server_ready", llama?.ready == true)
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

package com.kojot.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "kojot:indexer")
            wakeLock?.acquire(30 * 60 * 1000L)
        } catch (_: Exception) {}
    }
    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private var proc: Process? = null
    private var embProc: Process? = null
    private var tap: (() -> Unit)? = null
    private lateinit var msg: TextView
    private lateinit var web: WebView
    private val model get() = File(filesDir, "model.gguf")
    private val embModel get() = File(filesDir, "nomic.gguf")
    private val prefs by lazy { getSharedPreferences("lc", Context.MODE_PRIVATE) }
    private var memory: MemoryTools? = null

    /** Open the memory DB lazily and report the real error if it fails. */
    @Synchronized private fun mem(): MemoryTools {
        memory?.let { return it }
        try {
            return MemoryTools(File(filesDir, "memory.db")) { rootDir() }.also { memory = it }
        } catch (e: Throwable) {
            throw ToolError("memory engine failed to start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    @Volatile private var embReady = false

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
        // Extract on-device APK compiler binaries (aapt2, android.jar, shell dex, keystore)
        thread {
            try {
                CompilerTools.ensureExtracted(this@MainActivity, File(filesDir, "compiler_tools"))
            } catch (e: Exception) {
                Log.w("Kojot", "compiler tools extract: ${e.message}")
            }
        }
        if (model.exists()) start() else pickModel()
    }

    // ---- model pickers ----------------------------------------------------------------------
    private fun pickModel() {
        tap = { pickModel() }
        msg.text = "Tap to choose your model (.gguf file).\nAny GGUF model works — it's loaded locally.\nCopied into the app once (needs free space equal to the file size)."
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_MODEL)
    }

    private fun pickEmbedding() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_EMB)
    }

    @Deprecated("plain Activity keeps this dependency-free")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        val u = d?.data
        if (c != RESULT_OK || u == null) {
            if (r == REQ_MODEL) msg.text = "No file chosen. Tap to try again."
            return
        }
        val dest = when (r) {
            REQ_MODEL -> model
            REQ_EMB -> embModel
            else -> return
        }
        val label = if (r == REQ_EMB) "embedding model" else "model"
        thread {
            try {
                val tmp = File(filesDir, "$label.part")
                val total = contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: 0L
                contentResolver.openInputStream(u)!!.use { i ->
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
                runOnUiThread {
                    if (r == REQ_MODEL) start()
                    else {
                        // Restart only the embedding process
                        thread { startEmbedding(File(applicationInfo.nativeLibraryDir, "libllama_server.so")) }
                        msg.text = if (web.visibility == View.VISIBLE) msg.text else "Embedding model installed. Restarting embed server…"
                        if (web.visibility == View.VISIBLE) {
                            // already in chat UI — nothing else to do
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { msg.text = "Copy failed: ${e.message}\nTap to retry." }
            }
        }
    }

    // ---- llama-server -----------------------------------------------------------------------
    private fun serverArgs(exe: String, www: String): List<String> {
        val s = try { JSONObject(prefs.getString("srv", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val a = mutableListOf(exe, "-m", model.path, "--host", "127.0.0.1", "--port", "8080", "--path", www, "--jinja")
        fun num(key: String, flag: String, def: Int) {
            val v = if (s.has(key)) s.optInt(key, 0) else def
            if (v > 0) { a.add(flag); a.add(v.toString()) }
        }
        fun word(key: String, flag: String) {
            val v = s.optString(key, "").trim()
            if (Regex("[a-z0-9_]{1,12}").matches(v)) { a.add(flag); a.add(v) }
        }
        num("ctx", "-c", 8192); num("threads", "-t", 4); num("threads_batch", "-tb", 0)
        num("batch", "-b", 0); num("ubatch", "-ub", 0); num("parallel", "-np", 1)
        word("fa", "-fa"); word("ctk", "-ctk"); word("ctv", "-ctv")
        if (s.optBoolean("mlock", false)) a.add("--mlock")
        if (s.optBoolean("no_mmap", false)) a.add("--no-mmap")
        s.optString("extra", "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { a.add(it) }
        return a
    }

    private fun hasCustomArgs(): Boolean {
        val s = prefs.getString("srv", "{}") ?: "{}"
        return s != "{}" && s.isNotBlank()
    }

    private fun restartServer() {
        val old = proc; proc = null
        val oldEmb = embProc; embProc = null
        embReady = false
        web.visibility = View.GONE; msg.visibility = View.VISIBLE
        start(old, oldEmb)
    }

    /** Recursively copy an assets folder into [dest]. Supports the offline Monaco tree under assets/www/. */
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

    private fun startEmbedding(exe: File) {
        embProc?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
        embProc = null
        embReady = false
        if (!embModel.exists()) {
            Log.i("llama", "No nomic.gguf — embedding server skipped")
            return
        }
        try {
            val args = listOf(
                exe.path, "-m", embModel.path,
                "--host", "127.0.0.1", "--port", "8081",
                "--embedding", "-c", "512", "-t", "2"
            )
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            embProc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama-emb", it) } }
            thread {
                repeat(60) {
                    if (!p.isAlive) return@thread
                    try {
                        val c = URL("http://127.0.0.1:8081/health").openConnection() as HttpURLConnection
                        c.connectTimeout = 500; c.readTimeout = 500
                        if (c.responseCode == 200) {
                            embReady = true
                            memory?.checkEmbedding()
                            Log.i("llama-emb", "embedding server ready")
                            return@thread
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }
        } catch (e: Exception) {
            Log.e("llama-emb", "failed to start: ${e.message}")
        }
    }

    private fun start(old: Process? = null, oldEmb: Process? = null) {
        tap = null
        msg.text = "Loading model…"
        thread {
            old?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
            oldEmb?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
            val www = File(filesDir, "www").apply { deleteRecursively(); mkdirs() }
            val hasWww = try { (assets.list("www") ?: emptyArray()).isNotEmpty() } catch (_: Exception) { false }
            if (hasWww) copyAssetDir("www", www)
            else assets.open("index.html").use { i -> File(www, "index.html").outputStream().use { i.copyTo(it) } }

            val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")

            // Launch embedding server in parallel (optional)
            startEmbedding(exe)

            val p = ProcessBuilder(serverArgs(exe.path, www.path)).redirectErrorStream(true).start()
            proc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama", it) } }
            repeat(300) {
                if (!p.isAlive) {
                    runOnUiThread {
                        msg.text = "Model server exited (code ${p.exitValue()}). See logcat tag 'llama'." +
                            if (hasCustomArgs()) "\n\nTap to reset server settings and retry." else ""
                        if (hasCustomArgs()) tap = { prefs.edit().remove("srv").apply(); restartServer() }
                    }
                    return@thread
                }
                try {
                    val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
                    if (c.responseCode == 200) {
                        runOnUiThread {
                            msg.visibility = View.GONE
                            web.visibility = View.VISIBLE
                            web.loadUrl("http://127.0.0.1:8080/")
                        }
                        return@thread
                    }
                } catch (_: Exception) {}
                Thread.sleep(1000)
            }
            runOnUiThread { msg.text = "Server didn't become ready in time." }
        }
    }

    // ---- JS bridge --------------------------------------------------------------------------
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
        "list_facts", "clear_facts", "memory_stats", "get_user_anchor", "set_user_anchor"
    )
    private val compilerOps = setOf("compile_app", "compiler_status")

    
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

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            when (pm.currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE -> "normal"
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> "light"
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                else -> "unknown"
            }
        } else "legacy"

        val stat = android.os.Environment.getDataDirectory()
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
                acquireWakeLock()
                mem().startIndexing(a.optBoolean("force", false), a.optInt("max", 10000)) {
                    releaseWakeLock()
                }
            } else {
                mem().run(op, a)
            }
        }
        in compilerOps -> {
            val tools = File(filesDir, "compiler_tools")
            CompilerTools.ensureExtracted(this, tools)
            CompilerTools(this, tools).run(op, a, rootDir())
        }
        "install_apk" -> {
            val path = a.optString("path", "").trim()
            if (path.isEmpty()) throw ToolError("path is required")
            val f = File(path).canonicalFile
            if (!f.isFile || !f.name.endsWith(".apk", ignoreCase = true))
                throw ToolError("APK not found: $path")
            runOnUiThread {
                try {
                    // Copy into cache and use content:// via a simple openFile approach is heavy;
                    // use ACTION_VIEW with FileProvider-free stream via public Downloads copy.
                    val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), f.name)
                    if (f.absolutePath != pub.absolutePath) {
                        f.copyTo(pub, overwrite = true)
                    }
                    val uri = Uri.parse("content://$packageName.provider${pub.absolutePath}")
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("Kojot", "install_apk: ${e.message}")
                }
            }
            JSONObject().put("text", "install prompt opened for ${f.name}")
        }
        else -> {
            if (!filesOk()) throw ToolError("Files access isn't granted yet. Open Settings → Tools → Files and tap Grant.")
            FileTools(rootDir()).run(op, a)
        }
    }

    @Volatile private var backActive = false

@Deprecated("Deprecated in Java")
override fun onBackPressed() {
    if (backActive) {
        runOnUiThread { web.evaluateJavascript("window.__goBack()", null) }
    } else {
        super.onBackPressed()
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
            return JSONObject()
                .put("files", filesOk())
                .put("root", rootDir().path)
                .put("base", base())
                .put("srv", try { JSONObject(prefs.getString("srv", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("embed", embReady || (memory?.embOnline == true))
                .put("embed_model", embPresent)
                .put("compiler", File(filesDir, "compiler_tools/aapt2").canExecute())
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
        proc?.destroy()
        embProc?.destroy()
        memory?.close()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MODEL = 1
        private const val REQ_EMB = 2
    }
}

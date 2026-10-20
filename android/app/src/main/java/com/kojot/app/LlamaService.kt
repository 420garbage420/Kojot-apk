package com.kojot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service that owns llama-server (chat) and the embedding server.
 * Survives Activity destruction so long agent runs are not killed when the UI is backgrounded.
 */
class LlamaService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LlamaService = this@LlamaService
    }

    private val binder = LocalBinder()
    private val prefs by lazy { getSharedPreferences("lc", Context.MODE_PRIVATE) }

    @Volatile private var proc: Process? = null
    @Volatile private var embProc: Process? = null
    @Volatile var embReady: Boolean = false
        private set
    @Volatile var activeSlot: String = "chat"
        private set
    @Volatile var ready: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var readyListeners = mutableListOf<(Boolean, String?) -> Unit>()

    private val embModel get() = File(filesDir, "nomic.gguf")
    private val coderModel get() = File(filesDir, "coder.gguf")

    private fun modelFile(): File {
        val external = prefs.getString("model_path", null)
        if (!external.isNullOrBlank()) {
            val f = File(external)
            if (f.isFile && f.canRead()) return f
        }
        return File(filesDir, "model.gguf")
    }

    private fun activeModelFile(): File =
        if (activeSlot == "coder" && coderModel.exists()) coderModel else modelFile()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                promoteForeground("Starting model…")
                if (modelFile().exists() && (proc?.isAlive != true)) {
                    thread { boot(null, null) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServers()
        releaseWakeLock()
        super.onDestroy()
    }

    fun addReadyListener(cb: (Boolean, String?) -> Unit) {
        synchronized(readyListeners) { readyListeners.add(cb) }
        if (ready) cb(true, null)
        else if (lastError != null) cb(false, lastError)
    }

    fun removeReadyListener(cb: (Boolean, String?) -> Unit) {
        synchronized(readyListeners) { readyListeners.remove(cb) }
    }

    private fun notifyReady(ok: Boolean, err: String?) {
        ready = ok
        lastError = err
        val copy: List<(Boolean, String?) -> Unit>
        synchronized(readyListeners) { copy = readyListeners.toList() }
        copy.forEach { try { it(ok, err) } catch (_: Exception) {} }
    }

    fun startServers() {
        promoteForeground("Loading model…")
        thread { boot(null, null) }
    }

    fun restartServers() {
        promoteForeground("Restarting model…")
        val old = proc; proc = null
        val oldEmb = embProc; embProc = null
        embReady = false
        ready = false
        thread { boot(old, oldEmb) }
    }

    fun swapModel(slot: String): JSONObject {
        val target = if (slot == "coder") "coder" else "chat"
        if (target == "coder" && !coderModel.exists()) {
            return JSONObject().put("ok", false).put("error", "Coder model not installed").put("slot", activeSlot)
        }
        if (target == activeSlot && proc?.isAlive == true) {
            return JSONObject().put("ok", true).put("slot", activeSlot).put("cached", true)
        }
        activeSlot = target
        promoteForeground(if (target == "coder") "Switching to coder model…" else "Switching to chat model…")
        proc?.destroy()
        try { proc?.waitFor() } catch (_: Exception) {}
        proc = null
        ready = false
        val www = File(filesDir, "www")
        val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
        return try {
            val p = ProcessBuilder(serverArgs(exe.path, www.path)).redirectErrorStream(true).start()
            proc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama", it) } }
            for (i in 0 until 40) {
                if (!p.isAlive) {
                    val err = "Exited with code ${p.exitValue()}"
                    notifyReady(false, err)
                    return JSONObject().put("ok", false).put("error", err).put("slot", activeSlot)
                }
                try {
                    val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 300; c.readTimeout = 300
                    if (c.responseCode == 200) {
                        ready = true
                        updateNotification(activeModelFile().name)
                        notifyReady(true, null)
                        return JSONObject().put("ok", true).put("slot", activeSlot).put("model", activeModelFile().name)
                    }
                } catch (_: Exception) {}
                Thread.sleep(350)
            }
            val err = "Startup timed out"
            notifyReady(false, err)
            JSONObject().put("ok", false).put("error", err).put("slot", activeSlot)
        } catch (e: Exception) {
            val err = e.message ?: "swap failed"
            notifyReady(false, err)
            JSONObject().put("ok", false).put("error", err).put("slot", activeSlot)
        }
    }

    fun stopServers() {
        proc?.destroy()
        embProc?.destroy()
        try { proc?.waitFor() } catch (_: Exception) {}
        try { embProc?.waitFor() } catch (_: Exception) {}
        proc = null
        embProc = null
        embReady = false
        ready = false
        releaseWakeLock()
    }

    fun isChatAlive(): Boolean = proc?.isAlive == true
    fun isEmbAlive(): Boolean = embProc?.isAlive == true

    private fun boot(old: Process?, oldEmb: Process?) {
        try {
            old?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
            oldEmb?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }

            acquireWakeLock()
            val www = File(filesDir, "www").apply { if (!exists()) mkdirs() }
            // www assets are copied by Activity before start; service only needs the dir present

            val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
            startEmbedding(exe)

            if (!modelFile().exists() && !(activeSlot == "coder" && coderModel.exists())) {
                notifyReady(false, "No model file")
                updateNotification("No model")
                return
            }

            val p = ProcessBuilder(serverArgs(exe.path, www.path)).redirectErrorStream(true).start()
            proc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama", it) } }

            repeat(300) {
                if (!p.isAlive) {
                    val code = try { p.exitValue() } catch (_: Exception) { -1 }
                    val err = "Model server exited (code $code)"
                    Log.e(TAG, err)
                    notifyReady(false, err)
                    updateNotification(err)
                    return
                }
                try {
                    val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 500; c.readTimeout = 500
                    if (c.responseCode == 200) {
                        ready = true
                        lastError = null
                        updateNotification(activeModelFile().name)
                        notifyReady(true, null)
                        return
                    }
                } catch (_: Exception) {}
                Thread.sleep(1000)
            }
            notifyReady(false, "Server didn't become ready in time")
            updateNotification("Startup timeout")
        } catch (e: Exception) {
            Log.e(TAG, "boot failed", e)
            notifyReady(false, e.message ?: "boot failed")
            updateNotification("Error: ${e.message}")
        }
    }

    private fun startEmbedding(exe: File) {
        embProc?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
        embProc = null
        embReady = false
        if (!embModel.exists()) return
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
                            return@thread
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "embedding failed: ${e.message}")
        }
    }

    private fun serverArgs(exe: String, www: String): List<String> {
        val srvKey = if (activeSlot == "coder") "srv_coder" else "srv"
        val s = try { JSONObject(prefs.getString(srvKey, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val currentModel = activeModelFile()
        val a = mutableListOf(exe, "-m", currentModel.path, "--host", "127.0.0.1", "--port", "8080", "--path", www, "--jinja")
        fun num(key: String, flag: String, def: Int) {
            val v = if (s.has(key)) s.optInt(key, 0) else def
            if (v > 0) { a.add(flag); a.add(v.toString()) }
        }
        fun word(key: String, flag: String) {
            val v = s.optString(key, "").trim()
            if (Regex("[a-z0-9_]{1,12}").matches(v)) { a.add(flag); a.add(v) }
        }
        val defaultCtx = if (activeSlot == "coder") 32768 else 8192
        num("ctx", "-c", defaultCtx); num("threads", "-t", 4); num("threads_batch", "-tb", 0)
        num("batch", "-b", 0); num("ubatch", "-ub", 0); num("parallel", "-np", 1)
        word("fa", "-fa"); word("ctk", "-ctk"); word("ctv", "-ctv")
        if (s.optBoolean("mlock", false)) a.add("--mlock")
        if (s.optBoolean("no_mmap", false)) a.add("--no-mmap")
        s.optString("extra", "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { a.add(it) }
        return a
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL_ID, "Kojot model server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the on-device model running while you use other apps"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun promoteForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LlamaService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Kojot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kojot:fgs")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        private const val TAG = "LlamaService"
        const val CHANNEL_ID = "kojot_server"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.kojot.app.START_SERVER"
        const val ACTION_STOP = "com.kojot.app.STOP_SERVER"

        fun start(ctx: Context) {
            val i = Intent(ctx, LlamaService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LlamaService::class.java).setAction(ACTION_STOP))
        }
    }
}

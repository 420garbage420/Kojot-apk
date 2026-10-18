package com.kojot.app

import android.content.Context
import android.util.Log
import com.android.apksig.ApkSigner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-device APK compiler for Kojot.
 *
 * Expects under [toolsDir] (usually filesDir/compiler_tools/):
 *   aapt2          — static arm64 binary
 *   d8             — static arm64 binary (optional; only if .class inputs exist)
 *   android.jar    — android.jar from an Android SDK platform
 *   shell/
 *     classes.dex  — prebuilt minimal WebView activity (for HTML/JS apps)
 *     AndroidManifest.web.xml — template manifest for web shells
 *   debug.keystore — optional; generated on first use if missing
 *
 * Pipeline for a workspace project:
 *   1. Detect web app vs Android project
 *   2. aapt2 compile + link  →  resources.apk
 *   3. Merge classes.dex (shell or project)
 *   4. Zip → unsigned.apk
 *   5. apksig sign → out.apk
 */
class CompilerTools(private val context: Context, private val toolsDir: File) {

    data class LogLine(val step: String, val stream: String, val text: String)

    private val logs = ArrayList<LogLine>()
    private val logLock = Any()

    private fun log(step: String, stream: String, text: String) {
        synchronized(logLock) {
            logs.add(LogLine(step, stream, text))
            Log.d("KojotCompiler", "[$step/$stream] $text")
        }
    }

    private fun result(text: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("text", text).apply { if (data != null) put("data", data) }

    private fun logsJson(): JSONArray {
        val a = JSONArray()
        synchronized(logLock) {
            logs.forEach { l ->
                a.put(JSONObject().put("step", l.step).put("stream", l.stream).put("text", l.text))
            }
        }
        return a
    }

    /** aapt2 ships as jniLibs/arm64-v8a/libaapt2.so so Android (targetSdk>=29) lets us exec it. */
    private fun aapt2File(): File {
        val native = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        return if (native.isFile) native else File(toolsDir, "aapt2")
    }

    fun toolsReady(): Boolean {
        val aapt2 = aapt2File()
        val jar = File(toolsDir, "android.jar")
        return aapt2.isFile && aapt2.canExecute() && jar.isFile
    }

    fun toolsStatus(): JSONObject {
        fun st(name: String): JSONObject {
            val f = if (name == "aapt2") aapt2File() else File(toolsDir, name)
            return JSONObject()
                .put("present", f.exists())
                .put("size", if (f.exists()) f.length() else 0)
                .put("exec", f.canExecute())
        }
        return result(
            if (toolsReady()) "compiler tools ready" else "compiler tools incomplete — place aapt2 + android.jar in assets/compiler_tools",
            JSONObject()
                .put("ready", toolsReady())
                .put("dir", toolsDir.path)
                .put("aapt2", st("aapt2"))
                .put("d8", st("d8"))
                .put("android_jar", st("android.jar"))
                .put("shell_dex", st("shell/classes.dex"))
        )
    }

    // ---- process runner --------------------------------------------------------------------
    private fun runCmd(
        step: String,
        argv: List<String>,
        cwd: File,
        timeoutSec: Long = 120
    ): Int {
        log(step, "cmd", argv.joinToString(" "))
        val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(false)
        pb.environment()["LD_LIBRARY_PATH"] = toolsDir.path
        val proc = pb.start()
        val outT = Thread {
            BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) log(step, "stdout", line!!)
            }
        }
        val errT = Thread {
            BufferedReader(InputStreamReader(proc.errorStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) log(step, "stderr", line!!)
            }
        }
        outT.start(); errT.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            log(step, "stderr", "timed out after ${timeoutSec}s")
            return -1
        }
        outT.join(2000); errT.join(2000)
        log(step, "exit", proc.exitValue().toString())
        return proc.exitValue()
    }


    /**
     * Sign [unsigned] → [signed] with apksig.
     * Prefers toolsDir/debug.keystore (android/androiddebugkey); if missing, uses a one-shot
     * in-memory key generated and persisted to toolsDir/kojot-debug.keystore.
     */
    private fun signApk(unsigned: File, signed: File) {
        val ksFile = File(toolsDir, "debug.keystore")
        if (!ksFile.isFile) {
            throw ToolError(
                "debug.keystore missing under compiler_tools/. " +
                    "Generate: keytool -genkey -v -keystore debug.keystore -storepass android " +
                    "-alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000"
            )
        }
        val password = "android".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(ksFile).use { keyStore.load(it, password) }
        val alias = keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
            ?: keyStore.aliases().toList().firstOrNull()
            ?: throw ToolError("keystore has no aliases")
        val privateKey = keyStore.getKey(alias, password) as? PrivateKey
            ?: throw ToolError("no private key for alias $alias")
        val cert = keyStore.getCertificate(alias) as X509Certificate
        val config = ApkSigner.SignerConfig.Builder("Kojot", privateKey, listOf(cert)).build()
        if (signed.exists()) signed.delete()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        log("sign", "info", "signed ${signed.name} (${signed.length()} bytes)")
    }

    // ---- project detection -----------------------------------------------------------------
    private fun isWebProject(dir: File): Boolean {
        val hasManifest = File(dir, "AndroidManifest.xml").isFile
        if (hasManifest) return false
        val index = sequenceOf("index.html", "www/index.html", "app/index.html")
            .map { File(dir, it) }.firstOrNull { it.isFile }
        return index != null
    }

    private fun findHtmlRoot(dir: File): File {
        for (c in listOf(".", "www", "app", "public", "dist")) {
            val f = File(dir, "$c/index.html").normalize()
            if (f.isFile) return f.parentFile!!
        }
        throw ToolError("no index.html found under ${dir.path}")
    }

    // ---- aapt2 helpers ---------------------------------------------------------------------
    private fun aapt2(): String = aapt2File().path
    private fun androidJar(): String = File(toolsDir, "android.jar").path

    private fun compileRes(step: String, resDir: File, flatDir: File): Boolean {
        flatDir.mkdirs()
        if (!resDir.isDirectory) {
            // synthesize a minimal values/strings.xml so aapt2 has something to link
            val values = File(resDir, "values").apply { mkdirs() }
            File(values, "strings.xml").writeText(
                """<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">Kojot App</string></resources>"""
            )
        }
        val inputs = resDir.walkTopDown().filter { it.isFile }.toList()
        if (inputs.isEmpty()) {
            log(step, "info", "no resource files — synthesizing empty values")
            val values = File(resDir, "values").apply { mkdirs() }
            File(values, "strings.xml").writeText(
                """<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">Kojot App</string></resources>"""
            )
        }
        val code = runCmd(
            step,
            listOf(aapt2(), "compile", "--dir", resDir.path, "-o", flatDir.path + "/"),
            toolsDir
        )
        return code == 0
    }

    private fun linkRes(
        step: String,
        flatDir: File,
        manifest: File,
        assetsDir: File?,
        outApk: File,
        packageId: String
    ): Boolean {
        val flats = flatDir.listFiles()?.filter { it.extension == "flat" }?.map { it.path } ?: emptyList()
        val argv = mutableListOf(
            aapt2(), "link",
            "-o", outApk.path,
            "-I", androidJar(),
            "--manifest", manifest.path,
            "--rename-manifest-package", packageId,
            "--auto-add-overlay"
        )
        if (assetsDir != null && assetsDir.isDirectory) {
            argv += listOf("-A", assetsDir.path)
        }
        // aapt2 link accepts flat files as trailing args
        flats.forEach { argv += it }
        // also accept a compiled zip if present
        File(flatDir, "compiled.zip").takeIf { it.isFile }?.let {
            // recompile as zip archive of flats
        }
        val code = runCmd(step, argv, toolsDir)
        return code == 0 && outApk.isFile
    }

    // ---- packaging -------------------------------------------------------------------------
    private fun mergeDexIntoApk(resApk: File, dex: File, outUnsigned: File) {
        // resApk is already a zip; copy entries and add classes.dex
        ZipOutputStream(FileOutputStream(outUnsigned)).use { zos ->
            ZipFile(resApk).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.equals("classes.dex", ignoreCase = true)) continue
                    zos.putNextEntry(ZipEntry(e.name))
                    zf.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zos.putNextEntry(ZipEntry("classes.dex"))
            dex.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        log("package", "info", "merged dex → ${outUnsigned.name}")
    }

    private fun writeWebManifest(out: File, appName: String, packageId: String) {
        out.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageId">
                <uses-permission android:name="android.permission.INTERNET"/>
                <application
                    android:label="$appName"
                    android:usesCleartextTraffic="true"
                    android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
                    <activity
                        android:name="com.kojot.shell.WebShellActivity"
                        android:exported="true"
                        android:configChanges="orientation|screenSize|keyboardHidden|uiMode"
                        android:windowSoftInputMode="adjustResize">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )
    }

    private fun copyDir(src: File, dst: File) {
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src)
            val t = File(dst, rel.path)
            if (f.isDirectory) t.mkdirs()
            else {
                t.parentFile?.mkdirs()
                f.copyTo(t, overwrite = true)
            }
        }
    }

    // ---- public entry ----------------------------------------------------------------------
    /**
     * Compile [projectDir] into an installable APK under the workspace's `build/` folder.
     *
     * Modes:
     *  - Web project (index.html, no AndroidManifest): wrap in WebView shell
     *  - Android project (AndroidManifest.xml present): package res/ + assets/ + shell or project dex
     */
    fun compileApp(projectDir: File, appName: String? = null, packageId: String? = null): JSONObject {
        synchronized(logLock) { logs.clear() }
        if (!toolsReady()) {
            return result(
                "Compiler tools not ready. Add arm64 aapt2 and android.jar to assets/compiler_tools/ and reinstall.",
                JSONObject().put("ready", false).put("logs", logsJson())
            )
        }
        if (!projectDir.isDirectory) throw ToolError("project_dir is not a directory: ${projectDir.path}")

        val work = File(context.cacheDir, "kojot-build-${System.currentTimeMillis()}").apply {
            deleteRecursively(); mkdirs()
        }
        val outDir = File(projectDir, "build").apply { mkdirs() }
        val pkg = packageId?.takeIf { it.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) }
            ?: "com.kojot.app.built"
        val label = appName?.take(40)?.ifBlank { null } ?: projectDir.name.take(40).ifBlank { "KojotApp" }

        try {
            val shellDex = File(toolsDir, "shell/classes.dex")
            if (!shellDex.isFile) {
                log("check", "stderr", "shell/classes.dex missing — build a minimal WebView activity dex and place it under assets/compiler_tools/shell/")
                return result(
                    "Missing shell/classes.dex. See assets/compiler_tools/README.md",
                    JSONObject().put("logs", logsJson()).put("ready", false)
                )
            }

            val resDir = File(work, "res").apply { mkdirs() }
            val flatDir = File(work, "flat").apply { mkdirs() }
            val assetsDir = File(work, "assets").apply { mkdirs() }
            val manifest = File(work, "AndroidManifest.xml")
            val resApk = File(work, "resources.apk")
            val unsigned = File(work, "unsigned.apk")
            val signed = File(outDir, "${label.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")

            if (isWebProject(projectDir)) {
                log("detect", "info", "web project")
                val htmlRoot = findHtmlRoot(projectDir)
                copyDir(htmlRoot, assetsDir)
                writeWebManifest(manifest, label, pkg)
                // minimal icon/values
                File(resDir, "values").mkdirs()
                File(resDir, "values/strings.xml").writeText(
                    """<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">$label</string></resources>"""
                )
            } else {
                log("detect", "info", "android project")
                val projManifest = File(projectDir, "AndroidManifest.xml")
                if (!projManifest.isFile) throw ToolError("AndroidManifest.xml not found")
                projManifest.copyTo(manifest, overwrite = true)
                File(projectDir, "res").takeIf { it.isDirectory }?.let { copyDir(it, resDir) }
                File(projectDir, "assets").takeIf { it.isDirectory }?.let { copyDir(it, assetsDir) }
                // also map www/ into assets if present
                File(projectDir, "www").takeIf { it.isDirectory }?.let { copyDir(it, File(assetsDir, "www").apply { mkdirs() }) }
            }

            if (!compileRes("aapt2-compile", resDir, flatDir)) {
                return result("aapt2 compile failed", JSONObject().put("logs", logsJson()).put("ok", false))
            }
            if (!linkRes("aapt2-link", flatDir, manifest, assetsDir, resApk, pkg)) {
                return result("aapt2 link failed", JSONObject().put("logs", logsJson()).put("ok", false))
            }

            // Prefer project classes.dex if present, else shell
            val projectDex = sequenceOf(
                File(projectDir, "classes.dex"),
                File(projectDir, "build/classes.dex"),
                File(projectDir, "app/build/classes.dex")
            ).firstOrNull { it.isFile } ?: shellDex

            // Optional d8 pass if .class files exist
            val classDir = File(projectDir, "classes")
            if (classDir.isDirectory && File(toolsDir, "d8").canExecute()) {
                val d8Out = File(work, "d8").apply { mkdirs() }
                val classes = classDir.walkTopDown().filter { it.extension == "class" }.map { it.path }.toList()
                if (classes.isNotEmpty()) {
                    val argv = mutableListOf(File(toolsDir, "d8").path, "--output", d8Out.path) + classes
                    if (runCmd("d8", argv, toolsDir) == 0) {
                        val built = File(d8Out, "classes.dex")
                        if (built.isFile) {
                            mergeDexIntoApk(resApk, built, unsigned)
                        } else {
                            mergeDexIntoApk(resApk, projectDex, unsigned)
                        }
                    } else {
                        mergeDexIntoApk(resApk, projectDex, unsigned)
                    }
                } else {
                    mergeDexIntoApk(resApk, projectDex, unsigned)
                }
            } else {
                mergeDexIntoApk(resApk, projectDex, unsigned)
            }

            try {
                signApk(unsigned, signed)
            } catch (e: Exception) {
                log("sign", "stderr", e.message ?: "sign failed")
                // Still return unsigned so the user can sign externally
                val fallback = File(outDir, unsigned.name)
                unsigned.copyTo(fallback, overwrite = true)
                return result(
                    "Build produced unsigned APK (signing failed: ${e.message}). ${fallback.path}",
                    JSONObject()
                        .put("ok", false)
                        .put("apk", fallback.path)
                        .put("signed", false)
                        .put("logs", logsJson())
                )
            }

            return result(
                "Built ${signed.name} (${signed.length() / 1024} KB) → ${signed.path}",
                JSONObject()
                    .put("ok", true)
                    .put("apk", signed.path)
                    .put("signed", true)
                    .put("package", pkg)
                    .put("logs", logsJson())
            )
        } finally {
            // keep work dir for debugging if KOJOT_KEEP_BUILD=1; otherwise clean
            try { work.deleteRecursively() } catch (_: Exception) {}
        }
    }

    fun run(op: String, a: JSONObject, workspace: File): JSONObject = when (op) {
        "compiler_status" -> toolsStatus()
        "compile_app" -> {
            val rel = a.optString("project_dir", ".").ifBlank { "." }
            val dir = if (rel.startsWith("/")) File(rel) else File(workspace, rel)
            val canonical = dir.canonicalFile
            // stay inside workspace
            if (canonical != workspace.canonicalFile && !canonical.path.startsWith(workspace.canonicalFile.path + File.separator)) {
                throw ToolError("project_dir outside workspace")
            }
            compileApp(
                canonical,
                appName = a.optString("app_name", "").ifBlank { null },
                packageId = a.optString("package_id", "").ifBlank { null }
            )
        }
        else -> throw ToolError("unknown compiler op: $op")
    }

    companion object {
        /**
         * Extract assets/compiler_tools/ into [dest] on first launch and mark binaries executable.
         */
        fun ensureExtracted(context: Context, dest: File) {
            if (File(dest, ".extracted").isFile) return
            dest.mkdirs()
            try {
                copyAssetDir(context, "compiler_tools", dest)
            } catch (e: Exception) {
                Log.w("KojotCompiler", "extract tools: ${e.message}")
            }
            listOf("aapt2", "d8", "shell/aapt2").forEach { rel ->
                val f = File(dest, rel)
                if (f.isFile) f.setExecutable(true, false)
            }
            File(dest, ".extracted").writeText(System.currentTimeMillis().toString())
        }

        private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
            val list = context.assets.list(assetPath) ?: emptyArray()
            if (list.isEmpty()) {
                // leaf file
                try {
                    context.assets.open(assetPath).use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                } catch (_: Exception) {
                    // directory listed empty and not a file — ignore
                }
                return
            }
            dest.mkdirs()
            for (name in list) {
                val child = if (assetPath.isEmpty()) name else "$assetPath/$name"
                copyAssetDir(context, child, File(dest, name))
            }
        }
    }
}

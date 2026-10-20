package com.kojot.app

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class IndexStatus(
    var running: Boolean = false,
    var total: Int = 0,
    var current: Int = 0,
    var indexed: Int = 0,
    var skipped: Int = 0,
    var currentFile: String = "",
    var cancelled: Boolean = false,
    var error: String? = null
)

/**
 * Edge Memory Architecture for Kojot.
 *
 * - facts: telegraphic micro-facts with optional dense vectors (nomic-embed on :8081)
 * - file_index: path + snippet embeddings for semantic file discovery
 * - Hybrid recall: FTS5 BM25 + cosine similarity fused via Reciprocal Rank Fusion
 * - Hard cap: recall output ≤ 1600 chars (~400 tokens)
 */
class MemoryTools(private val dbFile: File, private val workspace: () -> File) {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    private val embUrl = "http://127.0.0.1:8081/v1/embeddings"
    @Volatile var embOnline: Boolean = false
        private set

    val indexStatus = IndexStatus()

    fun getIndexingStatus(): JSONObject = JSONObject()
        .put("running", indexStatus.running)
        .put("total", indexStatus.total)
        .put("current", indexStatus.current)
        .put("indexed", indexStatus.indexed)
        .put("skipped", indexStatus.skipped)
        .put("current_file", indexStatus.currentFile)
        .put("error", indexStatus.error ?: "")

    fun cancelIndexing(): JSONObject {
        indexStatus.cancelled = true
        return JSONObject().put("text", "cancellation requested")
    }

    fun startIndexing(force: Boolean = false, maxFiles: Int = 10000, onDone: (() -> Unit)? = null): JSONObject {
        synchronized(indexStatus) {
            if (indexStatus.running) return JSONObject().put("text", "running").put("data", getIndexingStatus())
            indexStatus.running = true
            indexStatus.cancelled = false
            indexStatus.total = 0
            indexStatus.current = 0
            indexStatus.indexed = 0
            indexStatus.skipped = 0
            indexStatus.currentFile = ""
            indexStatus.error = null
        }
        if (force) {
            db.execSQL("DELETE FROM file_index")
        }
        val root = workspace()
        kotlin.concurrent.thread(name = "kojot-batch-indexer") {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val candidates = ArrayList<File>()
                fun collect(d: File, depth: Int) {
                    if (indexStatus.cancelled || candidates.size >= maxFiles) return
                    val kids = d.listFiles() ?: return
                    for (k in kids) {
                        if (indexStatus.cancelled || candidates.size >= maxFiles) return
                        if (k.name.startsWith(".")) continue
                        if (k.name in setOf("node_modules", "build", ".git", ".gradle", "__pycache__", "venv")) continue
                        if (k.isDirectory) {
                            if (depth < 8) collect(k, depth + 1)
                            continue
                        }
                        if (isTextFile(k)) candidates.add(k)
                    }
                }
                collect(root, 0)
                indexStatus.total = candidates.size
                for (f in candidates) {
                    if (indexStatus.cancelled) break
                    val path = rel(f, root)
                    indexStatus.currentFile = f.name
                    val existingMtime = db.rawQuery(
                        "SELECT mtime FROM file_index WHERE path=?", arrayOf(path)
                    ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                    if (existingMtime == f.lastModified()) {
                        indexStatus.skipped++
                    } else {
                        try {
                            indexFile(f, root)
                            indexStatus.indexed++
                        } catch (e: Exception) {
                            Log.w("MemoryTools", "index error on $path: ${e.message}")
                        }
                    }
                    indexStatus.current++
                    Thread.sleep(15)
                }
            } catch (e: Exception) {
                indexStatus.error = e.message
            } finally {
                indexStatus.running = false
                onDone?.invoke()
            }
        }
        return JSONObject().put("text", "indexing started").put("data", getIndexingStatus())
    }

    init {
        db.enableWriteAheadLogging()
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tag TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                vec BLOB
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS file_index (
                path TEXT PRIMARY KEY,
                mtime INTEGER NOT NULL,
                snippet TEXT,
                vec BLOB
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT
            )"""
        )
        if (getMeta("user_anchor").isNullOrBlank()) {
            setMeta(
                "user_anchor",
                "Device: Android phone. App: Kojot on-device. Prefer short answers. Use tools for files."
            )
        }
    }
    private fun result(text: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("text", text).apply { if (data != null) put("data", data) }

    // ---- meta / anchor --------------------------------------------------------------------
    fun getMeta(key: String): String? {
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setMeta(key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
            arrayOf(key, value)
        )
    }

    fun getUserAnchor(): JSONObject {
        val a = getMeta("user_anchor") ?: ""
        return result(a, JSONObject().put("anchor", a))
    }

    fun setUserAnchor(a: JSONObject): JSONObject {
        val text = a.optString("text", a.optString("anchor", "")).trim()
        if (text.isEmpty()) throw ToolError("anchor text is required")
        val clipped = text.take(320)
        setMeta("user_anchor", clipped)
        return result("anchor saved (${clipped.length} chars)")
    }

    // ---- embedding client -----------------------------------------------------------------
    fun checkEmbedding(): Boolean {
        embOnline = try {
            val c = URL("http://127.0.0.1:8081/health").openConnection() as HttpURLConnection
            c.connectTimeout = 800; c.readTimeout = 800
            c.responseCode == 200
        } catch (_: Exception) {
            false
        }
        return embOnline
    }

    fun getEmbedding(text: String): FloatArray? {
        if (!embOnline && !checkEmbedding()) return null
        return try {
            val body = JSONObject()
                .put("input", text.take(1000))
                .put("model", "nomic")
                .toString()
            val c = URL(embUrl).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 4000; c.readTimeout = 12000
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) {
                embOnline = false
                return null
            }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(resp)
                .getJSONArray("data")
                .getJSONObject(0)
                .getJSONArray("embedding")
            val n = arr.length()
            val v = FloatArray(n)
            var sumSq = 0.0
            for (i in 0 until n) {
                val f = arr.getDouble(i).toFloat()
                v[i] = f
                sumSq += f * f
            }
            val norm = sqrt(sumSq).toFloat()
            if (norm > 1e-8f) {
                for (i in 0 until n) v[i] /= norm
            }
            embOnline = true
            reconcileDim(n)
            v
        } catch (e: Exception) {
            Log.w("MemoryTools", "embed failed: ${e.message}")
            embOnline = false
            null
        }
    }

    private fun reconcileDim(n: Int) {
        val prev = getMeta("emb_dim")?.toIntOrNull()
        if (prev == n) return
        if (prev != null) {
            db.execSQL("UPDATE facts SET vec=NULL")
            db.execSQL("UPDATE file_index SET vec=NULL, mtime=0")
        }
        setMeta("emb_dim", n.toString())
    }

    private fun packVec(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun unpackVec(b: ByteArray?): FloatArray? {
        if (b == null || b.size < 4 || b.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(b.size / 4)
        for (i in v.indices) v[i] = buf.float
        return v
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        val n = a.size
        if (n == 0) return 0f
        var dot = 0f
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }

    // ---- facts ----------------------------------------------------------------------------
    fun saveFact(a: JSONObject): JSONObject {
        val tag = a.optString("tag", "NOTE").trim().uppercase().take(24).ifEmpty { "NOTE" }
        var content = a.optString("content", "").trim()
        if (content.isEmpty()) throw ToolError("content is required")
        content = content.split(Regex("\\s+")).take(20).joinToString(" ").take(120)
        val vec = getEmbedding("[$tag] $content")
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO facts(tag, content, created_at, vec) VALUES(?,?,?,?)",
            arrayOf(tag, content, now, vec?.let { packVec(it) })
        )
        return result("saved [$tag] $content")
    }

    fun recallMemory(a: JSONObject): JSONObject {
        val query = a.optString("query", "").trim()
        val limit = a.optInt("limit", 6).coerceIn(1, 12)
        if (query.isEmpty()) return result("(no query)", JSONObject().put("facts", JSONArray()))

        data class Hit(val id: Long, val tag: String, val content: String, var score: Double)
        val byId = LinkedHashMap<Long, Hit>()

        val tokens = query.split(Regex("\\s+")).filter { it.length > 1 }
        for (t in tokens) {
            try {
                db.rawQuery(
                    "SELECT id, tag, content FROM facts WHERE content LIKE ? OR tag LIKE ? LIMIT ?",
                    arrayOf("%$t%", "%$t%", limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val hit = byId.getOrPut(id) { Hit(id, c.getString(1), c.getString(2), 0.0) }
                        hit.score += 1.0 / 61.0
                    }
                }
            } catch (e: Exception) {
                Log.w("MemoryTools", "keyword match: ${e.message}")
            }
        }

        val qVec = getEmbedding(query)
        if (qVec != null) {
            val scored = ArrayList<Pair<Long, Float>>()
            db.rawQuery("SELECT id, tag, content, vec FROM facts WHERE vec IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val vec = unpackVec(c.getBlob(3)) ?: continue
                    val sim = cosine(qVec, vec)
                    if (sim > 0.25f) scored.add(id to sim)
                    val existing = byId[id]
                    if (existing == null && sim > 0.35f) {
                        byId[id] = Hit(id, c.getString(1), c.getString(2), 0.0)
                    }
                }
            }
            scored.sortedByDescending { it.second }.take(limit).forEachIndexed { i, (id, _) ->
                byId[id]?.let { it.score += 1.0 / (60.0 + i + 1) }
            }
        }

        if (byId.isEmpty()) {
            db.rawQuery(
                "SELECT id, tag, content FROM facts ORDER BY created_at DESC LIMIT ?",
                arrayOf(minOf(limit, 3).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    byId[c.getLong(0)] = Hit(c.getLong(0), c.getString(1), c.getString(2), 0.01)
                }
            }
        }

        val ranked = byId.values.sortedByDescending { it.score }.take(limit)
        val lines = ArrayList<String>()
        var chars = 0
        val maxChars = 1600
        val arr = JSONArray()
        for (h in ranked) {
            val line = "[${h.tag}] ${h.content}"
            if (chars + line.length + 1 > maxChars) break
            lines.add(line)
            chars += line.length + 1
            arr.put(
                JSONObject()
                    .put("tag", h.tag)
                    .put("content", h.content)
                    .put("score", String.format("%.3f", h.score))
            )
        }
        val text = if (lines.isEmpty()) "(no matching memory)" else lines.joinToString("\n")
        return result(text, JSONObject().put("facts", arr).put("chars", text.length))
    }

    fun listFacts(a: JSONObject): JSONObject {
        val limit = a.optInt("limit", 40).coerceIn(1, 200)
        val arr = JSONArray()
        val sb = StringBuilder()
        db.rawQuery(
            "SELECT id, tag, content, created_at FROM facts ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val tag = c.getString(1)
                val content = c.getString(2)
                arr.put(JSONObject().put("id", id).put("tag", tag).put("content", content).put("created_at", c.getLong(3)))
                sb.append("#$id [$tag] $content\n")
            }
        }
        return result(sb.toString().ifBlank { "(no facts stored)" }, JSONObject().put("facts", arr).put("total", arr.length()))
    }

    fun clearFacts(a: JSONObject): JSONObject {
        val id = a.optLong("id", -1)
        if (id > 0) {
            db.execSQL("DELETE FROM facts WHERE id=?", arrayOf(id))
            return result("deleted fact #$id")
        }
        val n = db.compileStatement("SELECT COUNT(*) FROM facts").simpleQueryForLong()
        db.execSQL("DELETE FROM facts")
        try { db.execSQL("INSERT INTO facts_fts(facts_fts) VALUES('rebuild')") } catch (_: Exception) {}
        return result("cleared $n facts")
    }

    /** Update an existing memory topic (Claude-style edit). */
    fun updateFact(a: JSONObject): JSONObject {
        val id = a.optLong("id", -1)
        if (id <= 0) throw ToolError("id is required")
        var content = a.optString("content", "").trim()
        if (content.isEmpty()) throw ToolError("content is required")
        content = content.split(Regex("\\s+")).take(40).joinToString(" ").take(240)
        val tag = a.optString("tag", "").trim().uppercase().take(24)
        val vec = getEmbedding(if (tag.isNotEmpty()) "[$tag] $content" else content)
        if (tag.isNotEmpty()) {
            db.execSQL(
                "UPDATE facts SET content=?, tag=?, vec=? WHERE id=?",
                arrayOf(content, tag, vec?.let { packVec(it) }, id)
            )
        } else {
            db.execSQL(
                "UPDATE facts SET content=?, vec=? WHERE id=?",
                arrayOf(content, vec?.let { packVec(it) }, id)
            )
        }
        return result("updated fact #$id")
    }

    fun stats(): JSONObject {
        val facts = db.compileStatement("SELECT COUNT(*) FROM facts").simpleQueryForLong()
        val files = db.compileStatement("SELECT COUNT(*) FROM file_index").simpleQueryForLong()
        checkEmbedding()
        return result(
            "facts=$facts files=$files embed=${if (embOnline) "online" else "offline"}",
            JSONObject()
                .put("facts", facts)
                .put("files", files)
                .put("embed", embOnline)
        )
    }

    // ---- file index -----------------------------------------------------------------------
    private val textExt = setOf(
        "txt", "md", "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "htm", "css", "scss",
        "json", "xml", "yaml", "yml", "toml", "gradle", "kts", "c", "cpp", "h", "hpp", "rs",
        "go", "rb", "php", "sh", "bash", "sql", "csv", "ini", "cfg", "conf", "properties"
    )

    private fun isTextFile(f: File): Boolean {
        val ext = f.extension.lowercase()
        if (ext in textExt) return true
        if (f.length() > 512_000) return false
        return try {
            val head = ByteArray(minOf(256, f.length().toInt()))
            f.inputStream().use { it.read(head) }
            head.none { it.toInt() == 0 }
        } catch (_: Exception) {
            false
        }
    }

    private fun rel(f: File, root: File): String =
        if (f == root) "." else try { f.relativeTo(root).path } catch (_: Exception) { f.name }

    fun indexFile(file: File, root: File = workspace()): JSONObject {
        if (!file.isFile || !isTextFile(file)) throw ToolError("not a text file")
        val path = rel(file, root)
        val snippet = try {
            val buf = ByteArray(minOf(1200, file.length().toInt()))
            file.inputStream().use { it.read(buf) }
            String(buf, Charsets.UTF_8).replace(Regex("\\s+"), " ").trim().take(800)
        } catch (e: Exception) {
            throw ToolError("read failed: ${e.message}")
        }
        val embedText = "path: $path\ncontent: $snippet"
        val vec = getEmbedding(embedText)
        db.execSQL(
            "INSERT OR REPLACE INTO file_index(path, mtime, snippet, vec) VALUES(?,?,?,?)",
            arrayOf(path, file.lastModified(), snippet, vec?.let { packVec(it) })
        )
        return result("indexed $path")
    }

    fun syncFileIndex(a: JSONObject): JSONObject {
        val root = workspace()
        if (!root.isDirectory) throw ToolError("workspace not a directory")
        val maxFiles = a.optInt("max", 200).coerceIn(10, 500)
        val maxDepth = a.optInt("depth", 6).coerceIn(1, 12)
        var indexed = 0
        var skipped = 0
        val errors = ArrayList<String>()

        fun walk(d: File, depth: Int) {
            if (indexed >= maxFiles) return
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (indexed >= maxFiles) return
                if (k.name.startsWith(".")) continue
                if (k.name in setOf("node_modules", "build", ".git", ".gradle", "__pycache__", "venv")) continue
                if (k.isDirectory) {
                    if (depth < maxDepth) walk(k, depth + 1)
                    continue
                }
                if (!isTextFile(k)) { skipped++; continue }
                val path = rel(k, root)
                val existingMtime = db.rawQuery(
                    "SELECT mtime FROM file_index WHERE path=?", arrayOf(path)
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                if (existingMtime == k.lastModified()) { skipped++; continue }
                try {
                    indexFile(k, root)
                    indexed++
                } catch (e: Exception) {
                    errors.add("$path: ${e.message}")
                }
            }
        }
        walk(root, 0)
        val total = db.compileStatement("SELECT COUNT(*) FROM file_index").simpleQueryForLong()
        return result(
            "indexed $indexed, skipped $skipped, total $total" +
                if (errors.isNotEmpty()) " (${errors.size} errors)" else "",
            JSONObject()
                .put("indexed", indexed)
                .put("skipped", skipped)
                .put("total", total)
                .put("errors", JSONArray(errors.take(5)))
        )
    }

    fun recallFiles(a: JSONObject): JSONObject {
        val query = a.optString("query", "").trim()
        val limit = a.optInt("limit", 8).coerceIn(1, 20)
        if (query.isEmpty()) throw ToolError("query is required")

        data class FHit(val path: String, val snippet: String, var score: Double)

        val hits = LinkedHashMap<String, FHit>()
        val qLower = query.lowercase()
        val tokens = qLower.split(Regex("[^a-z0-9_./-]+")).filter { it.length > 1 }

        db.rawQuery("SELECT path, snippet FROM file_index", null).use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                val snip = c.getString(1) ?: ""
                val hay = (path + " " + snip).lowercase()
                var s = 0.0
                for (t in tokens) if (hay.contains(t)) s += 1.0
                if (s > 0) hits[path] = FHit(path, snip.take(120), s)
            }
        }

        val qVec = getEmbedding(query)
        if (qVec != null) {
            val scored = ArrayList<Triple<String, String, Float>>()
            db.rawQuery("SELECT path, snippet, vec FROM file_index WHERE vec IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0)
                    val snip = c.getString(1) ?: ""
                    val vec = unpackVec(c.getBlob(2)) ?: continue
                    val sim = cosine(qVec, vec)
                    if (sim > 0.2f) scored.add(Triple(path, snip, sim))
                }
            }
            scored.sortedByDescending { it.third }.take(limit * 2).forEachIndexed { i, (path, snip, sim) ->
                val h = hits.getOrPut(path) { FHit(path, snip.take(120), 0.0) }
                h.score += 1.0 / (60.0 + i + 1) + sim * 0.5
            }
        }

        val ranked = hits.values.sortedByDescending { it.score }.take(limit)
        if (ranked.isEmpty()) return result("no matching files", JSONObject().put("files", JSONArray()))

        val lines = ranked.map {
            val sc = String.format("%.2f", it.score.coerceAtMost(9.99))
            "${it.path} (score: $sc) - ${it.snippet.take(80)}"
        }
        val text = lines.joinToString("\n").take(1600)
        val arr = JSONArray()
        ranked.forEach {
            arr.put(
                JSONObject()
                    .put("path", it.path)
                    .put("score", it.score)
                    .put("snippet", it.snippet)
            )
        }
        return result(text, JSONObject().put("files", arr))
    }

    fun run(op: String, a: JSONObject): JSONObject = when (op) {
        "save_fact" -> saveFact(a)
        "recall_memory" -> recallMemory(a)
        "recall_files" -> recallFiles(a)
        "sync_file_index" -> syncFileIndex(a)
        "start_indexing" -> startIndexing(a.optBoolean("force", false), a.optInt("max", 10000))
        "get_indexing_status" -> JSONObject().put("text", "status").put("data", getIndexingStatus())
        "cancel_indexing" -> cancelIndexing()
        "list_facts" -> listFacts(a)
        "clear_facts" -> clearFacts(a)
        "update_fact" -> updateFact(a)
        "memory_stats" -> stats()
        "get_user_anchor" -> getUserAnchor()
        "set_user_anchor" -> setUserAnchor(a)
        else -> throw ToolError("unknown memory op: $op")
    }

    fun close() {
        try { db.close() } catch (_: Exception) {}
    }
}

package com.kojot.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolError(msg: String) : Exception(msg)

/** Kotlin port of file_ops.py plus read-only helpers. Everything is confined to [rootDir]. */
class FileTools(rootDir: File, private val gates: List<String> = emptyList()) {
    private val root: File = rootDir.canonicalFile
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun rel(f: File): String = if (f == root) "." else f.relativeTo(root).path

    /** User-configured AI permission gates (relative paths). Blocked for all tool ops. */
    private fun isGated(relPath: String): Boolean {
        val r = relPath.trim().trimStart('/').trimEnd('/')
        if (r.isEmpty() || r == ".") return false
        for (g in gates) {
            val gg = g.trim().trimStart('/').trimEnd('/')
            if (gg.isEmpty()) continue
            if (r == gg || r.startsWith("$gg/")) return true
        }
        return false
    }

    fun safe(p: String?): File {
        val s = (p ?: ".").trim().ifEmpty { "." }
        val f = if (s.startsWith("/")) File(s) else File(root, s)
        val c = f.canonicalFile
        if (c != root && !c.path.startsWith(root.path + File.separator))
            throw ToolError("refused: $s is outside the workspace (${root.path})")
        val r = rel(c)
        if (isGated(r))
            throw ToolError("refused: path is gated by user (AI blocked): $r")
        return c
    }

    private fun sz(n: Long): String = when {
        n < 1024 -> "${n}B"
        n < (1 shl 20) -> "${n shr 10}KB"
        n < (1L shl 30) -> String.format(Locale.US, "%.1fMB", n / 1048576.0)
        else -> String.format(Locale.US, "%.2fGB", n / 1073741824.0)
    }

    private fun strs(a: JSONObject, vararg keys: String): List<String> {
        for (k in keys) {
            val v = a.opt(k) ?: continue
            if (v === JSONObject.NULL) continue
            val out = if (v is JSONArray) (0 until v.length()).map { v.optString(it) } else listOf(v.toString())
            val clean = out.filter { it.isNotBlank() }
            if (clean.isNotEmpty()) return clean
        }
        return emptyList()
    }

    private fun entryJson(f: File): JSONObject = JSONObject()
        .put("n", f.name).put("p", rel(f)).put("d", f.isDirectory)
        .put("s", if (f.isDirectory) 0L else f.length()).put("m", dateFmt.format(Date(f.lastModified()))).put("mt", f.lastModified())

    /** One stat per file (not one per sort comparison): attributes are read once and reused for sorting + output. */
    private class Ent(val f: File, val dir: Boolean, val size: Long, val mt: Long, val lname: String)
    private fun ent(f: File): Ent = try {
        val at = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        Ent(f, at.isDirectory, if (at.isDirectory) 0L else at.size(), at.lastModifiedTime().toMillis(), f.name.lowercase())
    } catch (e: Exception) {
        val d = f.isDirectory
        Ent(f, d, if (d) 0L else f.length(), f.lastModified(), f.name.lowercase())
    }
    private fun entryJson(e: Ent): JSONObject = JSONObject()
        .put("n", e.f.name).put("p", rel(e.f)).put("d", e.dir).put("s", e.size).put("m", dateFmt.format(Date(e.mt))).put("mt", e.mt)
    private fun entryLine(e: Ent): String =
        if (e.dir) "[dir]  ${e.f.name}/" else "[file] ${e.f.name}  ${sz(e.size)}  ${dateFmt.format(Date(e.mt))}"

    private fun entryLine(f: File): String =
        // Avoid listFiles() per dir entry (N+1 FUSE scans) — viewer uses JSON entries only
        if (f.isDirectory) "[dir]  ${f.name}/"
        else "[file] ${f.name}  ${sz(f.length())}  ${dateFmt.format(Date(f.lastModified()))}"

    private fun result(text: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("text", text).apply { if (data != null) put("data", data) }

    // ---- read-only -------------------------------------------------------------------------
    fun listDir(a: JSONObject): JSONObject {
        val d = safe(a.optString("path", "."))
        if (!d.isDirectory) throw ToolError("not a directory: ${rel(d)}")
        val limit = a.optInt("limit", 200).coerceIn(1, 5000)
        val off = a.optInt("offset", 0).coerceAtLeast(0)
        val hidden = a.optBoolean("hidden", false)
        val kids = (d.listFiles() ?: throw ToolError("cannot read ${rel(d)}")).filter { hidden || !it.name.startsWith(".") }.map { ent(it) }
        val sorted = when (a.optString("sort", "name")) {
            "size" -> kids.sortedByDescending { if (it.dir) -1L else it.size }
            "date" -> kids.sortedByDescending { it.mt }
            else -> kids.sortedWith(compareBy<Ent>({ !it.dir }, { it.lname }))
        }
        val page = sorted.drop(off).take(limit)
        val more = sorted.size - off - page.size
        val text = StringBuilder("${rel(d)}: ${sorted.size} entries\n")
        page.forEach { text.append(entryLine(it)).append('\n') }
        if (more > 0) text.append("…$more more (use offset=${off + page.size})")
        if (page.isEmpty()) text.append("(empty)")
        val data = JSONObject().put("entries", JSONArray(page.map { entryJson(it) })).put("total", sorted.size).put("more", maxOf(more, 0))
        return result(text.toString().trim(), data)
    }

    fun find(a: JSONObject): JSONObject {
        val q = a.optString("query").trim()
        if (q.isEmpty()) throw ToolError("query is required")
        val base = safe(a.optString("path", "."))
        if (!base.isDirectory) throw ToolError("not a directory: ${rel(base)}")
        val max = a.optInt("max", 40).coerceIn(1, 100)
        val maxDepth = a.optInt("depth", 8).coerceIn(1, 20)
        val re = if ('*' in q || '?' in q)
            Regex(q.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("", "^", "\$"), RegexOption.IGNORE_CASE)
        else Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
        val out = ArrayList<File>()
        var seen = 0
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (out.size >= max || seen > 40000) return
                seen++
                if (k.name.startsWith(".")) continue
                if (re.containsMatchIn(k.name)) out.add(k)
                if (k.isDirectory && depth < maxDepth && !Files.isSymbolicLink(k.toPath())) walk(k, depth + 1)
            }
        }
        walk(base, 1)
        val text = StringBuilder("${out.size} match(es) for \"$q\" under ${rel(base)}\n")
        out.forEach {
            text.append(if (it.isDirectory) "[dir]  ${rel(it)}/" else "[file] ${rel(it)}  ${sz(it.length())}  ${dateFmt.format(Date(it.lastModified()))}").append('\n')
        }
        if (out.size >= max) text.append("(stopped at $max results — narrow the query or path)")
        val data = JSONObject().put("entries", JSONArray(out.map { entryJson(it) })).put("total", out.size).put("more", 0).put("full", true)
        return result(text.toString().trim(), data)
    }

    fun read(a: JSONObject): JSONObject {
        val f = safe(a.optString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        if (a.optBoolean("full", false)) {
                    if (f.length() > 2L * 1024 * 1024) throw ToolError("file too large for full read (>2MB)")
                    val bytes = f.readBytes()
                    if (bytes.any { it.toInt() == 0 }) {
                        // Flag so editors must not treat this as savable content
                        return result("${rel(f)} is a binary file (${sz(f.length())}); not shown.")
                            .put("data", JSONObject().put("binary", true).put("size", f.length()))
                    }
                    return result(String(bytes, Charsets.UTF_8), JSONObject().put("mtime", f.lastModified()).put("size", f.length()))
                }
                val max = a.optInt("max_chars", 3000).coerceIn(200, 8000)
        val offset = a.optLong("offset", 0L).coerceAtLeast(0L)
        val fileLen = f.length()
        if (offset >= fileLen && fileLen > 0L) {
            return result("EOF: offset $offset is beyond file size (${sz(fileLen)})", JSONObject().put("eof", true).put("total", fileLen))
        }
        val buf = ByteArray(minOf(fileLen - offset, 32768L).toInt())
        val n = f.inputStream().use {
            if (offset > 0L) it.skip(offset)
            it.read(buf)
        }
        val b = buf.copyOf(maxOf(n, 0))
        if (b.any { it.toInt() == 0 }) return result("${rel(f)} is a binary file (${sz(fileLen)}); not shown.")
        val full = String(b, Charsets.UTF_8)
        val shown = full.take(max)
        val nextOffset = offset + shown.toByteArray(Charsets.UTF_8).size
        val hasMore = nextOffset < fileLen
        val notice = if (hasMore) "\n…[more content: next offset=$nextOffset, total=${sz(fileLen)}]" else ""
        val data = JSONObject()
            .put("offset", offset)
            .put("next_offset", nextOffset)
            .put("total_bytes", fileLen)
            .put("has_more", hasMore)
        return result(shown + notice, data)
    }

    // ---- changes (file_ops.py) -----------------------------------------------------------------
    fun mkdir(a: JSONObject): JSONObject {
        val ps = strs(a, "paths", "path")
        if (ps.isEmpty()) throw ToolError("paths is required")
        val lines = ps.map { val d = safe(it); d.mkdirs(); "ok ${rel(d)}/" }
        return result(lines.joinToString("\n"), JSONObject().put("paths", JSONArray(ps)))
    }

    private fun targetOf(srcArg: String, dst: File, dstArg: String, multi: Boolean): File {
        val name = File(srcArg.trimEnd('/', '\\')).name
        if (dst.isDirectory || dstArg.endsWith("/")) return File(dst, name)
        if (multi) throw ToolError("with several sources the destination must be a folder (end it with /)")
        return dst
    }

    fun moveCopy(a: JSONObject, copy: Boolean): JSONObject {
        val srcs = strs(a, "src", "sources", "paths")
        val dstArg = a.optString("dst").ifBlank { a.optString("destination") }
        if (srcs.isEmpty() || dstArg.isBlank()) throw ToolError("src and dst are required")
        val dst = safe(dstArg)
        val force = a.optBoolean("overwrite", false)
        val verb = if (copy) "copied" else "moved"
        val lines = ArrayList<String>()
        val moves = JSONArray()
        var done = 0
        for (s in srcs) {
            val item = JSONObject().put("from", s)
            try {
                val src = safe(s)
                val dest = targetOf(s, dst, dstArg, srcs.size > 1)
                item.put("to", rel(dest))
                if (!src.exists()) throw ToolError("not found: ${rel(src)}")
                if (src == root || rel(src) == ".git") throw ToolError("refused: will not $verb ${rel(src)}")
                if (dest == src || dest.path.startsWith(src.path + File.separator)) throw ToolError("refused: cannot put ${rel(src)} inside itself")
                if (dest.exists() && !force) throw ToolError("refused: ${rel(dest)} already exists (set overwrite=true to replace)")
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.deleteRecursively()
                if (copy) {
                    if (src.isDirectory) src.copyRecursively(dest, true) else src.copyTo(dest, true)
                } else if (!src.renameTo(dest)) {
                    src.copyRecursively(dest, true)
                    src.deleteRecursively()
                }
                lines.add("$verb ${rel(src)} -> ${rel(dest)}")
                item.put("ok", true)
                done++
            } catch (e: Exception) {
                lines.add("failed: ${e.message}")
                item.put("ok", false).put("err", e.message ?: "error")
            }
            moves.put(item)
        }
        if (done == 0) throw ToolError(lines.joinToString("\n"))
        return result(lines.joinToString("\n"), JSONObject().put("moves", moves))
    }

    fun delete(a: JSONObject): JSONObject {
        val ps = strs(a, "paths", "path")
        if (ps.isEmpty()) throw ToolError("paths is required")
        val hard = a.optBoolean("permanent", false)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val lines = ArrayList<String>()
        var done = 0
        for (p in ps) {
            try {
                val f = safe(p)
                val r = rel(f)
                if (f == root || r == ".git") throw ToolError("refused: will not delete $r")
                if (!f.exists()) throw ToolError("not found: $r")
                val isDir = f.isDirectory
                if (hard || r.startsWith(".agent/trash")) {
                    f.deleteRecursively()
                    lines.add("deleted $r${if (isDir) "/" else ""} permanently")
                } else {
                    val dest = File(root, ".agent/trash/$stamp/$r")
                    dest.parentFile?.mkdirs()
                    if (!f.renameTo(dest)) {
                        f.copyRecursively(dest, true)
                        f.deleteRecursively()
                    }
                    lines.add("trashed $r${if (isDir) "/" else ""} -> ${rel(dest)} (restore with move)")
                }
                done++
            } catch (e: Exception) {
                lines.add("failed: ${e.message}")
            }
        }
        if (done == 0) throw ToolError(lines.joinToString("\n"))
        return result(lines.joinToString("\n"), JSONObject().put("hard", hard))
    }

    // ---- write / edit ------------------------------------------------------------------------
    fun write(a: JSONObject): JSONObject {
        val path = a.optString("path").trim()
        if (path.isEmpty()) throw ToolError("path is required")
        val content = a.optString("content", "")
        val overwrite = a.optBoolean("overwrite", false)
        val f = safe(path)
        if (f == root || rel(f) == ".git") throw ToolError("refused: will not write to ${rel(f)}")
        if (f.exists() && !overwrite) throw ToolError("refused: ${rel(f)} already exists (set overwrite=true to replace)")
        // Optional optimistic-concurrency guard: the editor passes the mtime it last saw.
        val expect = a.optLong("expect_mtime", 0L)
        if (expect > 0L && f.exists() && f.lastModified() != expect)
            throw ToolError("CONFLICT: ${rel(f)} changed on disk since it was opened (another app or the AI agent)")
        f.parentFile?.mkdirs()
        atomicWrite(f, content)
        return result("wrote ${sz(f.length())} to ${rel(f)}", JSONObject().put("mtime", f.lastModified()).put("size", f.length()))
    }

    /** Write to a temp file in the same folder, then rename over the target, so a kill mid-write can't truncate the user's file. */
    private fun atomicWrite(f: File, content: String) {
        val tmp = File(f.parentFile, ".${f.name}.kojot-tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            f.writeText(content, Charsets.UTF_8)
        }
    }

    fun stat(a: JSONObject): JSONObject {
        val f = safe(a.optString("path"))
        if (!f.exists()) throw ToolError("not found: ${rel(f)}")
        return result(rel(f), JSONObject().put("mtime", f.lastModified()).put("size", if (f.isDirectory) 0L else f.length()).put("dir", f.isDirectory))
    }

    /** Project-wide plain-text search (case-insensitive substring). Skips hidden/vendor dirs, big/binary files and gated paths. */
    fun searchText(a: JSONObject): JSONObject {
        val q = a.optString("query").trim()
        if (q.isEmpty()) throw ToolError("query is required")
        val base = safe(a.optString("path", "."))
        if (!base.isDirectory) throw ToolError("not a directory: ${rel(base)}")
        val max = a.optInt("max", 80).coerceIn(1, 300)
        val maxDepth = a.optInt("depth", 10).coerceIn(1, 20)
        val skipDirs = setOf("node_modules", "build", "vs", "__pycache__")
        val hits = JSONArray()
        var scanned = 0
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (hits.length() >= max || scanned > 5000) return
                if (k.name.startsWith(".") || k.name in skipDirs) continue
                val r = rel(k)
                if (isGated(r)) continue
                if (k.isDirectory) {
                    if (depth < maxDepth && !Files.isSymbolicLink(k.toPath())) walk(k, depth + 1)
                    continue
                }
                if (k.length() > 1_000_000L) continue
                scanned++
                try {
                    val head = ByteArray(512)
                    val n = k.inputStream().use { it.read(head) }
                    if ((0 until maxOf(n, 0)).any { head[it].toInt() == 0 }) continue
                    k.bufferedReader(Charsets.UTF_8).use { rd ->
                        var ln = 0
                        while (true) {
                            val line = rd.readLine() ?: break
                            ln++
                            if (line.contains(q, ignoreCase = true)) {
                                hits.put(JSONObject().put("p", r).put("l", ln).put("t", line.trim().take(160)))
                                if (hits.length() >= max) break
                            }
                        }
                    }
                } catch (e: Exception) { /* unreadable file: skip */ }
            }
        }
        walk(base, 1)
        val text = StringBuilder("${hits.length()} hit(s) for \"$q\" under ${rel(base)}\n")
        for (i in 0 until hits.length()) { val h = hits.getJSONObject(i); text.append("${h.getString("p")}:${h.getInt("l")}: ${h.getString("t")}\n") }
        if (hits.length() >= max) text.append("(stopped at $max results — narrow the query)")
        return result(text.toString().trim(), JSONObject().put("hits", hits).put("total", hits.length()))
    }

    fun edit(a: JSONObject): JSONObject {
        val path = a.optString("path").trim()
        val search = a.optString("search")
        val replace = a.optString("replace")
        if (path.isEmpty()) throw ToolError("path is required")
        if (search.isEmpty()) throw ToolError("search is required and must not be empty")
        val f = safe(path)
        if (!f.isFile) throw ToolError("not a regular file: ${rel(f)}")
        val raw = f.readText(Charsets.UTF_8)
        var count = 0
        var from = 0
        while (true) {
            val i = raw.indexOf(search, from)
            if (i < 0) break
            count++
            from = i + search.length
        }
        if (count == 0) {
            val normSearch = search.replace("\r\n", "\n")
            val normRaw = raw.replace("\r\n", "\n")
            val idx = normRaw.indexOf(normSearch)
            if (idx < 0)
                throw ToolError("search block not found in ${rel(f)}")
            val normReplace = replace.replace("\r\n", "\n")
            val updated = normRaw.replaceFirst(normSearch, normReplace)
            atomicWrite(f, updated)
            return result("edited ${rel(f)} (applied with normalized line endings)")
        }
        if (count > 1)
            throw ToolError("search block appears $count times; provide more surrounding lines")
        val updated = raw.replaceFirst(search, replace)
        atomicWrite(f, updated)
        return result("edited ${rel(f)} (replaced 1 block)")
    }

    fun rename(a: JSONObject): JSONObject {
        val path = a.optString("path").trim()
        val newName = a.optString("name").trim().ifBlank { a.optString("new_name").trim() }
        if (path.isEmpty() || newName.isEmpty()) throw ToolError("path and name are required")
        if (newName.contains('/') || newName.contains('\\')) throw ToolError("name must not contain path separators")
        val src = safe(path)
        if (!src.exists()) throw ToolError("not found: ${rel(src)}")
        if (src == root) throw ToolError("refused: cannot rename workspace root")
        val dest = File(src.parentFile, newName)
        if (dest.exists()) throw ToolError("already exists: ${rel(dest)}")
        if (!src.renameTo(dest)) {
            src.copyRecursively(dest, true)
            src.deleteRecursively()
        }
        return result("renamed ${rel(src)} → ${rel(dest)}", JSONObject().put("path", rel(dest)).put("from", rel(src)))
    }

    fun run(op: String, a: JSONObject): JSONObject = when (op) {
        "list_dir" -> listDir(a)
        "find_files" -> find(a)
        "read_file" -> read(a)
        "make_dir" -> mkdir(a)
        "move" -> moveCopy(a, false)
        "copy" -> moveCopy(a, true)
        "delete" -> delete(a)
        "rename" -> rename(a)
        "write_file" -> write(a)
        "edit_file" -> edit(a)
        "stat" -> stat(a)
        "search_text" -> searchText(a)
        else -> throw ToolError("unknown tool: $op")
    }
}

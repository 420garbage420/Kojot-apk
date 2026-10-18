package com.kojot.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolError(msg: String) : Exception(msg)

/** Kotlin port of file_ops.py plus read-only helpers. Everything is confined to [rootDir]. */
class FileTools(rootDir: File) {
    private val root: File = rootDir.canonicalFile
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun rel(f: File): String = if (f == root) "." else f.relativeTo(root).path

    fun safe(p: String?): File {
        val s = (p ?: ".").trim().ifEmpty { "." }
        val f = if (s.startsWith("/")) File(s) else File(root, s)
        val c = f.canonicalFile
        if (c != root && !c.path.startsWith(root.path + File.separator))
            throw ToolError("refused: $s is outside the workspace (${root.path})")
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
        .put("s", if (f.isDirectory) 0L else f.length()).put("m", dateFmt.format(Date(f.lastModified())))

    private fun entryLine(f: File): String =
        if (f.isDirectory) "[dir]  ${f.name}/  (${f.listFiles()?.size ?: "?"} items)"
        else "[file] ${f.name}  ${sz(f.length())}  ${dateFmt.format(Date(f.lastModified()))}"

    private fun result(text: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("text", text).apply { if (data != null) put("data", data) }

    // ---- read-only -------------------------------------------------------------------------
    fun listDir(a: JSONObject): JSONObject {
        val d = safe(a.optString("path", "."))
        if (!d.isDirectory) throw ToolError("not a directory: ${rel(d)}")
        val limit = a.optInt("limit", 60).coerceIn(1, 200)
        val off = a.optInt("offset", 0).coerceAtLeast(0)
        val hidden = a.optBoolean("hidden", false)
        val kids = (d.listFiles() ?: throw ToolError("cannot read ${rel(d)}")).filter { hidden || !it.name.startsWith(".") }
        val sorted = when (a.optString("sort", "name")) {
            "size" -> kids.sortedByDescending { if (it.isDirectory) -1L else it.length() }
            "date" -> kids.sortedByDescending { it.lastModified() }
            else -> kids.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
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
        val max = a.optInt("max_chars", 3000).coerceIn(200, 8000)
        val buf = ByteArray(minOf(f.length(), 32768L).toInt())
        val n = f.inputStream().use { it.read(buf) }
        val b = buf.copyOf(maxOf(n, 0))
        if (b.any { it.toInt() == 0 }) return result("${rel(f)} is a binary file (${sz(f.length())}); not shown.")
        val full = String(b, Charsets.UTF_8)
        val shown = full.take(max)
        val cut = shown.length < full.length || f.length() > b.size
        return result(shown + if (cut) "\n…(truncated; file is ${sz(f.length())})" else "")
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
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return result("wrote ${sz(f.length())} to ${rel(f)}")
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
            f.writeText(updated, Charsets.UTF_8)
            return result("edited ${rel(f)} (applied with normalized line endings)")
        }
        if (count > 1)
            throw ToolError("search block appears $count times; provide more surrounding lines")
        val updated = raw.replaceFirst(search, replace)
        f.writeText(updated, Charsets.UTF_8)
        return result("edited ${rel(f)} (replaced 1 block)")
    }

    fun run(op: String, a: JSONObject): JSONObject = when (op) {
        "list_dir" -> listDir(a)
        "find_files" -> find(a)
        "read_file" -> read(a)
        "make_dir" -> mkdir(a)
        "move" -> moveCopy(a, false)
        "copy" -> moveCopy(a, true)
        "delete" -> delete(a)
        "write_file" -> write(a)
        "edit_file" -> edit(a)
        else -> throw ToolError("unknown tool: $op")
    }
}

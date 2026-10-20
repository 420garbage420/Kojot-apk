package com.kojot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Binary PDF I/O + on-device OCR (ML Kit) with line bounding boxes.
 * Boxes are in PDF user space (origin bottom-left) so the agent can stamp text.
 */
class PdfTools(private val rootDir: File, private val appCtx: Context) {
    private val root = rootDir.canonicalFile

    private fun safe(p: String?): File {
        val s = (p ?: ".").trim().ifEmpty { "." }
        val f = if (s.startsWith("/")) File(s) else File(root, s)
        val c = f.canonicalFile
        if (c != root && !c.path.startsWith(root.path + File.separator))
            throw ToolError("refused: $s is outside the workspace (${root.path})")
        return c
    }

    private fun rel(f: File): String = if (f == root) "." else f.relativeTo(root).path

    private fun result(text: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("text", text).apply { if (data != null) put("data", data) }

    fun readB64(a: JSONObject): JSONObject {
        val f = safe(a.getString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        if (f.length() > 40L * 1024 * 1024) throw ToolError("file too large (>40MB)")
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        return result("read ${rel(f)} (${f.length()} bytes)", JSONObject()
            .put("path", rel(f))
            .put("size", f.length())
            .put("b64", b64)
            .put("mime", if (f.name.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream"))
    }

    fun writeB64(a: JSONObject): JSONObject {
        val f = safe(a.getString("path"))
        f.parentFile?.mkdirs()
        val b64 = a.getString("b64")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        f.writeBytes(bytes)
        return result("wrote ${rel(f)} (${bytes.size} bytes)", JSONObject()
            .put("path", rel(f)).put("size", bytes.size.toLong()))
    }

    fun ocrImage(a: JSONObject): JSONObject {
        val f = safe(a.getString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        // Prefer vision GGUF when server is up; still attach ML Kit line boxes when available
        var visionText: String? = null
        try {
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) {
                visionText = visionOcrText(bmp)
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (_: Exception) {}
        val image = InputImage.fromFilePath(appCtx, android.net.Uri.fromFile(f))
        val detail = recognizeDetailed(image, imgW = 0, imgH = 0, pdfW = 0f, pdfH = 0f, scale = 1f)
        if (detail == null && visionText.isNullOrBlank()) {
            throw ToolError("OCR failed (no ML Kit and vision server unavailable)")
        }
        val out = detail ?: JSONObject().put("text", "").put("lines", JSONArray()).put("anchors", JSONArray())
        if (!visionText.isNullOrBlank()) {
            out.put("text", visionText)
            out.put("engine", "gguf+mlkit")
        } else {
            out.put("engine", "mlkit")
        }
        return result(out.optString("text"), out.put("path", rel(f)))
    }

    /**
     * OCR PDF pages with line-level boxes in PDF coordinates + suggested fill anchors
     * (to the right of labels ending with ':' or short field-like lines).
     */
    fun ocrPdf(a: JSONObject): JSONObject {
        val f = safe(a.getString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        val maxPages = a.optInt("max_pages", 8).coerceIn(1, 30)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val n = minOf(renderer.pageCount, maxPages)
            val pages = JSONArray()
            val allLines = JSONArray()
            val anchors = JSONArray()
            val sb = StringBuilder()
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                try {
                    val scale = 2f
                    val pdfW = page.width.toFloat()
                    val pdfH = page.height.toFloat()
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt(),
                        (page.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val visionText = visionOcrText(bmp)
                    val detail = recognizeDetailed(InputImage.fromBitmap(bmp, 0), bmp.width, bmp.height, pdfW, pdfH, scale)
                        ?: JSONObject().put("text", "").put("lines", JSONArray()).put("anchors", JSONArray())
                    if (!visionText.isNullOrBlank()) {
                        detail.put("text", visionText)
                        detail.put("engine", "gguf+mlkit")
                    } else {
                        detail.put("engine", "mlkit")
                    }
                    bmp.recycle()

                    val pageNo = i + 1
                    val pageObj = JSONObject()
                        .put("page", pageNo)
                        .put("width", pdfW.toDouble())
                        .put("height", pdfH.toDouble())
                        .put("text", detail.optString("text"))
                        .put("chars", detail.optString("text").length)
                        .put("engine", detail.optString("engine", "mlkit"))
                        .put("lines", detail.optJSONArray("lines") ?: JSONArray())
                        .put("anchors", detail.optJSONArray("anchors") ?: JSONArray())
                    pages.put(pageObj)

                    val lines = detail.optJSONArray("lines") ?: JSONArray()
                    for (li in 0 until lines.length()) {
                        val line = lines.getJSONObject(li)
                        allLines.put(JSONObject(line.toString()).put("page", pageNo))
                    }
                    val ancs = detail.optJSONArray("anchors") ?: JSONArray()
                    for (ai in 0 until ancs.length()) {
                        anchors.put(JSONObject(ancs.getJSONObject(ai).toString()).put("page", pageNo))
                    }

                    val t = detail.optString("text")
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n--- page $pageNo ---\n")
                        else sb.append("--- page $pageNo ---\n")
                        sb.append(t)
                    }
                } finally {
                    page.close()
                }
            }
            val more = renderer.pageCount - n
            val engines = mutableSetOf<String>()
            for (i in 0 until pages.length()) {
                val e = pages.optJSONObject(i)?.optString("engine", "mlkit") ?: "mlkit"
                engines.add(e)
            }
            val summary = buildString {
                append("OCR ${rel(f)}: ${renderer.pageCount} pages")
                if (more > 0) append(" (first $n OCR'd, $more skipped)")
                append(", ${allLines.length()} lines, ${anchors.length()} fill anchors")
                append(" [engine: ${engines.joinToString("+")}]\n")
                append(if (sb.isBlank()) "(no text recognized)" else sb.toString())
            }
            return result(summary, JSONObject()
                .put("path", rel(f))
                .put("page_count", renderer.pageCount)
                .put("ocr_pages", n)
                .put("pages", pages)
                .put("lines", allLines)
                .put("anchors", anchors)
                .put("text", sb.toString())
                .put("coords", "pdf") // origin bottom-left, units = PDF points
            )
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    fun pdfInfo(a: JSONObject): JSONObject {
        val f = safe(a.getString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            return result(
                "${rel(f)}: ${renderer.pageCount} pages, ${f.length()} bytes",
                JSONObject()
                    .put("path", rel(f))
                    .put("page_count", renderer.pageCount)
                    .put("size", f.length())
            )
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    /**
     * @param scale bitmap pixels per PDF point (same on x and y when rendering uniformly)
     * When pdfW/pdfH are 0, boxes stay in image pixel space (top-left origin).
     */
    /** True when optional OCR llama-server is up on :8082. */
    private fun ocrServerUp(): Boolean {
        return try {
            val c = URL("http://127.0.0.1:8082/health").openConnection() as HttpURLConnection
            c.connectTimeout = 400
            c.readTimeout = 400
            c.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun bitmapToJpegB64(bmp: Bitmap, quality: Int = 85): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Vision OCR via optional GGUF server (:8082 + mmproj).
     * Returns plain text (no line boxes). Caller merges with ML Kit geometry when needed.
     */
    private fun visionOcrText(bmp: Bitmap): String? {
        if (!ocrServerUp()) return null
        return try {
            val b64 = bitmapToJpegB64(bmp)
            val body = JSONObject()
                .put("model", "local")
                .put("max_tokens", 2048)
                .put("temperature", 0.1)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                        .put(JSONObject()
                            .put("type", "text")
                            .put("text", "Extract ALL readable text from this document image. Preserve reading order and line breaks. Output plain text only — no markdown fences, no commentary."))
                    )
                ))
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val c = URL("http://127.0.0.1:8082/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader()?.readText() ?: return null
            if (code !in 200..299) {
                Log.w(TAG, "vision OCR HTTP $code: ${resp.take(200)}")
                return null
            }
            val j = JSONObject(resp)
            val choices = j.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.getJSONObject(0).optJSONObject("message")
            val content = msg?.optString("content")?.trim().orEmpty()
            if (content.isBlank()) null else content
        } catch (e: Exception) {
            Log.w(TAG, "vision OCR failed: ${e.message}")
            null
        }
    }

    private fun recognizeDetailed(
        image: InputImage,
        imgW: Int,
        imgH: Int,
        pdfW: Float,
        pdfH: Float,
        scale: Float
    ): JSONObject? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Text?>(null)
        val err = AtomicReference<Exception?>(null)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                ref.set(result)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                err.set(e)
                latch.countDown()
            }
        val ok = latch.await(45, TimeUnit.SECONDS)
        recognizer.close()
        if (!ok) throw ToolError("OCR timed out")
        err.get()?.let { throw ToolError("OCR error: ${it.message}") }
        val vision = ref.get() ?: return null

        val linesArr = JSONArray()
        val anchors = JSONArray()
        val toPdf = pdfW > 0f && pdfH > 0f && scale > 0f

        fun boxJson(r: Rect?): JSONObject {
            if (r == null) return JSONObject()
            return if (toPdf) {
                // Image: origin top-left, y down. PDF: origin bottom-left, y up.
                val left = r.left / scale
                val right = r.right / scale
                val top = r.top / scale
                val bottom = r.bottom / scale
                val x = left
                val y = pdfH - bottom // bottom edge of text baseline-ish
                val w = (right - left).coerceAtLeast(0f)
                val h = (bottom - top).coerceAtLeast(0f)
                JSONObject()
                    .put("x", x.toDouble())
                    .put("y", y.toDouble())
                    .put("w", w.toDouble())
                    .put("h", h.toDouble())
            } else {
                JSONObject()
                    .put("x", r.left)
                    .put("y", r.top)
                    .put("w", r.width())
                    .put("h", r.height())
            }
        }

        for (block in vision.textBlocks) {
            for (line in block.lines) {
                val t = line.text?.trim() ?: continue
                if (t.isEmpty()) continue
                val box = line.boundingBox
                val lineObj = JSONObject()
                    .put("text", t)
                    .put("box", boxJson(box))
                linesArr.put(lineObj)

                // Suggest fill anchor: to the right of label-like lines
                val labelish = t.endsWith(":") ||
                    (t.length in 2..40 && !t.contains('\n') && t.count { it.isLetter() } >= t.length / 2)
                if (labelish && box != null && toPdf) {
                    val left = box.left / scale
                    val right = box.right / scale
                    val top = box.top / scale
                    val bottom = box.bottom / scale
                    val h = (bottom - top).coerceAtLeast(8f)
                    val fillX = right + 8f
                    val fillY = pdfH - bottom // baseline of the label row
                    // Skip if there's almost no room on the right (likely multi-column noise)
                    if (fillX < pdfW * 0.85f) {
                        anchors.put(JSONObject()
                            .put("label", t.trimEnd(':').trim())
                            .put("x", fillX.toDouble())
                            .put("y", fillY.toDouble())
                            .put("size", h.coerceIn(9f, 16f).toDouble())
                            .put("hint", "right_of_label")
                        )
                    }
                }
            }
        }

        return JSONObject()
            .put("text", vision.text ?: "")
            .put("lines", linesArr)
            .put("anchors", anchors)
    }

    fun run(op: String, a: JSONObject): JSONObject = when (op) {
        "read_file_b64" -> readB64(a)
        "write_file_b64" -> writeB64(a)
        "ocr_image" -> ocrImage(a)
        "ocr_pdf" -> ocrPdf(a)
        "pdf_info" -> pdfInfo(a)
        else -> throw ToolError("unknown pdf tool: $op")
    }

    companion object {
        private const val TAG = "PdfTools"
        val OPS = setOf("read_file_b64", "write_file_b64", "ocr_image", "ocr_pdf", "pdf_info")
    }
}

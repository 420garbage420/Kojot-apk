package com.kojot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Binary PDF I/O + optional on-device OCR (ML Kit).
 * Form-field listing/filling is done in the WebView with pdf-lib; this class
 * supplies bytes and OCR text for scanned pages.
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

    /** OCR a single image file (png/jpg/webp). */
    fun ocrImage(a: JSONObject): JSONObject {
        val f = safe(a.getString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        val text = runMlKitOnFile(f)
            ?: throw ToolError("OCR failed or ML Kit unavailable")
        return result(text, JSONObject().put("path", rel(f)).put("chars", text.length))
    }

    /**
     * Render PDF pages with [PdfRenderer] and OCR each page via ML Kit.
     * Returns concatenated text; useful for scans with no text layer.
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
            val sb = StringBuilder()
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                try {
                    val scale = 2
                    val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val t = runMlKitOnBitmap(bmp) ?: ""
                    bmp.recycle()
                    pages.put(JSONObject().put("page", i + 1).put("text", t).put("chars", t.length))
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n--- page ${i + 1} ---\n")
                        else sb.append("--- page ${i + 1} ---\n")
                        sb.append(t)
                    }
                } finally {
                    page.close()
                }
            }
            val more = renderer.pageCount - n
            val summary = buildString {
                append("OCR ${rel(f)}: ${renderer.pageCount} pages")
                if (more > 0) append(" (first $n OCR'd, $more skipped)")
                append("\n")
                append(if (sb.isBlank()) "(no text recognized — try higher resolution scan)" else sb.toString())
            }
            return result(summary, JSONObject()
                .put("path", rel(f))
                .put("page_count", renderer.pageCount)
                .put("ocr_pages", n)
                .put("pages", pages)
                .put("text", sb.toString()))
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    /** Quick metadata: page count (no OCR). */
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

    private fun runMlKitOnFile(f: File): String? {
        return try {
            val image = InputImage.fromFilePath(appCtx, android.net.Uri.fromFile(f))
            recognize(image)
        } catch (e: Exception) {
            Log.w(TAG, "ocr image failed: ${e.message}")
            null
        }
    }

    private fun runMlKitOnBitmap(bmp: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bmp, 0)
            recognize(image)
        } catch (e: Exception) {
            Log.w(TAG, "ocr bitmap failed: ${e.message}")
            null
        }
    }

    private fun recognize(image: InputImage): String? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val latch = CountDownLatch(1)
        val ref = AtomicReference<String?>(null)
        val err = AtomicReference<Exception?>(null)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                ref.set(result.text ?: "")
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
        return ref.get()
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

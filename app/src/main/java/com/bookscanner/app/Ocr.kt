package com.bookscanner.app

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object Ocr {
    val recognizer by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    private const val PROMPT = "Transcribe all the text in this image exactly as written, in natural reading order. " +
        "Keep the original language and script (Hindi, Marathi, English or any other). Preserve paragraphs and line breaks. " +
        "Do not translate, summarize or add commentary. If the image has no text, describe it in one short sentence starting with 'Image:'."

    suspend fun read(ctx: Context, file: File, online: Boolean): String {
        if (online) {
            try { return gemini(ctx, file) } catch (e: Exception) {
                Worker.note = "Online reading failed (${e.message}). Offline reader was used for some pages."
            }
        }
        return offline(file)
    }

    private suspend fun offline(file: File): String {
        val bmp = BitmapFactory.decodeFile(file.path) ?: return ""
        try {
            val r = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            return r.text.trim()
        } finally { bmp.recycle() }
    }

    private suspend fun gemini(ctx: Context, file: File): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val key = Prefs.key(ctx)
        if (key.isBlank()) throw IllegalStateException("Gemini API key is not set")
        val bmp = BitmapFactory.decodeFile(file.path) ?: throw IllegalStateException("Cannot read image")
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        bmp.recycle()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val p1 = JSONObject().put("text", PROMPT)
        val p2 = JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(p1).put(p2))))
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${Prefs.model(ctx)}:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30000
        c.readTimeout = 120000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        sb.toString().trim()
    }
}

object Worker {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var note: String = ""

    fun pending(): Int = Store.snapshot().count { it.text == null }
    private fun fire() { listeners.forEach { it() } }

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val next = Store.snapshot().firstOrNull { it.text == null } ?: break
                    val text = try { Ocr.read(app, next.file, Prefs.online(app)) } catch (e: Exception) { "" }
                    Store.setText(next, text)
                    fire()
                }
            } finally { running.set(false) }
            if (pending() > 0) kick(app)
        }
    }
}

object Importer {
    private fun matrixFor(o: Int): Matrix? {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    fun decode(ctx: Context, uri: Uri, maxDim: Int = 2400): Bitmap? {
        val cr = ctx.contentResolver
        val b = BitmapFactory.Options()
        b.inJustDecodeBounds = true
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        if (b.outWidth <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val orient = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = matrixFor(orient)
        if (m != null) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val big = maxOf(bmp.width, bmp.height)
        if (big > maxDim) {
            val s = maxDim.toFloat() / big
            val r = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    private fun saveBitmap(bmp: Bitmap, file: File) {
        var out = bmp
        if (bmp.hasAlpha()) {
            out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            c.drawBitmap(bmp, 0f, 0f, null)
        }
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (out !== bmp) out.recycle()
    }

    fun addFromUri(ctx: Context, uri: Uri): Boolean {
        val bmp = decode(ctx, uri) ?: return false
        val f = Store.newFile()
        saveBitmap(bmp, f)
        bmp.recycle()
        Store.add(Page(f))
        Worker.kick(ctx)
        return true
    }

    fun addFromFile(ctx: Context, file: File): Int {
        val bmp = decode(ctx, Uri.fromFile(file))
        if (bmp != null) { saveBitmap(bmp, file); bmp.recycle() }
        val n = Store.add(Page(file))
        Worker.kick(ctx)
        return n
    }

    private fun isPdf(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            val b = ByteArray(4)
            it.read(b) == 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() && b[3] == 'F'.code.toByte()
        } ?: false
    } catch (e: Exception) { false }

    private fun importPdf(ctx: Context, uri: Uri, progress: (String) -> Unit): Int {
        val tmp = File(ctx.cacheDir, "in_${System.nanoTime()}.pdf")
        ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: return 0
        var added = 0
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            val n = r.pageCount
            for (i in 0 until n) {
                progress("Importing PDF page ${i + 1} of $n")
                val p = r.openPage(i)
                try {
                    val scale = 1800f / maxOf(p.width, p.height)
                    val w = (p.width * scale).toInt().coerceAtLeast(1)
                    val h = (p.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val f = Store.newFile()
                    saveBitmap(bmp, f)
                    bmp.recycle()
                    Store.add(Page(f))
                    added++
                } finally { p.close() }
                Worker.kick(ctx)
            }
        } finally { r.close(); pfd.close(); tmp.delete() }
        return added
    }

    fun importUris(ctx: Context, uris: List<Uri>, progress: (String) -> Unit): Int {
        var added = 0
        uris.forEachIndexed { idx, uri ->
            try {
                progress("Importing file ${idx + 1} of ${uris.size}")
                if (isPdf(ctx, uri)) added += importPdf(ctx, uri, progress)
                else if (addFromUri(ctx, uri)) added++
            } catch (e: Exception) {
                Worker.note = "Could not import a file: ${e.message}"
            }
        }
        return added
    }
}

object Docs {
    private const val DIR = "Documents/BookScanner"
    data class Item(val uri: Uri, val name: String, val mime: String, val size: Long)

    fun save(ctx: Context, name: String, mime: String, writer: (java.io.OutputStream) -> Unit): Uri {
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), v)
            ?: throw IllegalStateException("Cannot create file")
        try {
            ctx.contentResolver.openOutputStream(uri)!!.buffered().use { writer(it) }
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val base = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE)
        ctx.contentResolver.query(base, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$DIR%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                out.add(Item(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1) ?: "file", c.getString(2) ?: "*/*", c.getLong(3)))
            }
        }
        return out
    }
}

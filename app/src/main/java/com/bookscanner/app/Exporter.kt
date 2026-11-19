package com.bookscanner.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {
    private const val W = 595
    private const val H = 842

    private fun pageText(p: Page): String {
        val t = p.text?.trim().orEmpty()
        return if (t.isEmpty()) "[No text recognized on this page]" else t
    }

    fun txt(pages: List<Page>, out: OutputStream) {
        val s = pages.mapIndexed { i, p -> "Page ${i + 1}\n\n${pageText(p)}" }.joinToString("\n\n\n")
        out.write(s.toByteArray(Charsets.UTF_8))
    }

    fun textPdf(pages: List<Page>, out: OutputStream) {
        val doc = PdfDocument()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 13f
        paint.color = Color.BLACK
        val margin = 40
        val w = W - 2 * margin
        val availH = H - 2 * margin
        var no = 1
        for (p in pages) {
            val text = pageText(p)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setLineSpacing(0f, 1.2f).build()
            var start = 0
            while (start < layout.lineCount) {
                val top = layout.getLineTop(start)
                var end = start
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= availH) end++
                if (end == start) end = start + 1
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(W, H, no++).create())
                val c = pg.canvas
                c.save()
                c.translate(margin.toFloat(), margin.toFloat() - top)
                c.clipRect(0f, top.toFloat(), w.toFloat(), layout.getLineBottom(end - 1).toFloat())
                layout.draw(c)
                c.restore()
                doc.finishPage(pg)
                start = end
            }
        }
        doc.writeTo(out)
        doc.close()
    }

    fun imagePdf(pages: List<Page>, out: OutputStream) {
        val doc = PdfDocument()
        var no = 1
        for (p in pages) {
            val opts = BitmapFactory.Options()
            opts.inSampleSize = 1
            val bmp = BitmapFactory.decodeFile(p.file.path, opts) ?: continue
            val h = (W * bmp.height.toFloat() / bmp.width).toInt().coerceAtLeast(1)
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(W, h, no++).create())
            pg.canvas.drawBitmap(bmp, null, Rect(0, 0, W, h), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(pg)
            bmp.recycle()
        }
        doc.writeTo(out)
        doc.close()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch.code < 32 && ch != '\t' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun docx(pages: List<Page>, out: OutputStream) {
        val body = StringBuilder()
        pages.forEachIndexed { i, p ->
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            body.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Page ${i + 1}</w:t></w:r></w:p>")
            for (line in pageText(p).split("\n")) {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t></w:r></w:p>")
            }
        }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            body + "</w:body></w:document>"
        val types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val z = ZipOutputStream(out)
        fun put(n: String, c: String) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", types)
        put("_rels/.rels", rels)
        put("word/document.xml", doc)
        z.finish()
    }
}

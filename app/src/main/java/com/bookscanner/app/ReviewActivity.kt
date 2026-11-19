package com.bookscanner.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewActivity : AppCompatActivity() {
    private lateinit var summary: TextView
    private lateinit var preview: TextView
    private lateinit var nameEdit: EditText
    private var fmt = 0
    private var waiting = false
    private var reading = false
    private var lastUri: Uri? = null
    private var lastMime = "*/*"
    private val listener: () -> Unit = {
        runOnUiThread {
            refresh()
            if (waiting && Worker.pending() == 0) doSave()
        }
    }

    private val formats = listOf("PDF with text (best for screen readers)", "PDF with page images", "TXT (plain text)", "DOCX (Word)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        col.addView(uiLabel("Review and Save", 26f, true))
        summary = uiLabel("", 18f)
        col.addView(summary)
        col.addView(uiSpinner("Reading mode", listOf("Reader: Offline (ML Kit)", "Reader: Online (Gemini)"), if (Prefs.online(this)) 1 else 0) {
            Prefs.setOnline(this, it == 1)
        })
        col.addView(uiButton("Re-read All Pages With Selected Reader") {
            Store.resetTexts()
            Worker.note = ""
            Worker.kick(applicationContext)
            refresh()
            Speaker.say("Reading all pages again.")
        })
        col.addView(uiButton("Read Aloud / Stop") { toggleRead() })
        col.addView(uiLabel("Text preview (first part)", 16f, true))
        preview = uiLabel("", 16f)
        preview.setTextIsSelectable(true)
        col.addView(preview)
        col.addView(uiLabel("File name", 16f, true))
        nameEdit = EditText(this)
        nameEdit.hint = "File name (without extension)"
        nameEdit.contentDescription = "File name"
        nameEdit.setTextColor(Color.WHITE)
        nameEdit.setHintTextColor(Color.LTGRAY)
        nameEdit.textSize = 20f
        nameEdit.inputType = InputType.TYPE_CLASS_TEXT
        col.addView(nameEdit)
        col.addView(uiSpinner("File format", formats, 0) { fmt = it })
        col.addView(uiButton("Save") { doSave() })
        col.addView(uiButton("Open Saved File") {
            val u = lastUri
            if (u == null) Speaker.say("Nothing saved yet.") else openFile(u, lastMime)
        })
        col.addView(uiButton("Share Saved File") {
            val u = lastUri
            if (u == null) Speaker.say("Nothing saved yet.") else shareFile(u, lastMime)
        })
        col.addView(uiButton("Add More Pages") { startActivity(Intent(this, ScanActivity::class.java)); finish() })
        col.addView(uiButton("Back to Home") { finish() })
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        Worker.listeners.add(listener)
        Worker.kick(applicationContext)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Worker.listeners.remove(listener)
    }

    private fun refresh() {
        val pages = Store.snapshot()
        val pend = pages.count { it.text == null }
        var s = "Pages: ${pages.size}. Text ready: ${pages.size - pend}. Still reading: $pend."
        if (Worker.note.isNotEmpty()) s += "\n" + Worker.note
        summary.text = s
        val sb = StringBuilder()
        for ((i, p) in pages.withIndex()) {
            val t = p.text ?: continue
            sb.append("Page ${i + 1}: ").append(t).append("\n\n")
            if (sb.length > 3000) break
        }
        preview.text = if (sb.length > 3000) sb.substring(0, 3000) + "..." else sb.toString()
    }

    private fun toggleRead() {
        if (reading) { Speaker.stop(); reading = false; return }
        val pages = Store.snapshot()
        val sb = StringBuilder()
        pages.forEachIndexed { i, p ->
            val t = p.text?.trim().orEmpty()
            if (t.isNotEmpty()) sb.append("Page ${i + 1}.\n").append(t).append("\n\n")
        }
        if (sb.isEmpty()) { Speaker.say("No text is ready yet."); return }
        reading = true
        Speaker.readLong(sb.toString())
    }

    private fun doSave() {
        val pages = Store.snapshot()
        if (pages.isEmpty()) { Speaker.say("There are no pages to save."); return }
        if (fmt != 1 && Worker.pending() > 0) {
            waiting = true
            val m = "Still reading ${Worker.pending()} pages. Saving will start automatically when reading finishes."
            summary.text = m
            Speaker.say(m)
            return
        }
        waiting = false
        var base = nameEdit.text.toString().trim()
        if (base.isEmpty()) base = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        base = base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val ext: String
        val mime: String
        when (fmt) {
            0, 1 -> { ext = "pdf"; mime = "application/pdf" }
            2 -> { ext = "txt"; mime = "text/plain" }
            else -> { ext = "docx"; mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }
        }
        val f = fmt
        summary.text = "Saving ${pages.size} pages..."
        Speaker.say("Saving. Please wait.")
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    Docs.save(applicationContext, "$base.$ext", mime) { out ->
                        when (f) {
                            0 -> Exporter.textPdf(pages, out)
                            1 -> Exporter.imagePdf(pages, out)
                            2 -> Exporter.txt(pages, out)
                            else -> Exporter.docx(pages, out)
                        }
                    }
                }
                lastUri = uri
                lastMime = mime
                refresh()
                val m = "Saved as $base.$ext in the Documents, Book Scanner folder."
                summary.text = m + "\n" + summary.text
                Speaker.say(m)
            } catch (e: Throwable) {
                val m = "Save failed: ${e.message}"
                summary.text = m
                Speaker.say(m)
            }
        }
    }

    private fun openFile(u: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(u, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Open with"))
    }

    private fun shareFile(u: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, u)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }
}

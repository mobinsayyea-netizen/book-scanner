package com.bookscanner.app

import android.content.Intent
import android.graphics.Color
import android.widget.LinearLayout
import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class FilesActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        col.removeAllViews()
        col.addView(uiLabel("My Saved Files", 26f, true))
        val items = try { Docs.list(this) } catch (e: Exception) { emptyList() }
        if (items.isEmpty()) col.addView(uiLabel("No saved files yet. Files are saved in Documents, Book Scanner folder.", 18f))
        for (it in items) {
            val kb = (it.size / 1024).coerceAtLeast(1)
            col.addView(uiButton("${it.name}  ($kb KB)") { choose(it) })
        }
        col.addView(uiButton("Back") { finish() })
    }

    private fun choose(it: Docs.Item) {
        AlertDialog.Builder(this).setTitle(it.name).setItems(arrayOf("Open", "Share", "Delete")) { _, which ->
            when (which) {
                0 -> {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(it.uri, it.mime)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Open with"))
                }
                1 -> {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = it.mime
                    i.putExtra(Intent.EXTRA_STREAM, it.uri)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Share"))
                }
                else -> {
                    try { contentResolver.delete(it.uri, null, null) } catch (e: Exception) { Speaker.say("Could not delete this file.") }
                    load()
                }
            }
        }.show()
    }
}

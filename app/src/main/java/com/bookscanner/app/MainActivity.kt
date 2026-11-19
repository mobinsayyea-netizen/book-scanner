package com.bookscanner.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        col.addView(uiLabel("Book Scanner", 28f, true))
        status = uiLabel("", 18f)
        col.addView(status)
        col.addView(uiButton("Start Scan") { startActivity(Intent(this, ScanActivity::class.java)) })
        col.addView(uiButton("Review and Save") { startActivity(Intent(this, ReviewActivity::class.java)) })
        col.addView(uiButton("Select Photos or PDF") { picker.launch(arrayOf("image/*", "application/pdf")) })
        col.addView(uiButton("My Saved Files") { startActivity(Intent(this, FilesActivity::class.java)) })
        col.addView(uiButton("Clear All Pages") {
            AlertDialog.Builder(this).setTitle("Clear all pages?").setMessage("All pages in the current session will be deleted.")
                .setPositiveButton("Clear") { _, _ -> Store.clear(); refresh(); Speaker.say("All pages cleared") }
                .setNegativeButton("Cancel", null).show()
        })
        col.addView(uiButton("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val n = Store.size()
        val p = Worker.pending()
        status.text = "Pages in current session: $n" + if (p > 0) " ($p still being read)" else ""
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
        }
        if (uris.isEmpty() && (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE)) {
            val cd = i.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) {
            i.action = null
            importUris(uris)
        }
    }

    private fun importUris(uris: List<Uri>) {
        Speaker.say("Importing ${uris.size} file. Please wait.")
        status.text = "Importing..."
        lifecycleScope.launch {
            var added = 0
            try {
                added = withContext(Dispatchers.IO) {
                    Importer.importUris(applicationContext, uris) { m -> runOnUiThread { status.text = m } }
                }
            } catch (e: Exception) {
                status.text = "Import error: ${e.message}"
            }
            Worker.kick(applicationContext)
            refresh()
            if (added > 0) {
                Speaker.say("$added pages added. Opening review.")
                startActivity(Intent(this@MainActivity, ReviewActivity::class.java).putExtra("autoread", true))
            } else {
                Speaker.say("No pages could be imported.")
            }
        }
    }
}

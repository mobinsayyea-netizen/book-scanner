package com.bookscanner.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Page(val file: File, @Volatile var text: String? = null)

object Store {
    private var dir: File? = null
    private val pages = ArrayList<Page>()
    private var counter = 0

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "pages")
        d.mkdirs()
        dir = d
        val idx = File(d, "index.json")
        if (idx.exists()) {
            try {
                val arr = JSONArray(idx.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val f = File(d, o.getString("f"))
                    if (f.exists()) pages.add(Page(f, if (o.has("t")) o.getString("t") else null))
                }
            } catch (e: Exception) { }
        }
    }

    @Synchronized fun newFile(): File = File(dir!!, "p_${System.currentTimeMillis()}_${counter++}.jpg")
    @Synchronized fun snapshot(): List<Page> = ArrayList(pages)
    @Synchronized fun size(): Int = pages.size

    @Synchronized
    fun add(p: Page): Int { pages.add(p); persist(); return pages.size }

    @Synchronized
    fun setText(p: Page, t: String?) { p.text = t; persist() }

    @Synchronized
    fun removeLast(): Boolean {
        if (pages.isEmpty()) return false
        val p = pages.removeAt(pages.size - 1)
        p.file.delete()
        persist()
        return true
    }

    @Synchronized
    fun clear() {
        pages.forEach { it.file.delete() }
        pages.clear()
        persist()
    }

    @Synchronized
    fun resetTexts() { pages.forEach { it.text = null }; persist() }

    @Synchronized
    private fun persist() {
        val d = dir ?: return
        val arr = JSONArray()
        pages.forEach {
            val o = JSONObject()
            o.put("f", it.file.name)
            val t = it.text
            if (t != null) o.put("t", t)
            arr.put(o)
        }
        File(d, "index.json").writeText(arr.toString())
    }
}

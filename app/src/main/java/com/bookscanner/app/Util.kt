package com.bookscanner.app

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Speaker.init(this)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.uiButton(text: String, weighted: Boolean = false, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = text
    b.isAllCaps = false
    b.textSize = 19f
    b.setTextColor(Color.BLACK)
    b.typeface = Typeface.DEFAULT_BOLD
    val bg = GradientDrawable()
    bg.setColor(0xFFFFD600.toInt())
    bg.cornerRadius = dp(12).toFloat()
    b.background = bg
    b.minimumHeight = dp(64)
    val lp = if (weighted) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.setMargins(dp(8), dp(6), dp(8), dp(6))
    b.layoutParams = lp
    b.setOnClickListener { onClick() }
    return b
}

fun Context.uiLabel(text: String, size: Float = 18f, heading: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = size
    t.setTextColor(Color.WHITE)
    t.setPadding(dp(16), dp(8), dp(16), dp(8))
    if (heading) {
        t.typeface = Typeface.DEFAULT_BOLD
        ViewCompat.setAccessibilityHeading(t, true)
    }
    return t
}

fun Context.uiSpinner(desc: String, items: List<String>, selected: Int, onSel: (Int) -> Unit): Spinner {
    val s = Spinner(this)
    val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    s.adapter = a
    s.setSelection(selected)
    s.contentDescription = desc
    var cur = selected
    s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (pos != cur) { cur = pos; onSel(pos) }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.setMargins(dp(12), dp(6), dp(12), dp(6))
    s.layoutParams = lp
    s.minimumHeight = dp(56)
    return s
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun key(c: Context): String = sp(c).getString("gemini_key", "") ?: ""
    fun setKey(c: Context, v: String) = sp(c).edit().putString("gemini_key", v.trim()).apply()
    fun model(c: Context): String = sp(c).getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v.trim().ifEmpty { "gemini-2.5-flash" }).apply()
    fun online(c: Context): Boolean = sp(c).getBoolean("online", false)
    fun setOnline(c: Context, v: Boolean) = sp(c).edit().putBoolean("online", v).apply()
    fun rate(c: Context): Float = sp(c).getFloat("rate", 1.0f)
    fun setRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()
    fun voice(c: Context): Boolean = sp(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice", v).apply()
    fun auto(c: Context): Boolean = sp(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()
}

object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: String? = null
    private var hindi = false
    private var rate = 1f

    fun init(ctx: Context) {
        if (tts != null) return
        rate = Prefs.rate(ctx)
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setSpeechRate(rate)
                tts?.language = Locale.US
                queued?.let { speak(it, true, false); queued = null }
            }
        }
    }

    fun setRate(r: Float) { rate = r; tts?.setSpeechRate(r) }

    private fun speak(text: String, flush: Boolean, hi: Boolean) {
        val t = tts ?: return
        if (hi != hindi) {
            t.language = if (hi) Locale("hi", "IN") else Locale.US
            hindi = hi
        }
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    fun say(text: String) {
        if (!ready) { queued = text; return }
        speak(text, true, false)
    }

    fun readLong(text: String) {
        if (!ready) return
        tts?.stop()
        val dev = text.count { it.code in 0x0900..0x097F }
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        val hi = dev * 5 > letters
        chunk(text).forEach { speak(it, false, hi) }
    }

    fun stop() { tts?.stop() }

    private fun chunk(text: String, max: Int = 3500): List<String> {
        val out = ArrayList<String>()
        var s = 0
        while (s < text.length) {
            var e = minOf(s + max, text.length)
            if (e < text.length) {
                val nl = text.lastIndexOf('\n', e)
                val sp = text.lastIndexOf(' ', e)
                e = if (nl > s) nl else if (sp > s) sp else e
            }
            out.add(text.substring(s, e).trim())
            s = e
        }
        return out.filter { it.isNotEmpty() }
    }
}

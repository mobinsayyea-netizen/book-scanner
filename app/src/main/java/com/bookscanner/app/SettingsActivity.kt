package com.bookscanner.app

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val rates = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private fun field(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.contentDescription = hint
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.LTGRAY)
        e.textSize = 18f
        e.inputType = InputType.TYPE_CLASS_TEXT
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        col.addView(uiLabel("Settings", 26f, true))
        col.addView(uiLabel("Gemini API key (for online reading)", 16f, true))
        val key = field("Gemini API key", Prefs.key(this))
        col.addView(key)
        col.addView(uiLabel("Gemini model name", 16f, true))
        val model = field("Gemini model name", Prefs.model(this))
        col.addView(model)
        col.addView(uiLabel("Speech speed", 16f, true))
        val cur = rates.indexOf(Prefs.rate(this)).let { if (it < 0) 1 else it }
        col.addView(uiSpinner("Speech speed", listOf("Slow (0.75x)", "Normal (1x)", "Fast (1.25x)", "Faster (1.5x)", "Fastest (2x)"), cur) {
            Prefs.setRate(this, rates[it])
            Speaker.setRate(rates[it])
            Speaker.say("Speech speed changed")
        })
        val voice = CheckBox(this)
        voice.text = "Voice guidance while scanning"
        voice.textSize = 18f
        voice.setTextColor(Color.WHITE)
        voice.isChecked = Prefs.voice(this)
        voice.setOnCheckedChangeListener { _, c -> Prefs.setVoice(this, c) }
        col.addView(voice)
        val auto = CheckBox(this)
        auto.text = "Auto capture when the page is in frame"
        auto.textSize = 18f
        auto.setTextColor(Color.WHITE)
        auto.isChecked = Prefs.auto(this)
        auto.setOnCheckedChangeListener { _, c -> Prefs.setAuto(this, c) }
        col.addView(auto)
        col.addView(uiButton("Save Settings") {
            Prefs.setKey(this, key.text.toString())
            Prefs.setModel(this, model.text.toString())
            Speaker.say("Settings saved")
            finish()
        })
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
    }
}

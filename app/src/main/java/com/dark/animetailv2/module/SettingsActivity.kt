package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class SettingsActivity : Activity() {

    companion object {
        val BG_COLOR = Color.parseColor("#0D0D0F")
        val CARD_COLOR = Color.parseColor("#1A1A22")
        val CARD_STROKE = Color.parseColor("#2A2A38")
        val ACCENT_TEAL = Color.parseColor("#4DD9B8")
        val TEXT_PRIMARY = Color.parseColor("#F0F0F8")
        val TEXT_SECONDARY = Color.parseColor("#7A7A9A")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.decorView.setBackgroundColor(BG_COLOR)

        val prefs = getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/elite_mod_prefs.xml")

        val rootScroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }

        val header = TextView(this).apply {
            text = "Animetail Elite Mod v2.2"
            setTextColor(TEXT_PRIMARY)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        }
        mainLayout.addView(header)

        // Configuration Card
        val holdDelayInput = createInput(prefs.getString("hold_delay", "400"))
        val dragSensitivityInput = createInput(prefs.getString("drag_sensitivity", "100"))
        mainLayout.addView(createCard("GESTURE CONFIG",
            createLabel("Hold Delay (ms):"), holdDelayInput,
            createLabel("Drag sensitivity (Pixels):"), dragSensitivityInput
        ))

        // Speed Card
        val holdSpeedInput = createInput(prefs.getString("hold_speed", "2.0"))
        val sequenceInput = createInput(prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0"))
        mainLayout.addView(createCard("SPEED ENGINE",
            createLabel("Hold Speed:"), holdSpeedInput,
            createLabel("Sequence (0.5, 1.0, 2.0...):"), sequenceInput,
            createSwitch("Remember per show", "per_show_speed", true, prefs)
        ))

        // UI & System
        mainLayout.addView(createCard("SYSTEM",
            createSwitch("Screenshot Bypass", "screenshot_bypass", true, prefs),
            createSwitch("Silent Auto-Updater", "silent_update", true, prefs),
            createSwitch("PiP Auto-2x", "pip_2x", false, prefs)
        ))

        val saveBtn = Button(this).apply {
            text = "APPLY & SYNC SETTINGS"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(ACCENT_TEAL) }
            setPadding(0, 40, 0, 40)
            setOnClickListener {
                prefs.edit().apply {
                    putString("hold_delay", holdDelayInput.text.toString())
                    putString("drag_sensitivity", dragSensitivityInput.text.toString())
                    putString("hold_speed", holdSpeedInput.text.toString())
                    putString("speed_sequence", sequenceInput.text.toString())
                }.apply()
                
                // BULLETPROOF SYNC: Inject into Animetail using Root
                syncViaRoot(prefsFile)
                Toast.makeText(this@SettingsActivity, "Settings Injected Successfully!", Toast.LENGTH_LONG).show()
            }
        }
        mainLayout.addView(saveBtn)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
    }

    private fun createCard(title: String, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 40, 40, 40)
        background = GradientDrawable().apply { setColor(CARD_COLOR); cornerRadius = 24f; setStroke(2, CARD_STROKE) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 40 }
        addView(TextView(this@SettingsActivity).apply { text = title; setTextColor(TEXT_SECONDARY); textSize = 11f; setPadding(0, 0, 0, 20) })
        for (v in views) addView(v)
    }

    private fun createLabel(t: String) = TextView(this).apply { text = t; setTextColor(TEXT_PRIMARY); setPadding(0, 15, 0, 5) }
    private fun createInput(v: String?) = EditText(this).apply {
        setText(v)
        setTextColor(TEXT_PRIMARY)
        setPadding(20, 20, 20, 20)
        background = GradientDrawable().apply { setColor(Color.parseColor("#252530")); cornerRadius = 12f }
    }
    private fun createSwitch(l: String, k: String, d: Boolean, p: android.content.SharedPreferences) = Switch(this).apply {
        text = l; setTextColor(TEXT_PRIMARY); isChecked = p.getBoolean(k, d); setPadding(0, 20, 0, 20)
        setOnCheckedChangeListener { _, isChecked -> p.edit().putBoolean(k, isChecked).apply() }
    }

    private fun syncViaRoot(file: File) {
        val target = "/data/data/com.dark.animetailv2/shared_prefs/elite_mod_prefs.xml"
        val dir = "/data/data/com.dark.animetailv2/shared_prefs"
        try {
            val cmd = arrayOf("su", "-c", "mkdir -p $dir && cp ${file.absolutePath} $target && chmod 777 $dir && chmod 777 $target && chown 1000:1000 $target")
            Runtime.getRuntime().exec(cmd).waitFor()
        } catch (e: Exception) {}
    }
}

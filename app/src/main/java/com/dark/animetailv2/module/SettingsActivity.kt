package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
            text = "Animetail Elite Mod v2.4"
            setTextColor(TEXT_PRIMARY)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        mainLayout.addView(header)
        mainLayout.addView(TextView(this).apply { 
            text = "Ultimate Polish & Precision Sync"
            setTextColor(ACCENT_TEAL)
            textSize = 12f
            setPadding(0, 0, 0, 40)
        })

        // GESTURE Card
        val holdDelayInput = createInput(prefs.getString("hold_delay", "400"))
        val dragSensitivityInput = createInput(prefs.getString("drag_sensitivity", "100"))
        mainLayout.addView(createCard("GESTURE CONFIG",
            createLabel("Hold Delay (ms) [200 - 2000]:"), holdDelayInput,
            createLabel("Drag Sensitivity (Pixels) [50 - 500]:"), dragSensitivityInput,
            createSwitch("Horizontal Drag", "horizontal_drag", true, prefs)
        ))

        // UI Card
        val pillScaleInput = createInput(prefs.getString("pill_scale", "100"))
        mainLayout.addView(createCard("UI CUSTOMIZATION",
            createLabel("Pill Scale (%) [50 - 250]:"), pillScaleInput,
            createSwitch("Show Expansion Animation", "show_expansion", true, prefs)
        ))

        // SPEED Card
        val holdSpeedInput = createInput(prefs.getString("hold_speed", "2.0"))
        val sequenceInput = createInput(prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0"))
        mainLayout.addView(createCard("SPEED ENGINE",
            createLabel("Default Hold Speed:"), holdSpeedInput,
            createLabel("Sequence (Comma separated):"), sequenceInput,
            createSwitch("Remember per show", "per_show_speed", true, prefs)
        ))

        // SYSTEM
        mainLayout.addView(createCard("SYSTEM",
            createSwitch("Screenshot Bypass", "screenshot_bypass", true, prefs),
            createSwitch("Silent Auto-Updater", "silent_update", true, prefs)
        ))

        val saveBtn = Button(this).apply {
            text = "APPLY & FORCE REPAIR"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(ACCENT_TEAL) }
            setPadding(0, 40, 0, 40)
            setOnClickListener {
                val delay = (holdDelayInput.text.toString().toIntOrNull() ?: 400).coerceIn(200, 2000)
                val sens = (dragSensitivityInput.text.toString().toIntOrNull() ?: 100).coerceIn(50, 500)
                val scale = (pillScaleInput.text.toString().toIntOrNull() ?: 100).coerceIn(50, 250)

                prefs.edit().apply {
                    putString("hold_delay", delay.toString())
                    putString("drag_sensitivity", sens.toString())
                    putString("pill_scale", scale.toString())
                    putString("hold_speed", holdSpeedInput.text.toString())
                    putString("speed_sequence", sequenceInput.text.toString())
                }.apply()
                
                syncViaRoot(prefsFile)
                Toast.makeText(this@SettingsActivity, "Settings Synced & Permissions Fixed!", Toast.LENGTH_LONG).show()
                
                // Force Stop Animetail automatically if root allows
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.dark.animetailv2")) } catch(e: Exception) {}
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
        setPadding(25, 25, 25, 25)
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
            // CRITICAL: Mirror precisely for Animetail process
            val cmd = arrayOf("su", "-c", 
                "mkdir -p $dir && " +
                "cp ${file.absolutePath} $target && " +
                "chmod 777 $dir && " +
                "chmod 777 $target && " +
                "chown 1000:1000 $target"
            )
            Runtime.getRuntime().exec(cmd).waitFor()
        } catch (e: Exception) {}
    }
}

package com.dark.animetailv2.module

import android.app.Activity
import android.app.AlertDialog
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
        val ACCENT_VIOLET = Color.parseColor("#7C6FE0")
        val ACCENT_TEAL = Color.parseColor("#4DD9B8")
        val TEXT_PRIMARY = Color.parseColor("#F0F0F8")
        val TEXT_SECONDARY = Color.parseColor("#7A7A9A")
        val DANGER_RED = Color.parseColor("#E05C6F")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.decorView.setBackgroundColor(BG_COLOR)

        val prefs = getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/elite_mod_prefs.xml")
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")

        val rootScroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        // Header
        mainLayout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 60)
            addView(TextView(this@SettingsActivity).apply {
                text = "Animetail Elite Mod"
                setTextColor(TEXT_PRIMARY)
                textSize = 22f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "v2.6.1 · Definitive Edition"
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
            })
            addView(View(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4).apply { topMargin = 20 }
                setBackgroundColor(ACCENT_VIOLET)
            })
        })

        // GESTURE Card
        val holdDelayInput = createInput(prefs.getString("hold_delay", "400"))
        val dragSensitivityInput = createInput(prefs.getString("drag_sensitivity", "100"))
        val holdDelayHelper = createHelperText()
        val dragSensitivityHelper = createHelperText()
        
        mainLayout.addView(createCard("GESTURE CONFIG",
            createLabel("Hold Delay (ms) [Min: 200, Max: 2000]:"), holdDelayInput, holdDelayHelper,
            createLabel("Drag Sensitivity (Pixels) [Min: 50, Max: 500]:"), dragSensitivityInput, dragSensitivityHelper,
            createSwitch("Horizontal Drag", "horizontal_drag", true, prefs)
        ))

        // UI Card
        val pillScaleInput = createInput(prefs.getString("pill_scale", "100"))
        val pillMarginInput = createInput(prefs.getString("pill_margin", "15"))
        val pillScaleHelper = createHelperText()
        val pillMarginHelper = createHelperText()
        
        mainLayout.addView(createCard("UI CUSTOMIZATION",
            createLabel("Pill Scale (%) [Min: 50, Max: 250]:"), pillScaleInput, pillScaleHelper,
            createLabel("Pill Top Margin [Min: 5, Max: 100]:"), pillMarginInput, pillMarginHelper,
            createSwitch("Show Expansion Animation", "show_expansion", true, prefs)
        ))

        // SPEED Card
        val holdSpeedInput = createInput(prefs.getString("hold_speed", "2.0"))
        val holdSpeedHelper = createHelperText()
        val sequenceInput = createInput(prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0"))
        val buttonCycleInput = createInput(prefs.getString("button_cycle_sequence", "0.25, 0.5, 1.0, 1.25, 1.5, 1.75, 2.0"))
        
        mainLayout.addView(createCard("SPEED ENGINE",
            createLabel("Default Hold Speed:"), holdSpeedInput, holdSpeedHelper,
            createLabel("Hold Drag Sequence:"), sequenceInput,
            createLabel("Button Tap Sequence:"), buttonCycleInput,
            createSwitch("Remember per show", "per_show_speed", true, prefs)
        ))

        // SYSTEM
        mainLayout.addView(createCard("SYSTEM",
            createSwitch("Global Screenshot Bypass", "screenshot_bypass", true, prefs),
            createSwitch("Silent Auto-Update (Root)", "silent_update", true, prefs),
            createSwitch("📳 Haptic Feedback", "haptic_feedback", false, prefs)
        ))

        // Save Button
        val saveBtn = Button(this).apply {
            text = "SAVE & SYNC (ROOT)"
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(ACCENT_TEAL)
            }
            setPadding(0, 40, 0, 40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
            setOnClickListener {
                val delay = validateAndCoerce(holdDelayInput, 200.0, 2000.0, 400.0, holdDelayHelper).toInt()
                val sens = validateAndCoerce(dragSensitivityInput, 50.0, 500.0, 100.0, dragSensitivityHelper).toInt()
                val scale = validateAndCoerce(pillScaleInput, 50.0, 250.0, 100.0, pillScaleHelper).toInt()
                val margin = validateAndCoerce(pillMarginInput, 5.0, 100.0, 15.0, pillMarginHelper).toInt()
                val hSpeed = validateAndCoerce(holdSpeedInput, 0.1, 10.0, 2.0, holdSpeedHelper).toString()

                val editor = prefs.edit()
                editor.putString("hold_delay", delay.toString())
                editor.putString("drag_sensitivity", sens.toString())
                editor.putString("pill_scale", scale.toString())
                editor.putString("pill_margin", margin.toString())
                editor.putString("hold_speed", hSpeed)
                editor.putString("speed_sequence", sequenceInput.text.toString())
                editor.putString("button_cycle_sequence", buttonCycleInput.text.toString())
                editor.apply()
                
                syncViaRoot(prefsDir, prefsFile)
                Toast.makeText(this@SettingsActivity, "Settings Synced!", Toast.LENGTH_LONG).show()
                
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.dark.animetailv2")) } catch(e: Exception) {}
            }
        }
        mainLayout.addView(saveBtn)

        val footer = TextView(this).apply {
            tag = "footer"
            setTextColor(TEXT_SECONDARY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 60)
        }
        mainLayout.addView(footer)
        updateFooter(mainLayout)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
        syncViaRoot(prefsDir, prefsFile)
    }

    private fun createCard(title: String, vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(CARD_COLOR)
                cornerRadius = 24f
                setStroke(2, CARD_STROKE)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 40 }
            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(TEXT_SECONDARY)
                textSize = 11f
                letterSpacing = 0.12f
                setPadding(0, 0, 0, 30)
            })
            for (v in views) addView(v)
        }
    }

    private fun createSwitch(label: String, key: String, def: Boolean, prefs: android.content.SharedPreferences) = Switch(this).apply {
        text = label
        setTextColor(TEXT_PRIMARY)
        isChecked = prefs.getBoolean(key, def)
        setPadding(0, 20, 0, 20)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(ACCENT_TEAL, TEXT_SECONDARY))
        trackTintList = ColorStateList(states, intArrayOf(Color.argb(100, 77, 217, 184), CARD_STROKE))
        setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean(key, isChecked).apply() }
    }

    private fun createLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(TEXT_PRIMARY)
        textSize = 14f
        setPadding(0, 20, 0, 10)
    }

    private fun createHelperText() = TextView(this).apply {
        setTextColor(DANGER_RED)
        textSize = 12f
        visibility = View.GONE
        setPadding(10, 5, 0, 0)
    }

    private fun createInput(v: String?) = EditText(this).apply {
        setText(v)
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        textSize = 14f
        setPadding(30, 30, 30, 30)
        inputType = InputType.TYPE_CLASS_TEXT
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#252530"))
            cornerRadius = 16f
            setStroke(2, CARD_STROKE)
        }
    }

    private fun validateAndCoerce(input: EditText, min: Double, max: Double, fallback: Double, helper: TextView): Double {
        val v = input.text.toString().toDoubleOrNull()
        return when {
            v == null -> { helper.text = "Invalid - using $fallback"; helper.visibility = View.VISIBLE; fallback }
            v < min -> { helper.text = "Too low - clamped to $min"; helper.visibility = View.VISIBLE; input.setText(min.toString()); min }
            v > max -> { helper.text = "Too high - clamped to $max"; helper.visibility = View.VISIBLE; input.setText(max.toString()); max }
            else -> { helper.visibility = View.GONE; v }
        }
    }

    private fun updateFooter(layout: LinearLayout) {
        val footer = layout.findViewWithTag<TextView>("footer") ?: return
        val lastSync = getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE).getString("last_sync_time", "0")?.toLong() ?: 0L
        footer.text = if (lastSync == 0L) "Never synced" else "Last synced: ${getRelativeTime(lastSync)}"
    }

    private fun getRelativeTime(time: Long): String {
        val diff = System.currentTimeMillis() - time
        return when {
            diff < 60000 -> "${diff/1000}s ago"
            diff < 3600000 -> "${diff/60000}m ago"
            else -> "${diff/3600000}h ago"
        }
    }

    private fun syncViaRoot(dir: File, file: File) {
        val targetDir = "/data/data/com.dark.animetailv2/shared_prefs"
        val targetFile = "$targetDir/elite_mod_prefs.xml"
        try {
            val cmd = arrayOf("su", "-c", "mkdir -p $targetDir && cp ${file.absolutePath} $targetFile && chmod 777 $targetDir && chmod 777 $targetFile && chown 1000:1000 $targetFile")
            Runtime.getRuntime().exec(cmd).waitFor()
        } catch (e: Exception) {}
    }
}

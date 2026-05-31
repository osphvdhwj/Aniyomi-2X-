package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "v2.1 · LSPosed Optimized"
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
            })
            addView(View(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4).apply { topMargin = 20 }
                setBackgroundColor(ACCENT_VIOLET)
            })
        })

        // GESTURE ENGINE
        val holdDelayInput = EditText(this)
        val holdDelayHelper = createHelperText()
        mainLayout.addView(createCard("GESTURE ENGINE",
            createSwitch("Horizontal Drag (Off = Vertical)", "horizontal_drag", true, prefs),
            createLabel("Hold Activation Delay (ms):"), holdDelayInput, holdDelayHelper
        ))

        // SPEED CONTROL
        val holdSpeedInput = EditText(this)
        val holdSpeedHelper = createHelperText()
        val sequenceInput = EditText(this)
        val dragSensitivityInput = EditText(this)
        val dragSensitivityHelper = createHelperText()
        mainLayout.addView(createCard("SPEED CONTROL",
            createLabel("Default Hold Speed:"), holdSpeedInput, holdSpeedHelper,
            createLabel("Speed Sequence:"), sequenceInput,
            createLabel("Drag Sensitivity (Pixels per shift):"), dragSensitivityInput, dragSensitivityHelper,
            createSwitch("↺ Remember Speed Per Show", "per_show_speed", true, prefs)
        ))

        // SYSTEM & PIP
        mainLayout.addView(createCard("SYSTEM & PIP",
            createSwitch("Global Screenshot Bypass", "screenshot_bypass", true, prefs),
            createSwitch("Silent Auto-Update (Root)", "silent_update", true, prefs),
            createSwitch("📳 Haptic Feedback", "haptic_feedback", false, prefs),
            createSwitch("PiP Auto-2x", "pip_2x", false, prefs)
        ))

        // Pre-fill
        holdDelayInput.setText(prefs.getString("hold_delay", "400"))
        holdSpeedInput.setText(prefs.getString("hold_speed", "2.0"))
        sequenceInput.setText(prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0"))
        dragSensitivityInput.setText(prefs.getString("drag_sensitivity", "100"))

        styleInput(holdDelayInput, "400")
        styleInput(holdSpeedInput, "2.0")
        styleInput(sequenceInput, "0.1, 0.5...")
        styleInput(dragSensitivityInput, "100")

        // Save Button
        val saveBtn = Button(this).apply {
            text = "SYNC TO ANIMETAIL"
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val gd = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(ACCENT_TEAL)
            }
            background = gd
            setPadding(0, 40, 0, 40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
            setOnClickListener {
                val editor = prefs.edit()
                editor.putString("hold_delay", validateAndCoerce(holdDelayInput, 100.0, 5000.0, 400.0, holdDelayHelper).toInt().toString())
                editor.putString("hold_speed", validateAndCoerce(holdSpeedInput, 0.1, 10.0, 2.0, holdSpeedHelper).toString())
                editor.putString("drag_sensitivity", validateAndCoerce(dragSensitivityInput, 20.0, 500.0, 100.0, dragSensitivityHelper).toInt().toString())
                editor.putString("speed_sequence", sequenceInput.text.toString())
                editor.putString("last_sync_time", System.currentTimeMillis().toString())
                editor.apply()
                forceSync(prefsDir, prefsFile)
                Toast.makeText(this@SettingsActivity, "Synced successfully!", Toast.LENGTH_LONG).show()
                updateFooter(mainLayout)
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
        forceSync(prefsDir, prefsFile)
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

    private fun styleInput(et: EditText, hintStr: String) {
        et.hint = hintStr
        et.setTextColor(TEXT_PRIMARY)
        et.setHintTextColor(TEXT_SECONDARY)
        et.textSize = 14f
        et.setPadding(30, 30, 30, 30)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.background = GradientDrawable().apply {
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

    private fun forceSync(dir: File, file: File) {
        val targetDir = "/data/data/com.dark.animetailv2/shared_prefs"
        val targetFile = "$targetDir/elite_mod_prefs.xml"
        try {
            val cmd = arrayOf("su", "-c", "mkdir -p $targetDir && cp ${file.absolutePath} $targetFile && chmod 777 $targetDir && chmod 777 $targetFile && chown 1000:1000 $targetFile")
            Runtime.getRuntime().exec(cmd).waitFor()
        } catch (e: Exception) {}
    }
}

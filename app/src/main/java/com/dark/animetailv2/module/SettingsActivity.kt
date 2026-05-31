package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import java.io.File

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        
        val prefs = getSharedPreferences("mod_prefs", Context.MODE_PRIVATE)
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/mod_prefs.xml")
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
        
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60) 
        }

        val title = TextView(this).apply {
            text = "Animetail Elite Mod v1.2"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.BLUE)
            setPadding(0, 0, 0, 40)
        }
        layout.addView(title)
        
        // Toggles
        layout.addView(createSwitch("Horizontal Drag (Off = Vertical)", "horizontal_drag", true, prefs, prefsDir, prefsFile))
        layout.addView(createSwitch("Silent Auto-Update (Root)", "silent_update", true, prefs, prefsDir, prefsFile))
        
        // Inputs
        layout.addView(createHeader("Default Hold Speed:"))
        val holdSpeedInput = createInput("e.g. 2.0", prefs.getString("hold_speed", "2.0"))
        layout.addView(holdSpeedInput)

        layout.addView(createHeader("Speed Sequence (Comma separated):"))
        val sequenceInput = createInput("e.g. 0.5, 1.0, 2.0, 3.0", prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0"))
        layout.addView(sequenceInput)

        layout.addView(createHeader("Hold Activation Delay (ms):"))
        val holdDelayInput = createInput("Min 200, Max 2000", prefs.getString("hold_delay", "400"))
        layout.addView(holdDelayInput)

        layout.addView(createHeader("Drag Sensitivity (Pixels per shift):"))
        val dragSensitivityInput = createInput("Min 50, Max 500", prefs.getString("drag_sensitivity", "100"))
        layout.addView(dragSensitivityInput)

        val saveButton = Button(this).apply {
            text = "SAVE & SYNC SETTINGS"
            setPadding(0, 30, 0, 30)
            setOnClickListener { 
                // Validate and Save
                val delay = holdDelayInput.text.toString().toIntOrNull() ?: 400
                val cappedDelay = Math.max(200, Math.min(2000, delay))
                
                val sens = dragSensitivityInput.text.toString().toIntOrNull() ?: 100
                val cappedSens = Math.max(50, Math.min(500, sens))

                prefs.edit()
                    .putString("hold_speed", holdSpeedInput.text.toString())
                    .putString("speed_sequence", sequenceInput.text.toString())
                    .putString("hold_delay", cappedDelay.toString())
                    .putString("drag_sensitivity", cappedSens.toString())
                    .apply() 
                
                fixPermissions(prefsDir, prefsFile)
                Toast.makeText(this@SettingsActivity, "Settings Applied! Force Stop Animetail to reload.", Toast.LENGTH_LONG).show()
            }
        }
        layout.addView(View(this).apply { minimumHeight = 50 })
        layout.addView(saveButton)
        
        scrollView.addView(layout)
        setContentView(scrollView)
        fixPermissions(prefsDir, prefsFile)
    }

    private fun createHeader(text: String) = TextView(this).apply {
        this.text = "\n$text"
        setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun createInput(hintStr: String, currentVal: String?) = EditText(this).apply {
        hint = hintStr
        setText(currentVal)
    }

    private fun createSwitch(label: String, key: String, default: Boolean, prefs: android.content.SharedPreferences, dir: File, file: File) = Switch(this).apply {
        text = label
        isChecked = prefs.getBoolean(key, default)
        setPadding(0, 15, 0, 15)
        setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean(key, isChecked).apply() 
            fixPermissions(dir, file)
        }
    }

    private fun fixPermissions(dir: File, file: File) {
        try {
            // Force world readability via root shell (Needed for LSPosed XSharedPreferences)
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 777 ${dir.absolutePath} && chmod 666 ${file.absolutePath}"))
        } catch (e: Exception) {}
    }
}

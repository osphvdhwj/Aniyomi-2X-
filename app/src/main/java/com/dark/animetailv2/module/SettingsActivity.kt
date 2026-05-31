package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Remove title bar to prevent overlap
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        
        val prefs = getSharedPreferences("mod_prefs", Context.MODE_PRIVATE)
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/mod_prefs.xml")
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
        
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60) 
        }

        val title = TextView(this).apply {
            text = "Animetail Elite Mod Settings"
            textSize = 24f
            setTextColor(android.graphics.Color.BLACK)
            setPadding(0, 0, 0, 40)
        }
        layout.addView(title)
        
        val dragToggle = Switch(this).apply {
            text = "Horizontal Drag Speed (Off = Vertical)"
            isChecked = prefs.getBoolean("horizontal_drag", true)
            setPadding(0, 20, 0, 20)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("horizontal_drag", isChecked).apply() 
                fixPermissions(prefsDir, prefsFile)
            }
        }

        val silentUpdateToggle = Switch(this).apply {
            text = "Silent Auto-Update (Root)"
            isChecked = prefs.getBoolean("silent_update", true)
            setPadding(0, 20, 0, 20)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("silent_update", isChecked).apply() 
                fixPermissions(prefsDir, prefsFile)
            }
        }

        val pipSpeedToggle = Switch(this).apply {
            text = "Auto-2x in PiP Mode"
            isChecked = prefs.getBoolean("pip_2x", false)
            setPadding(0, 20, 0, 20)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("pip_2x", isChecked).apply() 
                fixPermissions(prefsDir, prefsFile)
            }
        }

        layout.addView(dragToggle)
        layout.addView(silentUpdateToggle)
        layout.addView(pipSpeedToggle)

        layout.addView(TextView(this).apply { text = "\nDefault Hold Speed:" })
        val holdSpeedInput = EditText(this).apply {
            hint = "e.g. 2.0"
            setText(prefs.getString("hold_speed", "2.0"))
        }
        layout.addView(holdSpeedInput)

        layout.addView(TextView(this).apply { text = "\nSpeed Sequence (Comma separated):" })
        val sequenceInput = EditText(this).apply {
            hint = "e.g. 0.5, 1.0, 1.5, 2.0, 3.0"
            setText(prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0"))
        }
        layout.addView(sequenceInput)

        val saveButton = Button(this).apply {
            text = "Apply & Save Settings"
            setPadding(0, 40, 0, 40)
            setOnClickListener { 
                prefs.edit()
                    .putString("hold_speed", holdSpeedInput.text.toString())
                    .putString("speed_sequence", sequenceInput.text.toString())
                    .apply() 
                fixPermissions(prefsDir, prefsFile)
                Toast.makeText(this@SettingsActivity, "Settings Saved! Reboot or Force Stop Animetail.", Toast.LENGTH_LONG).show()
            }
        }
        layout.addView(View(this).apply { minimumHeight = 40 })
        layout.addView(saveButton)
        
        scrollView.addView(layout)
        setContentView(scrollView)
        fixPermissions(prefsDir, prefsFile)
    }

    private fun fixPermissions(dir: File, file: File) {
        try {
            dir.setExecutable(true, false)
            dir.setReadable(true, false)
            file.setReadable(true, false)
            // Hard fix via shell
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 777 ${dir.absolutePath} && chmod 666 ${file.absolutePath}"))
        } catch (e: Exception) {}
    }
}

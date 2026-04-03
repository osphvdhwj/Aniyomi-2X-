package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.EditText
import android.widget.TextView
import java.io.File

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Use MODE_PRIVATE to avoid the SecurityException crash
        val prefs = getSharedPreferences("mod_prefs", Context.MODE_PRIVATE)
        
        // 2. Make the file readable for LSPosed manually
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/mod_prefs.xml")
        prefsFile.setReadable(true, false) 
        
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50) }
        
        val dragToggle = Switch(this).apply {
            text = "Horizontal Drag Speed (Off = Vertical)"
            isChecked = prefs.getBoolean("horizontal_drag", true)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("horizontal_drag", isChecked).apply() 
                prefsFile.setReadable(true, false) // Keep readable after editing
            }
        }

        val silentUpdateToggle = Switch(this).apply {
            text = "Silent Auto-Update (Root)"
            isChecked = prefs.getBoolean("silent_update", true)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("silent_update", isChecked).apply() 
                prefsFile.setReadable(true, false)
            }
        }

        val sequenceInput = EditText(this).apply {
            hint = "Speed Sequence (e.g. 0.5, 1.0, 2.0, 4.0)"
            setText(prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0"))
        }

        val saveButton = android.widget.Button(this).apply {
            text = "Save Sequence"
            setOnClickListener { 
                prefs.edit().putString("speed_sequence", sequenceInput.text.toString()).apply() 
                prefsFile.setReadable(true, false)
            }
        }

        layout.addView(dragToggle)
        layout.addView(silentUpdateToggle)
        layout.addView(TextView(this).apply { text = "\nSpeed Sequence Array:" })
        layout.addView(sequenceInput)
        layout.addView(saveButton)
        setContentView(layout)
    }
}

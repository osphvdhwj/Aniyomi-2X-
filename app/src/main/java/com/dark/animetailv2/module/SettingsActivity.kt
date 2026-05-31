package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.EditText
import android.widget.TextView
import android.widget.Button
import java.io.File

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("mod_prefs", Context.MODE_PRIVATE)
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/mod_prefs.xml")
        
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50) 
        }
        
        val dragToggle = Switch(this).apply {
            text = "Horizontal Drag Speed (Off = Vertical)"
            isChecked = prefs.getBoolean("horizontal_drag", true)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("horizontal_drag", isChecked).apply() 
                prefsFile.setReadable(true, false)
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

        val holdSpeedInput = EditText(this).apply {
            hint = "Hold Speed (e.g. 2.0)"
            setText(prefs.getString("hold_speed", "2.0"))
        }

        val sequenceInput = EditText(this).apply {
            hint = "Speed Sequence (e.g. 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0)"
            setText(prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0"))
        }

        val saveButton = Button(this).apply {
            text = "Save All Settings"
            setOnClickListener { 
                prefs.edit()
                    .putString("hold_speed", holdSpeedInput.text.toString())
                    .putString("speed_sequence", sequenceInput.text.toString())
                    .apply() 
                prefsFile.setReadable(true, false)
                android.widget.Toast.makeText(this@SettingsActivity, "Settings Saved", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(dragToggle)
        layout.addView(silentUpdateToggle)
        layout.addView(TextView(this).apply { text = "\nDefault Hold Speed:" })
        layout.addView(holdSpeedInput)
        layout.addView(TextView(this).apply { text = "\nSpeed Sequence Array (for drag):" })
        layout.addView(sequenceInput)
        layout.addView(saveButton)
        
        setContentView(layout)
        prefsFile.setReadable(true, false)
    }
}

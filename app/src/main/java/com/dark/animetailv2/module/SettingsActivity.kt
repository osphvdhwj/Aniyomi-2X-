package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.EditText
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("mod_prefs", Context.MODE_WORLD_READABLE)
        
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50) }
        
        val dragToggle = Switch(this).apply {
            text = "Horizontal Drag Speed (Off = Vertical)"
            isChecked = prefs.getBoolean("horizontal_drag", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("horizontal_drag", isChecked).apply() }
        }

        val silentUpdateToggle = Switch(this).apply {
            text = "Silent Auto-Update (Root)"
            isChecked = prefs.getBoolean("silent_update", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("silent_update", isChecked).apply() }
        }

        val sequenceInput = EditText(this).apply {
            hint = "Speed Sequence (e.g. 0.5, 1.0, 2.0, 4.0)"
            setText(prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0"))
        }

        val saveButton = android.widget.Button(this).apply {
            text = "Save Sequence"
            setOnClickListener { prefs.edit().putString("speed_sequence", sequenceInput.text.toString()).apply() }
        }

        layout.addView(dragToggle)
        layout.addView(silentUpdateToggle)
        layout.addView(TextView(this).apply { text = "\nSpeed Sequence Array:" })
        layout.addView(sequenceInput)
        layout.addView(saveButton)
        setContentView(layout)
    }
}

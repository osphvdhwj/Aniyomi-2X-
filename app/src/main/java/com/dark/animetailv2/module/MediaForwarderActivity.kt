package com.dark.animetailv2.module

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class MediaForwarderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingUri: Uri? = intent?.data
        if (incomingUri == null) { finish(); return }

        // Store the URI in a static field so the hook in ModuleMain can read it
        PendingMediaIntent.uri = incomingUri
        PendingMediaIntent.mimeType = intent?.type ?: ""
        PendingMediaIntent.isFromExternal = true

        // Launch Animetail
        val forwardIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                "com.dark.animetailv2",
                "eu.kanade.tachiyomi.ui.player.PlayerActivity"
            )
            data = incomingUri
            type = intent?.type
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(forwardIntent)
        } catch (e: Exception) {
            // PlayerActivity not available — launch the app's main activity instead
            val mainIntent = packageManager.getLaunchIntentForPackage("com.dark.animetailv2")
            mainIntent?.let { startActivity(it) }
        }
        finish()
    }
}

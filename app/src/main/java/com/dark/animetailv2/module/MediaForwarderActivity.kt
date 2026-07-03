package com.dark.animetailv2.module

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class MediaForwarderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingUri: Uri? = intent?.data
        val incomingType: String? = intent?.type

        if (incomingUri == null) { finish(); return }

        // Grant ourselves read permission if it's a content URI
        try {
            contentResolver.takePersistableUriPermission(
                incomingUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        PendingMediaIntent.uri = incomingUri
        PendingMediaIntent.mimeType = incomingType ?: ""
        PendingMediaIntent.isFromExternal = true

        // Try PlayerActivity first, with full URI grant flags
        val forwardIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(incomingUri, incomingType ?: "video/*")
            setPackage("com.dark.animetailv2")           // constrain to Animetail
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            // Check if Animetail can handle ACTION_VIEW for this URI/type
            val resolveList = packageManager.queryIntentActivities(forwardIntent, 0)
            if (resolveList.isNotEmpty()) {
                startActivity(forwardIntent)
            } else {
                // Fallback: launch main activity; the hook will pick up PendingMediaIntent
                val mainIntent = packageManager.getLaunchIntentForPackage("com.dark.animetailv2")
                    ?: run { finish(); return }
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                mainIntent.putExtra("module_forwarded_uri", incomingUri.toString())
                startActivity(mainIntent)
            }
        } catch (e: Exception) {
            // Last resort
            val mainIntent = packageManager.getLaunchIntentForPackage("com.dark.animetailv2")
            mainIntent?.let { 
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                it.putExtra("module_forwarded_uri", incomingUri.toString())
                startActivity(it) 
            }
        }
        finish()
    }
}

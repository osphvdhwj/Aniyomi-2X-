package com.dark.animetailv2.module

import android.net.Uri

object PendingMediaIntent {
    @Volatile var uri: Uri? = null
    @Volatile var mimeType: String = ""
    @Volatile var isFromExternal: Boolean = false

    fun consume(): Uri? {
        val u = uri
        uri = null
        isFromExternal = false
        return u
    }
}

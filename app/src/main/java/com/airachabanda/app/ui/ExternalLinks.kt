package com.airachabanda.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.airachabanda.app.security.RedactedLog

object ExternalLinks {
    fun openHttps(context: Context, url: String): String {
        require(url.startsWith("https://")) { "Only HTTPS links can be opened." }
        return try {
            val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            tabs.launchUrl(context, url.toUri())
            "The official site was requested. Opening a page does not verify a login."
        } catch (error: ActivityNotFoundException) {
            RedactedLog.d("ExternalLinks", "No browser available")
            "No browser is available to open the official site."
        }
    }

    fun copy(context: Context, label: String, text: String): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return "Prompt copied. Paste it into the provider's own interface."
    }

    fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }
}

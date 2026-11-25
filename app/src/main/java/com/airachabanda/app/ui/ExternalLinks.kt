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
    fun openHttps(context: Context, url: String): String = launch(context, url, customTab = true)

    fun openInBrowser(context: Context, url: String): String = launch(context, url, customTab = false)

    private fun launch(context: Context, url: String, customTab: Boolean): String {
        require(url.startsWith("https://")) { "Only HTTPS links can be opened." }
        val uri = url.toUri()
        if (customTab) {
            try {
                val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
                tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tabs.launchUrl(context, uri)
                return opened(url)
            } catch (error: ActivityNotFoundException) {
                RedactedLog.d("ExternalLinks", "No custom tab")
            } catch (error: RuntimeException) {
                RedactedLog.d("ExternalLinks", "Custom tab was not started")
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            opened(url)
        } catch (error: ActivityNotFoundException) {
            RedactedLog.d("ExternalLinks", "No browser available")
            "No browser is available. The address was not opened."
        }
    }

    private fun opened(url: String): String = when {
        url.startsWith("https://chatgpt.com") || url.startsWith("https://auth.openai.com") ->
            "ChatGPT opened in the browser. A website sign-in does not connect this app, and this app does not use your ChatGPT plan."
        else -> "The official site opened in the browser. Opening a page does not connect an automatic reply."
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

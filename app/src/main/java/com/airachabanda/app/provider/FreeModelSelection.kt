package com.airachabanda.app.provider

import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.billing.CloudflareBillingPolicy
import com.airachabanda.app.provider.gemini.GeminiFreeCatalog

object FreeModelSelection {
    const val AUTO = "Auto Select"

    fun options(providerId: ProviderId): List<String> = listOf(AUTO) + knownFree(providerId)

    fun choose(providerId: ProviderId, discovered: List<String>, manual: String = AUTO): String? {
        val requested = manual.trim().ifBlank { AUTO }
        if (requested != AUTO) return requested.takeIf { eligible(providerId, it) }
        val pool = if (discovered.isEmpty()) knownFree(providerId) else discovered.filter { eligible(providerId, it) }
        return pool.firstOrNull()
    }

    fun eligible(providerId: ProviderId, modelId: String): Boolean {
        if (modelId.isBlank() || modelId == AUTO) return false
        return when (providerId) {
            ProviderId.GEMINI -> GeminiFreeCatalog.eligible(modelId)
            ProviderId.CLOUDFLARE -> modelId.startsWith("@cf/") && modelId !in CloudflareBillingPolicy.PAID_REQUIRED_MODELS
            else -> false
        }
    }

    private fun knownFree(providerId: ProviderId): List<String> = when (providerId) {
        ProviderId.GEMINI -> GeminiFreeCatalog.TEXT_MODELS
        else -> emptyList()
    }
}

package com.airachabanda.app.provider

import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderId

enum class ProviderAccessMode {
    FREE_AUTOMATED_API,
    FREE_ASSISTED_CHAT,
    LOCAL_INFERENCE,
    UNAVAILABLE,
}

enum class AccessLabel {
    FREE_API,
    FREE_CHAT,
    CONFIGURATION_REQUIRED,
    BILLING_VERIFICATION_REQUIRED,
    LOCAL_INFERENCE,
    UNAVAILABLE,
}

data class ProviderAccessProfile(
    val providerId: ProviderId,
    val modes: Set<ProviderAccessMode>,
    val chatUrl: String?,
    val labels: List<AccessLabel>,
    val note: String,
)

object ProviderAccess {
    private val profiles: Map<ProviderId, ProviderAccessProfile> = listOf(
        apiOnly(
            ProviderId.CHATGPT,
            "Sign in with ChatGPT authorizes AI Rachabanda with the owner's ChatGPT plan. Logging into chatgpt.com in a browser alone does not connect this app.",
        ),
        unavailable(
            ProviderId.CLAUDE,
            "Claude has no verified zero-additional-cost automated route for this app. Automatic replies stay unavailable.",
        ),
        both(
            ProviderId.GEMINI,
            "https://gemini.google.com",
            "Gemini API keys can connect after free-tier billing checks. Browser login alone does not authorize this app.",
        ),
        unavailable(ProviderId.DEEPSEEK, "DeepSeek has no verified free automated route configured yet."),
        unavailable(ProviderId.GROK, "Grok has no verified free automated route configured yet."),
        unavailable(ProviderId.PERPLEXITY, "Perplexity has no verified free automated route configured yet."),
        unavailable(ProviderId.COPILOT, "Microsoft Copilot has no verified free automated route configured yet."),
        unavailable(ProviderId.QWEN, "Online Qwen remains a potential provider. Automatic access is not verified yet."),
        unavailable(ProviderId.MISTRAL, "Mistral API requires billing and is not used."),
        unavailable(ProviderId.KIMI, "Kimi has no verified free automated route configured yet."),
        unavailable(ProviderId.META_AI, "Meta AI has no verified free automated route configured yet."),
        apiOnly(ProviderId.GROQ, "Groq free API keys can connect after a live free-tier check."),
        apiOnly(ProviderId.OPENROUTER, "OpenRouter stays off until a free route and billing protection are verified."),
        apiOnly(ProviderId.CLOUDFLARE, "Workers AI connects only after a free Workers plan is verified."),
        unavailable(
            ProviderId.LOCAL,
            "Offline AI was removed. Connect an online AI account instead.",
        ),
    ).associateBy { it.providerId }

    val defaultEnabled: Set<ProviderId> = emptySet()

    fun profile(id: ProviderId): ProviderAccessProfile = profiles.getValue(id)

    fun assistedProviders(): List<ProviderId> = ProviderId.entries.filter { active(it) == ParticipationMode.ASSISTED }

    fun active(id: ProviderId): ParticipationMode = choose(
        modes = profile(id).modes,
        apiProtectionEstablished = false,
        paidFallbackRequested = false,
    )

    fun choose(
        modes: Set<ProviderAccessMode>,
        apiProtectionEstablished: Boolean,
        paidFallbackRequested: Boolean,
    ): ParticipationMode {
        if (paidFallbackRequested) return ParticipationMode.UNAVAILABLE
        if (ProviderAccessMode.FREE_AUTOMATED_API in modes && apiProtectionEstablished) return ParticipationMode.AUTOMATED
        if (ProviderAccessMode.FREE_ASSISTED_CHAT in modes) return ParticipationMode.ASSISTED
        return ParticipationMode.UNAVAILABLE
    }

    private fun chat(id: ProviderId, url: String, note: String) = ProviderAccessProfile(
        providerId = id,
        modes = setOf(ProviderAccessMode.FREE_ASSISTED_CHAT),
        chatUrl = url,
        labels = listOf(AccessLabel.FREE_CHAT),
        note = note,
    )

    private fun both(id: ProviderId, url: String, note: String) = ProviderAccessProfile(
        providerId = id,
        modes = setOf(ProviderAccessMode.FREE_ASSISTED_CHAT, ProviderAccessMode.FREE_AUTOMATED_API),
        chatUrl = url,
        labels = listOf(AccessLabel.FREE_CHAT, AccessLabel.BILLING_VERIFICATION_REQUIRED),
        note = note,
    )

    private fun unavailable(id: ProviderId, note: String) = ProviderAccessProfile(
        providerId = id,
        modes = setOf(ProviderAccessMode.UNAVAILABLE),
        chatUrl = null,
        labels = listOf(AccessLabel.UNAVAILABLE),
        note = note,
    )

    private fun apiOnly(id: ProviderId, note: String) = ProviderAccessProfile(
        providerId = id,
        modes = setOf(ProviderAccessMode.FREE_AUTOMATED_API),
        chatUrl = null,
        labels = listOf(AccessLabel.BILLING_VERIFICATION_REQUIRED, AccessLabel.CONFIGURATION_REQUIRED),
        note = note,
    )
}

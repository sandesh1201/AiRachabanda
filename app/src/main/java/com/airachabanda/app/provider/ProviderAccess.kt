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
        chat(ProviderId.CHATGPT, "https://chatgpt.com", "Free ChatGPT chat is assisted. The API is not used."),
        chat(ProviderId.CLAUDE, "https://claude.ai", "Free Claude chat is assisted. Consumer credentials are not collected."),
        both(
            ProviderId.GEMINI,
            "https://gemini.google.com",
            "Gemini chat is assisted. The Gemini API is separate and stays off until project billing is verified.",
        ),
        chat(ProviderId.DEEPSEEK, "https://chat.deepseek.com", "DeepSeek chat is assisted. The API is not used."),
        chat(ProviderId.GROK, "https://grok.com", "Grok chat is assisted. The API is not used."),
        chat(ProviderId.PERPLEXITY, "https://www.perplexity.ai", "Perplexity chat is assisted. The API is not used."),
        chat(ProviderId.COPILOT, "https://copilot.microsoft.com", "Microsoft Copilot chat is assisted. The Azure API is not used."),
        chat(ProviderId.QWEN, "https://chat.qwen.ai", "Qwen chat is assisted. The API is not used."),
        chat(ProviderId.MISTRAL, "https://chat.mistral.ai", "Le Chat is assisted. The Mistral API requires billing and is not used."),
        chat(ProviderId.KIMI, "https://www.kimi.com", "Kimi chat is assisted. The API is not used."),
        chat(ProviderId.META_AI, "https://meta.ai", "Meta AI chat is assisted. No API is used."),
        apiOnly(ProviderId.GROQ, "Groq has an API console, not a separate free chat. Free and Developer billing cannot be distinguished, so automation is off."),
        apiOnly(ProviderId.OPENROUTER, "OpenRouter has no free consumer chat. Automation stays off until billing protection is verified."),
        apiOnly(ProviderId.CLOUDFLARE, "Workers AI has no free consumer chat. A missing paid subscription does not positively establish Workers Free."),
        local(),
    ).associateBy { it.providerId }

    val defaultEnabled: Set<ProviderId> = setOf(ProviderId.CLAUDE)

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

    private fun local() = ProviderAccessProfile(
        providerId = ProviderId.LOCAL,
        modes = setOf(ProviderAccessMode.LOCAL_INFERENCE),
        chatUrl = null,
        labels = listOf(AccessLabel.LOCAL_INFERENCE, AccessLabel.CONFIGURATION_REQUIRED),
        note = "LiteRT-LM 0.20.0 is packaged for CPU. Model weights are not installed until a confirmed download finishes. A cloud model is not used.",
    )

    private fun apiOnly(id: ProviderId, note: String) = ProviderAccessProfile(
        providerId = id,
        modes = setOf(ProviderAccessMode.FREE_AUTOMATED_API),
        chatUrl = null,
        labels = listOf(AccessLabel.BILLING_VERIFICATION_REQUIRED, AccessLabel.CONFIGURATION_REQUIRED),
        note = note,
    )
}

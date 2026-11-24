package com.airachabanda.app.provider

import com.airachabanda.app.core.model.ProviderId

enum class SetupMethod(val label: String) {
    FREE_AUTOMATED_API("Free automated API"),
    FREE_ASSISTED_CHAT("Free assisted chat"),
    SUBSCRIPTION_ASSISTED("Existing subscription-assisted chat"),
    LOCAL_INFERENCE("Local inference"),
}

data class OfficialLink(val label: String, val url: String)

data class SecretField(val key: String, val label: String)

data class ProviderSetupSpec(
    val id: ProviderId,
    val methods: List<SetupMethod>,
    val fields: List<SecretField>,
    val links: List<OfficialLink>,
    val requirements: String,
    val automatedBlocker: String,
)

data class SetupStep(val name: String, val state: String)

object ProviderSetupCatalog {
    const val OAUTH_DISCLOSURE =
        "Google requires an Android OAuth client whose package name and signing certificate match this install. " +
            "Registering that client in the owner's Google Cloud project is a provider-side step. " +
            "A client secret is not used. The debug certificate is read from this install and is not a permanent production fingerprint."

    private val specs: Map<ProviderId, ProviderSetupSpec> = listOf(
        assisted(ProviderId.CHATGPT, "https://chatgpt.com", "The ChatGPT API is a separate product. A chat subscription does not authorize it."),
        assisted(ProviderId.CLAUDE, "https://claude.ai", "Claude chat is assisted. The Claude Platform API is not a zero-charge path."),
        ProviderSetupSpec(
            id = ProviderId.GEMINI,
            methods = listOf(SetupMethod.FREE_ASSISTED_CHAT, SetupMethod.FREE_AUTOMATED_API),
            fields = listOf(SecretField("api-key", "API key")),
            links = listOf(
                OfficialLink("Gemini chat", "https://gemini.google.com"),
                OfficialLink("Create an API key", "https://aistudio.google.com/apikey"),
                OfficialLink("API credentials", "https://console.cloud.google.com/apis/credentials"),
                OfficialLink("Billing", "https://console.cloud.google.com/billing"),
                OfficialLink("Free-tier pricing", "https://ai.google.dev/gemini-api/docs/pricing"),
            ),
            requirements = "Assisted chat opens gemini.google.com. The API stays off until the stored key is bound to a project whose billingEnabled is false. Context caching and grounding stay off. $OAUTH_DISCLOSURE",
            automatedBlocker = "Automated Gemini stays off until project billing is shown to be disabled for the key's project.",
        ),
        ProviderSetupSpec(
            id = ProviderId.CLOUDFLARE,
            methods = listOf(SetupMethod.FREE_AUTOMATED_API),
            fields = listOf(SecretField("account-id", "Account ID"), SecretField("api-token", "API token")),
            links = listOf(
                OfficialLink("Create an API token", "https://dash.cloudflare.com/profile/api-tokens"),
                OfficialLink("Workers AI pricing", "https://developers.cloudflare.com/workers-ai/platform/pricing/"),
                OfficialLink("Workers dashboard", "https://dash.cloudflare.com"),
            ),
            requirements = "Use a token limited to this account. Workers Free must be positively identified. A missing paid subscription is not that proof. Some models require Workers Paid or prepaid AI Gateway credits.",
            automatedBlocker = "Automated Workers AI stays off until Workers Free is positively identified.",
        ),
        ProviderSetupSpec(
            id = ProviderId.OPENROUTER,
            methods = listOf(SetupMethod.FREE_AUTOMATED_API),
            fields = listOf(SecretField("api-key", "API key")),
            links = listOf(
                OfficialLink("Create an API key", "https://openrouter.ai/settings/keys"),
                OfficialLink("Pricing and free limits", "https://openrouter.ai/pricing"),
                OfficialLink("OpenRouter", "https://openrouter.ai"),
            ),
            requirements = "A free-model name is not enough. The key must not be able to buy credits, use a paid fallback, or enable a billable plugin. A negative balance blocks free models.",
            automatedBlocker = "Automated OpenRouter stays off until spending restrictions are verified. Chat completions stay off.",
        ),
        ProviderSetupSpec(
            id = ProviderId.GROQ,
            methods = listOf(SetupMethod.FREE_AUTOMATED_API),
            fields = listOf(SecretField("api-key", "API key")),
            links = listOf(
                OfficialLink("Create an API key", "https://console.groq.com/keys"),
                OfficialLink("Plans", "https://console.groq.com/settings/billing/plans"),
                OfficialLink("Billing questions", "https://console.groq.com/docs/billing-faqs"),
            ),
            requirements = "Groq lists a Free plan at no charge and a Developer plan that requires a payment method. Saving a key does not show which plan the organization uses.",
            automatedBlocker = "Automated Groq stays off until the organization is shown to be on the Free plan.",
        ),
        assisted(ProviderId.DEEPSEEK, "https://chat.deepseek.com", "DeepSeek chat is assisted. The API is not used."),
        assisted(ProviderId.GROK, "https://grok.com", "Grok chat is assisted. The API is not used."),
        assisted(ProviderId.PERPLEXITY, "https://www.perplexity.ai", "Perplexity chat is assisted. The API is not used."),
        assisted(ProviderId.COPILOT, "https://copilot.microsoft.com", "Microsoft Copilot chat is assisted. The Azure API is not used."),
        assisted(ProviderId.QWEN, "https://chat.qwen.ai", "Qwen chat is assisted. The API is not used."),
        assisted(ProviderId.MISTRAL, "https://chat.mistral.ai", "Le Chat is assisted. The Mistral API requires billing and is not used."),
        assisted(ProviderId.KIMI, "https://www.kimi.com", "Kimi chat is assisted. The API is not used."),
        assisted(ProviderId.META_AI, "https://meta.ai", "Meta AI chat is assisted. No API is used."),
        ProviderSetupSpec(
            id = ProviderId.LOCAL,
            methods = listOf(SetupMethod.LOCAL_INFERENCE),
            fields = emptyList(),
            links = emptyList(),
            requirements = "LiteRT-LM is prepared for Gemma 3 1B and Qwen 2.5 1.5B. No runtime or model file is installed. This build does not download one.",
            automatedBlocker = "Local inference is not installed.",
        ),
    ).associateBy { it.id }

    fun spec(id: ProviderId): ProviderSetupSpec = specs.getValue(id)

    fun all(): List<ProviderSetupSpec> = ProviderId.entries.map { spec(it) }

    fun progress(
        spec: ProviderSetupSpec,
        method: SetupMethod,
        credentialStored: Boolean,
        billingVerified: Boolean,
        enabled: Boolean,
    ): List<SetupStep> {
        val selected = method in spec.methods
        val assisted = method == SetupMethod.FREE_ASSISTED_CHAT || method == SetupMethod.SUBSCRIPTION_ASSISTED
        return listOf(
            SetupStep("Select access method", if (selected) "Saved in the app." else "Choose one supported method."),
            SetupStep("Requirements", if (selected) spec.requirements else "Choose an access method first."),
            SetupStep(
                "Credentials or authorization",
                when {
                    !selected -> "Choose an access method first."
                    assisted -> "Open the official page. This app cannot confirm a consumer sign-in."
                    spec.fields.isEmpty() -> spec.automatedBlocker
                    credentialStored -> "A credential is stored. It is not shown again."
                    else -> "Enter the credential in this app."
                },
            ),
            SetupStep(
                "Verify authentication",
                when {
                    assisted -> "Opening the official chat is not an automated connection."
                    credentialStored && !billingVerified -> "Stored. Authentication has not been verified."
                    else -> "Authentication has not been verified."
                },
            ),
            SetupStep(
                "Verify zero-cost eligibility",
                when {
                    assisted -> "Assisted chat does not call the API."
                    billingVerified -> "Free eligibility is recorded."
                    else -> spec.automatedBlocker
                },
            ),
            SetupStep(
                "Select a model",
                if (assisted || method == SetupMethod.LOCAL_INFERENCE) {
                    "This app has not discovered a model for automated use."
                } else {
                    "No eligible model has been discovered. Paid and unknown prices stay unselectable."
                },
            ),
            SetupStep(
                "Test connection",
                if (billingVerified && method == SetupMethod.FREE_AUTOMATED_API) {
                    "One consented test can be sent from the provider verification control. It is not sent automatically."
                } else {
                    "No inference test is sent until eligibility and a separate consent are both present."
                },
            ),
            SetupStep(
                "Council participation",
                when {
                    assisted && enabled -> "Enabled for assisted discussions."
                    assisted -> "Disabled. Credentials, if any, stay stored until you delete them."
                    billingVerified && enabled -> "Enabled for automated participation."
                    else -> "Automated participation stays off."
                },
            ),
        )
    }

    private fun assisted(id: ProviderId, chatUrl: String, note: String) = ProviderSetupSpec(
        id = id,
        methods = listOf(SetupMethod.FREE_ASSISTED_CHAT, SetupMethod.SUBSCRIPTION_ASSISTED),
        fields = emptyList(),
        links = listOf(
            OfficialLink("Open official chat", chatUrl),
            OfficialLink("Open official sign-in", chatUrl),
        ),
        requirements = "$note Selecting a subscription does not authorize API usage.",
        automatedBlocker = "No verified zero-cost automated API is available.",
    )
}

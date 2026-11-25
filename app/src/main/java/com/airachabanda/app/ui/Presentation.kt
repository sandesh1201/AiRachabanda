package com.airachabanda.app.ui

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.provider.AccessLabel
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode

object ChatReadiness {
    fun automatic(providers: List<ProviderConfig>): Boolean =
        providers.any { config ->
            config.providerId != ProviderId.LOCAL &&
                config.automatedInferenceEnabled &&
                com.airachabanda.app.orchestration.ZeroCostPolicy.automatedInferenceAllowed(
                    config.providerId,
                    config.billingEligibility,
                )
        }

    @Deprecated("Offline AI was removed")
    fun automatic(localInstalled: Boolean, providers: List<ProviderConfig>): Boolean = automatic(providers)
}

object DiscussionPresence {
    const val READY = "Ready"
    const val RUNNING = "Running"
    const val PAUSED = "Paused"
    const val WAITING = "Waiting for you"
    const val FINISHED = "Finished"

    fun phase(stage: String?): String = when (stage) {
        null, "", "No session" -> READY
        "Paused" -> PAUSED
        "Waiting for your response" -> WAITING
        "Completed", "Stopped", "Failed", "Blocked" -> FINISHED
        else -> RUNNING
    }

    fun summary(stage: String?, pending: Int): String = when (phase(stage)) {
        READY -> "Ready to start"
        PAUSED -> "Paused"
        WAITING -> "Bring the assistant's reply back"
        FINISHED -> stage ?: FINISHED
        else -> if (pending > 0) "${stage ?: RUNNING} · $pending still open" else stage ?: RUNNING
    }
}

object ModeGuide {
    fun summary(mode: ResearchMode): String = when (mode) {
        ResearchMode.QUICK ->
            "Quick asks once. One AI request is not an independent multi-model review. Critical review stays closed unless a critique capability is requested. One task runs at a time."
        ResearchMode.STANDARD ->
            "Standard includes a critical review. Up to two tasks can run together. Delegation can go two steps."
        ResearchMode.DEEP_RESEARCH ->
            "Deep Research includes review and verification. Up to three tasks can run together. Delegation can go three steps."
        ResearchMode.CUSTOM ->
            "Custom uses the limits you save. A round is not one provider request. Budgets are not raised automatically."
    }

    fun customDetail(): String =
        "Custom settings the engine uses: selected participants, the round limit, the AI request budget, the research request budget, " +
            "the research source limit, concurrency, delegation depth, the discussion deadline, and whether verification is required. " +
            "A round is not one provider request. Critical review opens when a critique capability is requested. " +
            "Public research still requires your consent. Zero-cost rules apply in every mode."
}

object ProviderPresentation {
    fun status(
        config: ProviderConfig,
        enabled: Boolean = true,
        quotaExhausted: Boolean = false,
        credentialStored: Boolean = false,
        authenticationRequired: Boolean = false,
    ): String {
        if (!enabled && config.participationMode == ParticipationMode.ASSISTED) return "Disabled"
        if (quotaExhausted) return "Free limit reached"
        val labels = ProviderAccess.profile(config.providerId).labels
        val modes = ProviderAccess.profile(config.providerId).modes
        if (AccessLabel.UNAVAILABLE in labels || ProviderAccessMode.UNAVAILABLE in modes) {
            return "Unavailable"
        }
        val verified = config.billingEligibility == BillingEligibility.VERIFIED_FREE ||
            config.billingEligibility == BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED
        if (config.billingEligibility == BillingEligibility.PAID || config.billingEligibility == BillingEligibility.BLOCKED) {
            return "Free limit reached"
        }
        if (verified && config.automatedInferenceEnabled && ProviderAccessMode.FREE_AUTOMATED_API in modes) {
            return "Connected"
        }
        if (verified) return "Free eligibility verified"
        val configured = credentialStored || config.accountChoice != AccountChoice.NOT_CONFIGURED
        if (!configured) {
            if (AccessLabel.CONFIGURATION_REQUIRED in labels || AccessLabel.BILLING_VERIFICATION_REQUIRED in labels) {
                return "Not connected"
            }
        } else {
            if (authenticationRequired) return "Reconnect required"
            if (AccessLabel.BILLING_VERIFICATION_REQUIRED in labels && AccessLabel.FREE_CHAT !in labels) {
                return "Reconnect required"
            }
            if (credentialStored) return "Credential stored"
            return "Not connected"
        }
        if (config.participationMode == ParticipationMode.UNAVAILABLE) {
            return "Unavailable"
        }
        if (config.accountChoice == AccountChoice.NOT_CONFIGURED) return "Not connected"
        return "Unavailable"
    }

    fun blocker(status: String): String = when (status) {
        "Disabled" -> "This participant is turned off for new discussions."
        "Free limit reached" -> "The free allowance is used up. This app does not buy more and does not switch to a paid request."
        "Connected" -> "Authorization and a live inference test succeeded."
        "Free eligibility verified" -> "Free eligibility is recorded. Automated participation stays off until it is enabled for this provider."
        "Credential stored" -> "A credential is stored in Android Keystore and is not shown. Billing eligibility is not verified, so inference stays off."
        "Reconnect required" -> "Official authorization is incomplete or expired."
        "Not connected" -> "No credential is stored. Sign in or enter an API key in this app."
        "Unavailable" -> "No verified zero-cost automated path is available."
        else -> "Automated inference stays off until eligibility is verified."
    }

    fun kind(config: ProviderConfig): String {
        val modes = ProviderAccess.profile(config.providerId).modes
        return when {
            ProviderAccessMode.FREE_AUTOMATED_API in modes -> "Online AI"
            else -> "Unavailable"
        }
    }

    fun discussionStatus(state: String?): String = when (state) {
        null, "" -> "No discussion yet"
        "PAUSED" -> "Paused"
        "CANCELLED" -> "Stopped"
        "COMPLETED" -> "Completed"
        "FAILED" -> "Failed"
        "AWAITING_USER_RESPONSE" -> "Waiting for your response"
        "SYNTHESIS" -> "Synthesis"
        "COLLECTING_RESPONSES" -> "Collecting responses"
        else -> state.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
    }
}

object ConnectionPresentation {
    const val NOT_CONNECTED = "Not connected"
    const val CONNECTING = "Connecting"
    const val CONNECTED = "Connected"
    const val RECONNECT_REQUIRED = "Reconnect required"
    const val FREE_LIMIT_REACHED = "Free limit reached"
    const val UNAVAILABLE = "Unavailable"

    @Deprecated("Use CONNECTED")
    const val READY = CONNECTED
    @Deprecated("Use UNAVAILABLE")
    const val ASSISTED_CHAT = UNAVAILABLE
    @Deprecated("Use FREE_LIMIT_REACHED")
    const val FREE_ACCESS_UNAVAILABLE = FREE_LIMIT_REACHED
    @Deprecated("Use UNAVAILABLE")
    const val TEMPORARILY_UNAVAILABLE = UNAVAILABLE
    @Deprecated("Use RECONNECT_REQUIRED")
    const val SETUP_REQUIRED = RECONNECT_REQUIRED

    fun state(
        config: ProviderConfig,
        enabled: Boolean = true,
        quotaExhausted: Boolean = false,
        credentialStored: Boolean = false,
        authenticationRequired: Boolean = false,
        connecting: Boolean = false,
        connectionTested: Boolean = false,
    ): String {
        if (connecting) return CONNECTING
        if (quotaExhausted) return FREE_LIMIT_REACHED
        if (config.billingEligibility == BillingEligibility.PAID || config.billingEligibility == BillingEligibility.BLOCKED) {
            return FREE_LIMIT_REACHED
        }
        val modes = ProviderAccess.profile(config.providerId).modes
        val verified = config.billingEligibility == BillingEligibility.VERIFIED_FREE ||
            config.billingEligibility == BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED
        val automated = ProviderAccessMode.FREE_AUTOMATED_API in modes
        if (verified && config.automatedInferenceEnabled && enabled && automated && connectionTested) {
            return CONNECTED
        }
        if (verified && config.automatedInferenceEnabled && enabled && automated) {
            return RECONNECT_REQUIRED
        }
        if (ProviderAccessMode.UNAVAILABLE in modes || AccessLabel.UNAVAILABLE in ProviderAccess.profile(config.providerId).labels) {
            return UNAVAILABLE
        }
        if (authenticationRequired) return RECONNECT_REQUIRED
        if (automated && credentialStored && !verified) return RECONNECT_REQUIRED
        if (verified && automated && !config.automatedInferenceEnabled) return RECONNECT_REQUIRED
        if (automated && !credentialStored) return NOT_CONNECTED
        if (config.participationMode == ParticipationMode.UNAVAILABLE) return UNAVAILABLE
        return NOT_CONNECTED
    }

    fun explanation(state: String, providerId: ProviderId): String = when (state) {
        CONNECTING -> "Checking this provider. No paid request is sent."
        CONNECTED, READY -> "Authorization and a live inference test succeeded. This provider can join the Council."
        FREE_LIMIT_REACHED, FREE_ACCESS_UNAVAILABLE ->
            "Free usage is exhausted or billing would apply. Automated use stays off."
        UNAVAILABLE, ASSISTED_CHAT, TEMPORARILY_UNAVAILABLE -> when (providerId) {
            ProviderId.LOCAL -> "Offline AI was removed. Connect an online AI account instead."
            ProviderId.CLAUDE -> "Claude has no verified zero-additional-cost automated route for this app."
            else -> "This provider cannot take part right now. Other AIs can continue."
        }
        RECONNECT_REQUIRED, SETUP_REQUIRED -> when (providerId) {
            ProviderId.CHATGPT -> "Sign in with ChatGPT again, grant plan usage, and complete the return to this app."
            ProviderId.GROQ -> "The key is saved. Groq Free plan proof is incomplete."
            ProviderId.CLOUDFLARE -> "The token is saved. Workers Free is not verified."
            ProviderId.OPENROUTER -> "The key is saved. Spending limits are not verified."
            ProviderId.GEMINI -> "The key is saved. Project billing is not verified."
            else -> "Authorization is incomplete. Automated use stays off."
        }
        else -> when (providerId) {
            ProviderId.CHATGPT -> "Tap Connect, sign in on OpenAI's page, grant plan usage, and return automatically to this app."
            else -> "Paste an API key and tap Connect. Opening a website alone does not connect this app."
        }
    }
}

object ActivityFilter {
    val types = listOf(
        "All",
        "Assignments",
        "Research",
        "Exchanges",
        "Verification",
        "Corrections",
        "Failures",
        "Controls",
        "Documents",
    )

    fun matches(type: String, filter: String): Boolean {
        if (filter == "All") return true
        val name = type.uppercase()
        return when (filter) {
            "Assignments" -> "TASK" in name || "ASSIGN" in name || "PARTICIPANT_REQUEST" in name || "DISCUSSION_STARTED" in name
            "Research" -> "RESEARCH" in name || "SOURCE" in name || "RETRIEV" in name
            "Exchanges" -> "RESPONSE" in name || "EXCHANGE" in name || "MESSAGE" in name || "IMPORT" in name
            "Verification" -> "VERIF" in name || "CLAIM" in name || "OBJECTION" in name
            "Corrections" -> "CORRECT" in name || "INSTRUCTION" in name || "CONTEXT" in name
            "Failures" -> "FAIL" in name || "LATE" in name || "FAULT" in name || "REJECT" in name
            "Controls" -> "PAUSE" in name || "RESUME" in name || "STOP" in name || "FINISH" in name || "SKIP" in name
            "Documents" -> "DOCUMENT" in name || "ATTACHMENT" in name
            else -> true
        }
    }

    fun sourceUrl(summary: String): String? =
        Regex("""https://[^\s]+""").find(summary)?.value?.trimEnd('.', ',')
}

package com.airachabanda.app.ui

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.provider.AccessLabel
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode

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
        if (quotaExhausted) return "Free quota exhausted"
        val labels = ProviderAccess.profile(config.providerId).labels
        val verified = config.billingEligibility == BillingEligibility.VERIFIED_FREE ||
            config.billingEligibility == BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED
        if (config.billingEligibility == BillingEligibility.PAID || config.billingEligibility == BillingEligibility.BLOCKED) {
            return "Billing blocked"
        }
        val modes = ProviderAccess.profile(config.providerId).modes
        if (verified && config.automatedInferenceEnabled && ProviderAccessMode.FREE_AUTOMATED_API in modes) {
            return "Ready for automated participation"
        }
        if (verified) return "Free eligibility verified"
        val configured = credentialStored || config.accountChoice != AccountChoice.NOT_CONFIGURED
        if (!configured) {
            if (AccessLabel.FREE_CHAT in labels) return "Assisted chat available"
            if (AccessLabel.CONFIGURATION_REQUIRED in labels || AccessLabel.BILLING_VERIFICATION_REQUIRED in labels) {
                return "Not configured"
            }
        } else {
            if (authenticationRequired) return "Authentication required"
            if (AccessLabel.BILLING_VERIFICATION_REQUIRED in labels && AccessLabel.FREE_CHAT !in labels) {
                return "Verification required"
            }
            return "Credential stored"
        }
        if (AccessLabel.FREE_CHAT in labels) return "Assisted chat available"
        if (config.participationMode == ParticipationMode.UNAVAILABLE || AccessLabel.UNAVAILABLE in labels) {
            return "Temporarily unavailable"
        }
        if (AccessLabel.LOCAL_INFERENCE in labels) return "Not configured"
        if (config.accountChoice == AccountChoice.NOT_CONFIGURED) return "Not configured"
        return "Assisted chat available"
    }

    fun blocker(status: String): String = when (status) {
        "Disabled" -> "This participant is turned off for new discussions."
        "Free quota exhausted" -> "The free allowance is used up. This app does not buy more and does not switch to a paid request."
        "Billing blocked" -> "A charge remains possible, so automated access stays off."
        "Authentication required" -> "Official authorization is not complete. No metadata or inference request was sent."
        "Ready for automated participation" -> "Free eligibility is verified and automated participation is enabled. Paid fallback stays off."
        "Free eligibility verified" -> "Free eligibility is recorded. Automated participation stays off until it is enabled for this provider."
        "Credential stored" -> "A credential is stored in Android Keystore and is not shown. Billing eligibility is not verified, so inference stays off."
        "Verification required" -> "The account plan or project billing has not been shown to be free."
        "Not configured" -> "No credential is stored. Enter it in this app. Do not paste it into a chat."
        "Assisted chat available" -> "Official free chat is assisted. An imported response is user-supplied and is not an authenticated provider result."
        "Temporarily unavailable" -> "No verified zero-cost automated path is available."
        else -> "Automated inference stays off until eligibility is verified."
    }

    fun kind(config: ProviderConfig): String {
        val modes = ProviderAccess.profile(config.providerId).modes
        return when {
            ProviderAccessMode.LOCAL_INFERENCE in modes -> "Local inference"
            ProviderAccessMode.FREE_AUTOMATED_API in modes && ProviderAccessMode.FREE_ASSISTED_CHAT !in modes -> "Automated API"
            else -> "Assisted chat"
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

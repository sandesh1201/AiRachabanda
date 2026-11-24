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
            "Quick asks once. Critical review stays closed unless a critique capability is requested. One task runs at a time."
        ResearchMode.STANDARD ->
            "Standard includes a critical review. Up to two tasks can run together. Delegation can go two steps."
        ResearchMode.DEEP_RESEARCH ->
            "Deep Research includes review and verification. Up to three tasks can run together. Delegation can go three steps."
        ResearchMode.CUSTOM ->
            "Custom uses the participants and round limit you choose. Concurrency is up to two. Delegation can go two steps."
    }

    fun customDetail(): String =
        "Custom settings the engine uses: selected participants, and the round limit from Discussion defaults. " +
            "That round limit is also the automated request budget. Concurrency stays at most 2 and delegation depth stays at 2. " +
            "Critical review opens when a critique capability is requested. Verification opens when the question needs a numeric check. " +
            "Public research still requires your consent. Zero-cost rules apply in every mode."
}

object ProviderPresentation {
    fun status(config: ProviderConfig, enabled: Boolean = true, quotaExhausted: Boolean = false): String {
        if (!enabled && config.participationMode == ParticipationMode.ASSISTED) return "Disabled"
        if (quotaExhausted) return "Free quota exhausted"
        val labels = ProviderAccess.profile(config.providerId).labels
        if (config.billingEligibility == BillingEligibility.PAID || config.billingEligibility == BillingEligibility.BLOCKED) {
            return "Billing eligibility blocked"
        }
        if (AccessLabel.CONFIGURATION_REQUIRED in labels && config.accountChoice == AccountChoice.NOT_CONFIGURED) {
            return "Setup required"
        }
        if (AccessLabel.BILLING_VERIFICATION_REQUIRED in labels &&
            config.billingEligibility != BillingEligibility.VERIFIED_FREE &&
            config.billingEligibility != BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED &&
            AccessLabel.FREE_CHAT !in labels
        ) {
            return "Verification required"
        }
        if (AccessLabel.FREE_CHAT in labels) return "Assisted chat"
        if (config.participationMode == ParticipationMode.UNAVAILABLE || AccessLabel.UNAVAILABLE in labels) {
            return "Temporarily unavailable"
        }
        if (AccessLabel.LOCAL_INFERENCE in labels) return "Local inference"
        val modes = ProviderAccess.profile(config.providerId).modes
        if (ProviderAccessMode.FREE_AUTOMATED_API in modes &&
            config.automatedInferenceEnabled &&
            ProviderAccessMode.FREE_ASSISTED_CHAT !in modes
        ) {
            return "Available"
        }
        if (AccessLabel.FREE_CHAT in labels || ProviderAccessMode.FREE_ASSISTED_CHAT in modes) return "Assisted chat"
        if (config.accountChoice == AccountChoice.NOT_CONFIGURED) return "Setup required"
        return "Assisted chat"
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

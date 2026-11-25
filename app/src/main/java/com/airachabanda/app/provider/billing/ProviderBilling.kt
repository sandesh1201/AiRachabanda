package com.airachabanda.app.provider.billing

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ProviderId

enum class VerificationSource {
    NOT_CHECKED,
    PROGRAMMATIC,
    MANUAL_ONLY,
    INSUFFICIENT,
}

enum class PlanState {
    UNKNOWN,
    FREE_DOCUMENTED,
    PAID,
}

data class BillingSnapshot(
    val providerId: ProviderId,
    val authentication: String,
    val plan: PlanState,
    val billing: BillingEligibility,
    val modelEligible: Boolean,
    val usageLimits: String,
    val remainingQuota: String,
    val additionalChargeProtection: String,
    val verificationSource: VerificationSource,
    val verifiedAt: String,
    val blockers: String,
)

data class BillingDecision(
    val accepted: Boolean,
    val liveCallPermitted: Boolean,
    val reason: String,
    val snapshot: BillingSnapshot,
)

object CloudflareBillingPolicy {
    /**
     * Models the Workers AI pricing page, read 23 November 2026, says require
     * a paid billing method or prepaid AI Gateway credits.
     */
    val PAID_REQUIRED_MODELS = setOf(
        "@cf/moonshotai/kimi-k2.6",
        "@cf/moonshotai/kimi-k2.7-code",
        "@cf/zai-org/glm-5.2",
        "@cf/zai-org/glm-5.3",
        "@cf/zai-org/glm-5.3-flash",
        "@cf/deepseek-ai/deepseek-v4-flash-0731",
        "@cf/deepseek-ai/deepseek-v4.1-flash-1017",
        "@cf/deepseek-ai/deepseek-v4-pro-0813",
        "@cf/thinkingmachines/inkling-2-small",
    )

    fun assess(
        subscriptionsRead: Boolean,
        paidWorkersPlanListed: Boolean,
        modelId: String,
        textGeneration: Boolean,
        quotaExhausted: Boolean,
        sessionBudgetRemaining: Int,
        gatewayRequested: Boolean,
        positiveFreePlan: Boolean = false,
    ): BillingDecision {
        val paidModel = modelId in PAID_REQUIRED_MODELS
        val plan = when {
            paidWorkersPlanListed -> PlanState.PAID
            positiveFreePlan && subscriptionsRead -> PlanState.FREE_DOCUMENTED
            else -> PlanState.UNKNOWN
        }
        val protection = when {
            plan == PlanState.FREE_DOCUMENTED -> "Workers Free was positively identified. The pricing page says that plan fails after 10,000 Neurons per day instead of charging. An unknown remaining count is not an overage charge."
            plan == PlanState.PAID -> "A paid Workers subscription was listed. Usage above the daily Neuron allocation is billed."
            subscriptionsRead -> "The subscriptions response did not name Workers Free. Omitting WORKERS_PAID is not positive proof. The pricing page describes the default plan, and that description is not this account's verified plan."
            else -> "Workers Free was not verified. A Workers AI token cannot read the plan. Billing Read is required, and a missing read is not treated as Free."
        }
        val reason = when {
            gatewayRequested -> "AI Gateway prepaid credits are rejected."
            paidModel -> "This model requires a paid billing method or prepaid credits."
            !textGeneration -> "Only a text-generation model can be selected."
            plan == PlanState.UNKNOWN && subscriptionsRead -> "Excluding a known paid plan does not establish Workers Free."
            plan == PlanState.UNKNOWN -> "The Workers plan is unknown. Inference is blocked."
            plan == PlanState.PAID -> "Workers Paid can charge for Neurons above the daily allocation."
            quotaExhausted -> "The observed free Neuron allowance is exhausted. No further request is sent."
            sessionBudgetRemaining <= 0 -> "The session request budget is exhausted."
            else -> "Workers Free was positively identified and the model is not on the paid-required list. Live inference is not authorized."
        }
        val accepted = reason.startsWith("Workers Free was positively")
        return BillingDecision(
            accepted = accepted,
            liveCallPermitted = false,
            reason = reason,
            snapshot = BillingSnapshot(
                providerId = ProviderId.CLOUDFLARE,
                authentication = if (subscriptionsRead) "Credential accepted by the fixture or a prior response." else "Not established.",
                plan = plan,
                billing = if (accepted) BillingEligibility.UNKNOWN else BillingEligibility.BLOCKED,
                modelEligible = accepted,
                usageLimits = "Documented Workers Free allocation: 10,000 Neurons per day. This is not a remaining count.",
                remainingQuota = "Unknown",
                additionalChargeProtection = protection,
                verificationSource = if (plan == PlanState.FREE_DOCUMENTED) VerificationSource.PROGRAMMATIC else VerificationSource.INSUFFICIENT,
                verifiedAt = if (plan == PlanState.FREE_DOCUMENTED) "Positive plan read" else "Never",
                blockers = if (accepted) "Live inference is not authorized." else reason,
            ),
        )
    }
}

object GeminiBillingPolicy {
    fun assess(
        billingLinked: Boolean?,
        freeTierKnown: Boolean,
        paidModel: Boolean,
        toolsOrGrounding: Boolean,
        quotaExhausted: Boolean,
        sessionBudgetRemaining: Int,
        credentialBoundToProject: Boolean = false,
    ): BillingDecision {
        val plan = when {
            billingLinked == null || !credentialBoundToProject -> PlanState.UNKNOWN
            billingLinked -> PlanState.PAID
            else -> PlanState.FREE_DOCUMENTED
        }
        val reason = when {
            toolsOrGrounding -> "Grounding, search, caching, and tools are rejected."
            paidModel -> "The model is not eligible for the Free tier."
            billingLinked == null -> "A successful API key check does not prove that billing is unlinked."
            !credentialBoundToProject -> "The API key is not shown to belong to the project whose billing was read."
            billingLinked == true -> "A linked billing account can incur charges."
            !freeTierKnown -> "Free-tier pricing for this model was not in the model list."
            quotaExhausted -> "The observed free quota is exhausted. No further request is sent."
            sessionBudgetRemaining <= 0 -> "The session request budget is exhausted."
            else -> "Billing is unlinked and the model is marked free of charge. A live request is permitted."
        }
        val accepted = reason.startsWith("Billing is unlinked")
        return BillingDecision(
            accepted = accepted,
            liveCallPermitted = accepted,
            reason = reason,
            snapshot = BillingSnapshot(
                providerId = ProviderId.GEMINI,
                authentication = "Not treated as free-tier proof.",
                plan = plan,
                billing = if (accepted) BillingEligibility.UNKNOWN else BillingEligibility.BLOCKED,
                modelEligible = accepted,
                usageLimits = "Free-tier quotas are read in Google AI Studio. They are not copied from an old table.",
                remainingQuota = "Unknown",
                additionalChargeProtection = if (plan == PlanState.FREE_DOCUMENTED) {
                    "billingEnabled was false on the project bound to this credential. Paid tools stay off. An unknown remaining quota is not an overage charge on an unlinked project."
                } else {
                    "Project billing was not proven unlinked for this credential. The Gemini API key cannot call Cloud Billing or prove its project."
                },
                verificationSource = if (plan == PlanState.FREE_DOCUMENTED || plan == PlanState.PAID) VerificationSource.PROGRAMMATIC else VerificationSource.INSUFFICIENT,
                verifiedAt = if (billingLinked == null) "Never" else "Separate billing read",
                blockers = if (accepted) "None for this unlinked project." else reason,
            ),
        )
    }
}

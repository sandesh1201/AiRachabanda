package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ProviderId

object ZeroCostPolicy {
    const val MANDATORY = true

    fun automatedInferenceAllowed(
        providerId: ProviderId,
        billing: BillingEligibility,
    ): Boolean {
        if (!MANDATORY) return false
        if (providerId == ProviderId.CLAUDE || providerId == ProviderId.LOCAL) {
            return false
        }
        if (providerId == ProviderId.CHATGPT) {
            return billing == BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED
        }
        return billing == BillingEligibility.VERIFIED_FREE ||
            billing == BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED
    }
}

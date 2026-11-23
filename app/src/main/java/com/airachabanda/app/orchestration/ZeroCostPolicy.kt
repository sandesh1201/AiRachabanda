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
        if (providerId == ProviderId.CHATGPT || providerId == ProviderId.CLAUDE) {
            return false
        }
        return billing == BillingEligibility.VERIFIED_FREE ||
            billing == BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED
    }
}

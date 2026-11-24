package com.airachabanda.app.provider.cloudflare

/**
 * Decides whether a Cloudflare response positively names Workers Free.
 * A generic product plan id and an entitlement list do not.
 */
object CloudflarePlanSignal {
    fun workersFreeNamed(ratePlanIds: Set<String>, entitlementIds: Set<String>): Boolean {
        if (ratePlanIds.isEmpty() && entitlementIds.isEmpty()) return false
        val paid = ratePlanIds.any { it.equals("WORKERS_PAID", ignoreCase = true) }
        if (paid) return false
        val genericProductPlan = ratePlanIds.any {
            it.equals("free", ignoreCase = true) || it.equals("partners_free", ignoreCase = true)
        }
        if (genericProductPlan) return false
        return false
    }

    fun reason(ratePlanIds: Set<String>, entitlementIds: Set<String>): String = when {
        ratePlanIds.any { it.equals("WORKERS_PAID", ignoreCase = true) } ->
            "WORKERS_PAID was listed."
        ratePlanIds.any { it.equals("free", ignoreCase = true) || it.equals("partners_free", ignoreCase = true) } ->
            "A product rate plan named free is not the Workers Free plan."
        entitlementIds.isNotEmpty() ->
            "Entitlements are feature allocations. None is documented as the Workers Free plan name."
        else -> "No response field named Workers Free."
    }
}

package com.airachabanda.app.provider.connect

data class RuntimeEligibility(
    val credentialStored: Boolean = false,
    val authenticationVerified: Boolean = false,
    val billingVerified: Boolean = false,
    val eligibleModelDiscovered: Boolean = false,
    val connectionTested: Boolean = false,
    val detail: String = "",
) {
    val ready: Boolean =
        credentialStored && authenticationVerified && billingVerified && eligibleModelDiscovered && connectionTested

    fun lines(): List<String> = listOf(
        "Credential stored: ${yes(credentialStored)}",
        "Authentication verified: ${yes(authenticationVerified)}",
        "Billing verified: ${yes(billingVerified)}",
        "Eligible model discovered: ${yes(eligibleModelDiscovered)}",
        "Connection tested: ${yes(connectionTested)}",
        "Ready for automated Council participation: ${yes(ready)}",
    )

    private fun yes(value: Boolean) = if (value) "yes" else "no"
}

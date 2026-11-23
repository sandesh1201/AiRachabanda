package com.airachabanda.app.provider

import com.airachabanda.app.core.model.DeclaredPlanOption
import com.airachabanda.app.core.model.ProviderId

/**
 * User-declared plan names taken from official pages already reviewed.
 * Selecting one does not verify an entitlement.
 */
object PlanCatalog {
    private val chatgptHelp =
        "https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites"
    private val claudeLegal = "https://code.claude.com/docs/en/legal-and-compliance"
    private val claudeLinkedBilling =
        "https://support.claude.com/en/articles/18467148-use-your-claude-platform-account-in-a-third-party-app"

    fun paidPlans(providerId: ProviderId): List<DeclaredPlanOption> = when (providerId) {
        ProviderId.CHATGPT -> listOf(
            DeclaredPlanOption("go", "Go", chatgptHelp),
            DeclaredPlanOption("plus", "Plus", chatgptHelp),
            DeclaredPlanOption("pro", "Pro", chatgptHelp),
            DeclaredPlanOption("business", "Business", chatgptHelp),
        )
        ProviderId.CLAUDE -> listOf(
            DeclaredPlanOption("pro", "Pro", claudeLegal),
            DeclaredPlanOption("max", "Max", claudeLegal),
            DeclaredPlanOption("ultra", "Ultra", claudeLegal),
            DeclaredPlanOption("team", "Team", claudeLinkedBilling),
            DeclaredPlanOption("enterprise", "Enterprise", claudeLinkedBilling),
        )
        else -> emptyList()
    }
}

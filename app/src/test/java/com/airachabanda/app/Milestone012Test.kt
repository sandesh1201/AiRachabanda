package com.airachabanda.app

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.IntegrationMethod
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.ui.ActivityFilter
import com.airachabanda.app.ui.ModeGuide
import com.airachabanda.app.ui.ProviderPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone012Test {
    @Test
    fun modeGuideMatchesThePlanner() {
        assertTrue(ModeGuide.summary(ResearchMode.QUICK).contains("One task runs at a time"))
        assertTrue(ModeGuide.summary(ResearchMode.STANDARD).contains("Up to two"))
        assertTrue(ModeGuide.summary(ResearchMode.DEEP_RESEARCH).contains("Up to three"))
        assertTrue(ModeGuide.customDetail().contains("round limit"))
        assertTrue(ModeGuide.customDetail().contains("Zero-cost"))
    }

    @Test
    fun providerStatusUsesObservedState() {
        val chat = config(ProviderId.CHATGPT, ParticipationMode.ASSISTED, BillingEligibility.UNKNOWN, false)
        assertEquals("Manual access only", ProviderPresentation.status(chat))
        assertEquals("Disabled", ProviderPresentation.status(chat, enabled = false))

        val api = config(ProviderId.OPENROUTER, ParticipationMode.UNAVAILABLE, BillingEligibility.UNKNOWN, false)
        assertEquals("Not configured", ProviderPresentation.status(api))
        assertEquals("Automated API", ProviderPresentation.kind(api))

        val ready = config(ProviderId.GROQ, ParticipationMode.AUTOMATED, BillingEligibility.VERIFIED_FREE, true, AccountChoice.FREE_ACCOUNT)
        assertEquals("Ready", ProviderPresentation.status(ready))

        val blocked = config(ProviderId.GROQ, ParticipationMode.UNAVAILABLE, BillingEligibility.BLOCKED, false, AccountChoice.FREE_ACCOUNT)
        assertEquals("Billing blocked", ProviderPresentation.status(blocked))

        val exhausted = config(ProviderId.GROQ, ParticipationMode.AUTOMATED, BillingEligibility.VERIFIED_FREE, true, AccountChoice.FREE_ACCOUNT)
        assertEquals("Free quota exhausted", ProviderPresentation.status(exhausted, quotaExhausted = true))
        val gemini = config(ProviderId.GEMINI, ParticipationMode.UNAVAILABLE, BillingEligibility.UNKNOWN, false)
        assertEquals("Manual access only", ProviderPresentation.status(gemini))
    }

    @Test
    fun activityFilterKeepsRetrievedSeparateFromVerified() {
        assertTrue(ActivityFilter.matches("RESEARCH_RETRIEVED", "Research"))
        assertTrue(ActivityFilter.matches("DISCUSSION_PAUSED", "Controls"))
        assertEquals(
            "https://en.wikipedia.org/wiki/Gravity",
            ActivityFilter.sourceUrl("Retrieved Gravity. Verification RETRIEVED_NOT_ASSESSED. Source https://en.wikipedia.org/wiki/Gravity"),
        )
        assertEquals("Paused", ProviderPresentation.discussionStatus("PAUSED"))
        assertEquals("No discussion yet", ProviderPresentation.discussionStatus(null))
    }

    private fun config(
        id: ProviderId,
        mode: ParticipationMode,
        billing: BillingEligibility,
        automated: Boolean,
        account: AccountChoice = AccountChoice.NOT_CONFIGURED,
    ) = ProviderConfig(
        providerId = id,
        accountChoice = account,
        declaredPlanId = null,
        billingEligibility = billing,
        participationMode = mode,
        integrationMethod = IntegrationMethod.NONE,
        automatedInferenceEnabled = automated,
        updatedAtEpochMs = 0,
    )
}

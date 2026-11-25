package com.airachabanda.app

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.IntegrationMethod
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.ZeroCostPolicy
import com.airachabanda.app.provider.billing.CloudflareBillingPolicy
import com.airachabanda.app.provider.billing.GeminiBillingPolicy
import com.airachabanda.app.provider.gemini.GeminiFreeCatalog
import com.airachabanda.app.provider.gemini.GeminiLiveGate
import com.airachabanda.app.provider.gemini.GeminiProjectBinding
import com.airachabanda.app.ui.ProviderPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015Test {
    @Test
    fun providerStatesNameTheBlockerWithoutShowingASecret() {
        val stored = config(ProviderId.OPENROUTER, BillingEligibility.UNKNOWN, AccountChoice.FREE_ACCOUNT)
        assertEquals("Reconnect required", ProviderPresentation.status(stored, credentialStored = true))
        assertTrue(ProviderPresentation.blocker("Reconnect required").contains("incomplete") || ProviderPresentation.blocker("Reconnect required").contains("expired"))
        assertEquals(
            "Reconnect required",
            ProviderPresentation.status(stored, credentialStored = true, authenticationRequired = true),
        )
        val eligible = config(ProviderId.GEMINI, BillingEligibility.VERIFIED_FREE, AccountChoice.FREE_ACCOUNT, automated = false)
        assertEquals("Free eligibility verified", ProviderPresentation.status(eligible))
        assertFalse(ProviderPresentation.blocker("Credential stored").contains("sk-"))
    }

    @Test
    fun geminiInferenceStaysBlockedUntilConsentBindingAndUnlinkedBilling() {
        val blocked = GeminiLiveGate.inference(gate(inferenceConsent = false))
        assertFalse(blocked.permitted)
        assertTrue(blocked.reason.contains("Inference consent"))
        val unbound = GeminiLiveGate.inference(gate(inferenceConsent = true, bound = false))
        assertFalse(unbound.permitted)
        val ready = GeminiLiveGate.inference(gate(inferenceConsent = true, bound = true, unlinked = true))
        assertTrue(ready.permitted)
        assertEquals("gemini-3.5-flash-lite", GeminiLiveGate.FREE_MODEL)
        assertTrue(GeminiFreeCatalog.eligible("gemini-3.5-flash-lite"))
        assertTrue(GeminiFreeCatalog.eligible("gemini-3.1-flash-lite"))
        assertFalse(GeminiFreeCatalog.eligible("gemini-3.8-flash"))
        val decision = GeminiBillingPolicy.assess(
            billingLinked = false,
            freeTierKnown = true,
            paidModel = false,
            toolsOrGrounding = false,
            quotaExhausted = false,
            sessionBudgetRemaining = 1,
            credentialBoundToProject = true,
        )
        assertTrue(decision.accepted)
        assertTrue(decision.liveCallPermitted)
    }

    @Test
    fun cloudflareAndConsumerApisDoNotBecomeAutomated() {
        val workers = CloudflareBillingPolicy.assess(
            subscriptionsRead = true,
            paidWorkersPlanListed = false,
            modelId = "@cf/google/gemma-4-26b-a4b-it",
            textGeneration = true,
            quotaExhausted = false,
            sessionBudgetRemaining = 1,
            gatewayRequested = false,
            positiveFreePlan = false,
        )
        assertFalse(workers.accepted)
        assertFalse(workers.liveCallPermitted)
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CHATGPT, BillingEligibility.VERIFIED_FREE))
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CLAUDE, BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED))
        assertTrue(GeminiLiveGate.grade("17 multiplied by 23 is 391.").contains("contains 391"))
        assertTrue(GeminiLiveGate.grade("The answer is 392.").contains("does not contain 391"))
    }

    private fun gate(
        inferenceConsent: Boolean,
        bound: Boolean = true,
        unlinked: Boolean = false,
    ) = GeminiLiveGate.Input(
        metadataConsent = true,
        inferenceConsent = inferenceConsent,
        oauthState = GeminiLiveGate.OauthState.READY,
        binding = GeminiProjectBinding.Binding(
            projectId = "fixture",
            credentialBoundToProject = bound,
            billingUnlinked = unlinked,
            sendPermitted = bound && unlinked,
            reason = "fixture",
        ),
        modelId = GeminiFreeCatalog.PREFERRED,
        freeTierKnown = true,
        sessionBudgetRemaining = 1,
        quotaExhausted = false,
    )

    private fun config(
        id: ProviderId,
        billing: BillingEligibility,
        account: AccountChoice,
        automated: Boolean = false,
    ) = ProviderConfig(
        providerId = id,
        accountChoice = account,
        declaredPlanId = null,
        billingEligibility = billing,
        participationMode = if (automated) ParticipationMode.AUTOMATED else ParticipationMode.UNAVAILABLE,
        integrationMethod = IntegrationMethod.API_KEY_PENDING_VERIFICATION,
        automatedInferenceEnabled = automated,
        updatedAtEpochMs = 0,
    )
}

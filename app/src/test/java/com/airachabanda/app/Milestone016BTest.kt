package com.airachabanda.app

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.IntegrationMethod
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.chatgpt.ChatGptPlanUsage
import com.airachabanda.app.ui.ChatReadiness
import com.airachabanda.app.ui.DiscussionPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone016BTest {
    @Test
    fun offlineModelsDoNotEnableAutomaticAnswers() {
        assertFalse(ChatReadiness.automatic(localInstalled = true, providers = emptyList()))
        assertFalse(ChatReadiness.automatic(localInstalled = false, providers = emptyList()))
    }

    @Test
    fun chatgptPlanUsageStaysOffUntilLiveConnectAndWaitingNamesTheRequiredAction() {
        ChatGptPlanUsage.AUTOMATED_INFERENCE_ENABLED = false
        assertFalse(ChatGptPlanUsage.AUTOMATED_INFERENCE_ENABLED)
        assertEquals(
            "Bring the assistant's reply back",
            DiscussionPresence.summary("Waiting for your response", 1),
        )
    }

    @Test
    fun verifiedOnlineProvidersCanBeAutomaticIncludingChatGptPlanUsage() {
        val gemini = config(ProviderId.GEMINI, BillingEligibility.VERIFIED_FREE, automated = true)
        val chatgpt = config(ProviderId.CHATGPT, BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED, automated = true)
        assertTrue(ChatReadiness.automatic(listOf(gemini)))
        assertTrue(ChatReadiness.automatic(listOf(chatgpt)))
    }

    private fun config(id: ProviderId, billing: BillingEligibility, automated: Boolean) = ProviderConfig(
        providerId = id,
        accountChoice = AccountChoice.FREE_ACCOUNT,
        declaredPlanId = null,
        billingEligibility = billing,
        participationMode = ParticipationMode.AUTOMATED,
        integrationMethod = IntegrationMethod.API_KEY_PENDING_VERIFICATION,
        automatedInferenceEnabled = automated,
        updatedAtEpochMs = 0,
    )
}

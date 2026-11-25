package com.airachabanda.app

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.IntegrationMethod
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.chatgpt.ChatGptPlanUsage
import com.airachabanda.app.ui.ChatReadiness
import com.airachabanda.app.ui.ConnectionPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone016CTest {
    @Test
    fun websiteLoginIsNotConnectedAndAssistedIsOffByDefault() {
        assertTrue(ProviderAccess.defaultEnabled.isEmpty())
        ChatGptPlanUsage.AUTOMATED_INFERENCE_ENABLED = false
        assertFalse(ChatGptPlanUsage.AUTOMATED_INFERENCE_ENABLED)
        assertTrue(
            ChatGptPlanUsage.disabledReason().contains("chatgpt.com") ||
                ChatGptPlanUsage.disabledReason().contains("127.0.0.1"),
        )
        val claude = ProviderConfig(
            providerId = ProviderId.CLAUDE,
            accountChoice = AccountChoice.FREE_ACCOUNT,
            declaredPlanId = null,
            billingEligibility = BillingEligibility.UNKNOWN,
            participationMode = ParticipationMode.UNAVAILABLE,
            integrationMethod = IntegrationMethod.ASSISTED_OFFICIAL_INTERFACE,
            automatedInferenceEnabled = false,
            updatedAtEpochMs = 0,
        )
        assertEquals(ConnectionPresentation.UNAVAILABLE, ConnectionPresentation.state(claude, enabled = true))
        assertTrue(
            ConnectionPresentation.explanation(ConnectionPresentation.UNAVAILABLE, ProviderId.CLAUDE)
                .contains("zero-additional-cost"),
        )
        assertFalse(ChatReadiness.automatic(providers = listOf(claude)))
    }
}

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
    fun websiteLoginIsNotReadyAndAssistedIsOffByDefault() {
        assertTrue(ProviderAccess.defaultEnabled.isEmpty())
        assertFalse(ChatGptPlanUsage.AUTOMATED_INFERENCE_ENABLED)
        assertTrue(ChatGptPlanUsage.disabledReason().contains("chatgpt.com") || ChatGptPlanUsage.disabledReason().contains("loopback"))
        val claude = ProviderConfig(
            providerId = ProviderId.CLAUDE,
            accountChoice = AccountChoice.FREE_ACCOUNT,
            declaredPlanId = null,
            billingEligibility = BillingEligibility.UNKNOWN,
            participationMode = ParticipationMode.ASSISTED,
            integrationMethod = IntegrationMethod.ASSISTED_OFFICIAL_INTERFACE,
            automatedInferenceEnabled = false,
            updatedAtEpochMs = 0,
        )
        assertEquals(ConnectionPresentation.ASSISTED_CHAT, ConnectionPresentation.state(claude, enabled = true))
        assertEquals(
            "Open website shows the provider's own chat. Signing in there does not connect this app.",
            ConnectionPresentation.explanation(ConnectionPresentation.ASSISTED_CHAT, ProviderId.CLAUDE),
        )
        assertFalse(ChatReadiness.automatic(localInstalled = false, providers = listOf(claude)))
    }
}

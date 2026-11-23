package com.airachabanda.app

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.ImportRules
import com.airachabanda.app.orchestration.ZeroCostPolicy
import com.airachabanda.app.provider.OpenRouterRoutePolicy
import com.airachabanda.app.provider.chatgpt.ChatGptPlanUsage
import com.airachabanda.app.security.RedactedLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test
    fun zeroCostBlocksUnknownPaidAndUnverifiedSubscriptionStates() {
        val blocked = listOf(
            BillingEligibility.UNKNOWN,
            BillingEligibility.PAID,
            BillingEligibility.BLOCKED,
            BillingEligibility.USER_CONFIGURATION_REQUIRED,
        )
        blocked.forEach { billing ->
            assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.GEMINI, billing))
        }
    }

    @Test
    fun verifiedFreeCanBeEligibleExceptMandatoryAssistedProviders() {
        assertTrue(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.GROQ, BillingEligibility.VERIFIED_FREE))
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CHATGPT, BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED))
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CLAUDE, BillingEligibility.VERIFIED_FREE))
        assertTrue(ZeroCostPolicy.MANDATORY)
    }

    @Test
    fun openRouterRejectsPaidFallbacksAndUnprovenZeroLimit() {
        val paidFallback = OpenRouterRoutePolicy.evaluate("openrouter/free", listOf("openai/gpt-4o"), 0.0)
        assertFalse(paidFallback.automatedCallPermitted)
        assertFalse(paidFallback.modelAccepted)

        val unlimited = OpenRouterRoutePolicy.evaluate("google/gemma-4-31b-it:free", emptyList(), null)
        assertFalse(unlimited.limitAccepted)

        val shapedButUnproven = OpenRouterRoutePolicy.evaluate("openrouter/free", emptyList(), 0.0)
        assertTrue(shapedButUnproven.modelAccepted)
        assertTrue(shapedButUnproven.limitAccepted)
        assertFalse(shapedButUnproven.automatedCallPermitted)
        assertFalse(OpenRouterRoutePolicy.PROVEN_BY_LIVE_CHECK)
    }

    @Test
    fun chatGptAuthorizationIsPreparedAndLiveExchangeIsDisabled() {
        val request = ChatGptPlanUsage.prepareAuthorization(loopbackPort = 1455, hostId = "urn:uuid:11111111-1111-4111-8111-111111111111")
        assertTrue(request.authorizationUrl.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertTrue(request.authorizationUrl.contains("client_id=dynamic_agent_client"))
        assertTrue(request.authorizationUrl.contains("agent_name_hint=AI%20Rachabanda"))
        assertTrue(request.authorizationUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))
        assertTrue(request.authorizationUrl.contains("code_challenge_method=S256"))
        assertTrue(request.authorizationUrl.contains("chatgpt.tokens.use.direct"))
        assertFalse(request.authorizationUrl.contains("localhost"))
        assertFalse(request.codeChallenge.contains("="))
        assertFalse(request.codeChallenge.contains("+"))
        assertEquals(false, ChatGptPlanUsage.AUTOMATED_INFERENCE_ENABLED)
        val failure = runCatching { ChatGptPlanUsage.assertLiveExchangeDisabled() }
        assertTrue(failure.isFailure)
    }

    @Test
    fun assistedImportRejectsBlankTextAndUnsupportedProviders() {
        assertFalse(ImportRules.check(ProviderId.CLAUDE, "  ", 1).accepted)
        assertFalse(ImportRules.check(ProviderId.GROQ, "A response", 1).accepted)
        assertTrue(ImportRules.check(ProviderId.CHATGPT, "A response", 1).accepted)
    }

    @Test
    fun logsRedactCredentials() {
        val cleaned = RedactedLog.redact("Authorization: Bearer sk-testsecretvalue and api_key=abcd1234")
        assertFalse(cleaned.contains("sk-testsecretvalue"))
        assertFalse(cleaned.contains("abcd1234"))
    }
}

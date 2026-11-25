package com.airachabanda.app

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.orchestration.CritiquePrompt
import com.airachabanda.app.orchestration.DevilAdvocatePolicy
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
        assertTrue(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CHATGPT, BillingEligibility.INCLUDED_SUBSCRIPTION_VERIFIED))
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CHATGPT, BillingEligibility.VERIFIED_FREE))
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.CLAUDE, BillingEligibility.VERIFIED_FREE))
        assertFalse(ZeroCostPolicy.automatedInferenceAllowed(ProviderId.LOCAL, BillingEligibility.VERIFIED_FREE))
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
    fun chatGptAuthorizationIsPreparedForLoopbackPkce() {
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
        assertFalse(ImportRules.check(ProviderId.CHATGPT, "A response", 1).accepted)
        assertTrue(ImportRules.check(ProviderId.GEMINI, "A response", 1).accepted)
    }

    @Test
    fun logsRedactCredentials() {
        val cleaned = RedactedLog.redact("Authorization: Bearer sk-testsecretvalue and api_key=abcd1234")
        assertFalse(cleaned.contains("sk-testsecretvalue"))
        assertFalse(cleaned.contains("abcd1234"))
    }

    @Test
    fun devilAdvocatePreparesAnAssistedCritiqueAndDoesNotAuthorizeAutomation() {
        val empty = DevilAdvocatePolicy.assess(ResearchMode.STANDARD, emptyList(), ProviderId.CLAUDE, 3, 5)
        assertFalse(empty.assistedReviewPermitted)
        assertFalse(empty.automatedReviewPermitted)

        val message = ChatMessage(
            id = "m1",
            conversationId = "c1",
            providerId = ProviderId.CHATGPT,
            source = MessageSource.USER_SUPPLIED_PROVIDER_ATTRIBUTED,
            body = "The capital of France is Lyon.",
            roundNumber = 1,
            createdAtEpochMs = 0,
        )
        val standard = DevilAdvocatePolicy.assess(ResearchMode.STANDARD, listOf(message), ProviderId.CLAUDE, 3, 5)
        assertTrue(standard.assistedReviewPermitted)
        assertFalse(standard.automatedReviewPermitted)
        assertFalse(standard.independentReviewAvailable)
        assertEquals(1, standard.plannedRounds)
        assertFalse(DevilAdvocatePolicy.PAID_INFERENCE_PERMITTED)

        val deep = DevilAdvocatePolicy.assess(ResearchMode.DEEP_RESEARCH, listOf(message), ProviderId.CLAUDE, 3, 0)
        assertEquals(3, deep.plannedRounds)
        assertFalse(deep.automatedReviewPermitted)

        val customOff = DevilAdvocatePolicy.assess(ResearchMode.CUSTOM, listOf(message), ProviderId.CLAUDE, 0, 5)
        assertFalse(customOff.assistedReviewPermitted)
        assertTrue(customOff.disclosure.contains("reduced"))

        val prompt = CritiquePrompt.prepare("Claude", "Standard", 2, listOf(message), standard.disclosure)
        assertTrue(prompt.contains("Devil's Advocate"))
        assertTrue(prompt.contains("Discussion round: 2"))
        assertTrue(prompt.contains("The capital of France is Lyon."))
        assertTrue(prompt.contains("Do not invent evidence"))
        assertTrue(prompt.contains("not independent verification"))
        assertTrue(prompt.contains("Do not assign a confidence percentage"))
    }
}

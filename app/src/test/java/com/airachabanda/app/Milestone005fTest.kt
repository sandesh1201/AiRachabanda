package com.airachabanda.app

import com.airachabanda.app.provider.gemini.GeminiAdapter
import com.airachabanda.app.provider.gemini.GeminiLiveGate
import com.airachabanda.app.provider.gemini.GeminiProjectBinding
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpResult
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import com.airachabanda.app.security.RedactedLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone005fTest {
    private val bound = GeminiProjectBinding.assess(
        GeminiProjectBinding.KeyLookup("projects/studio-free", "projects/studio-free/locations/global/keys/1"),
        GeminiProjectBinding.ProjectBillingRead("studio-free", false, ""),
        typedProjectId = null,
    )

    private fun ready(binding: GeminiProjectBinding.Binding = bound) = GeminiLiveGate.Input(
        metadataConsent = true,
        inferenceConsent = true,
        oauthState = GeminiLiveGate.OauthState.READY,
        binding = binding,
        modelId = GeminiLiveGate.FREE_MODEL,
        freeTierKnown = true,
        sessionBudgetRemaining = 1,
        quotaExhausted = false,
    )

    @Test
    fun billingDisabledBindingCanPassAndBillingEnabledCannot() {
        assertTrue(bound.billingUnlinked)
        assertTrue(GeminiLiveGate.inference(ready()).permitted)
        val paid = GeminiProjectBinding.assess(
            GeminiProjectBinding.KeyLookup("projects/studio-free", "projects/studio-free/locations/global/keys/1"),
            GeminiProjectBinding.ProjectBillingRead("studio-free", true, "billingAccounts/paid"),
            typedProjectId = null,
        )
        assertFalse(paid.billingUnlinked)
        assertFalse(GeminiLiveGate.inference(ready(paid)).permitted)
    }

    @Test
    fun mismatchedProjectExpiredOauthAndMissingPermissionStayClosed() {
        val mismatch = GeminiProjectBinding.assess(
            GeminiProjectBinding.KeyLookup("projects/studio-free", "projects/studio-free/locations/global/keys/1"),
            GeminiProjectBinding.ProjectBillingRead("other-project", false, ""),
            typedProjectId = "other-project",
        )
        assertFalse(mismatch.credentialBoundToProject)
        assertFalse(GeminiLiveGate.inference(ready(mismatch)).permitted)
        assertFalse(GeminiLiveGate.metadata(ready().copy(oauthState = GeminiLiveGate.OauthState.EXPIRED)).permitted)
        assertFalse(GeminiLiveGate.metadata(ready().copy(oauthState = GeminiLiveGate.OauthState.PERMISSION_DENIED)).permitted)
        assertFalse(GeminiLiveGate.metadata(ready().copy(oauthState = GeminiLiveGate.OauthState.MISSING)).permitted)
    }

    @Test
    fun unknownPricingMissingConsentAndBudgetBlockTheSingleRequest() {
        assertFalse(GeminiLiveGate.inference(ready().copy(freeTierKnown = false)).permitted)
        assertFalse(GeminiLiveGate.inference(ready().copy(modelId = "gemini-unknown")).permitted)
        assertFalse(GeminiLiveGate.inference(ready().copy(modelId = "gemini-2.5-flash-lite")).permitted)
        assertTrue(GeminiLiveGate.inference(ready().copy(modelId = "gemini-3.1-flash-lite")).permitted)
        assertFalse(GeminiLiveGate.metadata(ready().copy(metadataConsent = false)).permitted)
        assertFalse(GeminiLiveGate.metadata(ready().copy(oauthState = GeminiLiveGate.OauthState.CANCELLED)).permitted)
        assertFalse(GeminiLiveGate.inference(ready().copy(inferenceConsent = false)).permitted)
        assertFalse(GeminiLiveGate.inference(ready().copy(sessionBudgetRemaining = 0)).permitted)
        assertFalse(GeminiLiveGate.inference(ready().copy(quotaExhausted = true)).permitted)
    }

    @Test
    fun preparedRequestHasNoToolsAndIsNotSent() {
        val adapter = GeminiAdapter(object : HttpTransport {
            override suspend fun exchange(request: HttpExchange): HttpResult = error("not called")
        })
        val outcome = adapter.prepareFirstInference(ready())
        assertEquals(OpenRouterFailure.POLICY, outcome.failure)
        assertTrue(outcome.reason.contains("prepared"))
        assertEquals(128, GeminiLiveGate.MAX_OUTPUT_TOKENS)
        assertFalse(outcome.request?.url.orEmpty().contains("key"))
        assertFalse(GeminiLiveGate.PROMPT.contains("391"))
    }

    @Test
    fun streamCompletionTruncationAndUnknownOutcomeStayDistinct() {
        assertEquals("The returned text contains 391.", GeminiLiveGate.grade("17 times 23 is 391."))
        assertEquals("The returned text does not contain 391.", GeminiLiveGate.grade("The result is 392."))
        val adapter = GeminiAdapter(object : HttpTransport {
            override suspend fun exchange(request: HttpExchange): HttpResult = error("not called")
        })
        assertEquals(ResponseDisposition.OUTCOME_UNKNOWN, adapter.parseStream("example-text", "").disposition)
    }

    @Test
    fun logsRemoveTheKeyTokenAndLookupQuery() {
        val cleaned = RedactedLog.redact(
            "lookup https://apikeys.googleapis.com/v2/keys:lookupKey?keyString=AIzaSySyntheticKeyValue000000000 access_token=ya29.secret",
        )
        assertFalse(cleaned.contains("AIza"))
        assertFalse(cleaned.contains("keyString=AIza"))
        assertFalse(cleaned.contains("ya29"))
    }

    @Test
    fun offlineLocalInferenceIsUnavailable() {
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(ProviderAccessMode.UNAVAILABLE))
    }
}

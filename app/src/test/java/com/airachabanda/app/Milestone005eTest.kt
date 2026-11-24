package com.airachabanda.app

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.ImportRules
import com.airachabanda.app.orchestration.ReviewClassification
import com.airachabanda.app.orchestration.ReviewIndependence
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.provider.billing.GeminiBillingPolicy
import com.airachabanda.app.provider.cloudflare.CloudflarePlanSignal
import com.airachabanda.app.provider.gemini.GeminiProjectBinding
import com.airachabanda.app.provider.local.LocalInferencePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone005eTest {
    @Test
    fun geminiLookupBindsTheKeyAndRejectsATypedProject() {
        val lookup = GeminiProjectBinding.KeyLookup("projects/studio-free", "projects/studio-free/locations/global/keys/1")
        val billing = GeminiProjectBinding.ProjectBillingRead("studio-free", billingEnabled = false, billingAccountName = "")
        val bound = GeminiProjectBinding.assess(lookup, billing, typedProjectId = "someone-else")
        assertFalse(bound.credentialBoundToProject)
        assertFalse(bound.sendPermitted)
        assertTrue(bound.reason.contains("typed project"))

        val matched = GeminiProjectBinding.assess(lookup, billing, typedProjectId = null)
        assertTrue(matched.credentialBoundToProject)
        assertTrue(matched.billingUnlinked)
        assertFalse(matched.sendPermitted)
        val decision = GeminiBillingPolicy.assess(
            billingLinked = false,
            freeTierKnown = true,
            paidModel = false,
            toolsOrGrounding = false,
            quotaExhausted = false,
            sessionBudgetRemaining = 1,
            credentialBoundToProject = matched.credentialBoundToProject,
        )
        assertTrue(decision.accepted)
        assertFalse(decision.liveCallPermitted)
        assertTrue(GeminiProjectBinding.LOOKUP_URL.startsWith("https://apikeys.googleapis.com/"))
        assertFalse(GeminiProjectBinding.billingUrl("studio-free").contains("key"))
    }

    @Test
    fun cloudflareProductPlanAndEntitlementsDoNotNameWorkersFree() {
        assertFalse(CloudflarePlanSignal.workersFreeNamed(emptySet(), emptySet()))
        assertFalse(CloudflarePlanSignal.workersFreeNamed(setOf("free"), emptySet()))
        assertFalse(CloudflarePlanSignal.workersFreeNamed(setOf("partners_free"), emptySet()))
        assertFalse(CloudflarePlanSignal.workersFreeNamed(emptySet(), setOf("workers.ai")))
        assertFalse(CloudflarePlanSignal.workersFreeNamed(setOf("WORKERS_PAID"), emptySet()))
        assertTrue(CloudflarePlanSignal.reason(setOf("free"), emptySet()).contains("not the Workers Free"))
    }

    @Test
    fun localSelectionRejectsCloudAndPublicEndpoints() {
        val ready = LocalInferencePolicy.Selection(
            modelId = LocalInferencePolicy.FIRST_MODEL,
            endpointHost = "127.0.0.1",
            cloudDisabled = true,
            contextTokens = 2048,
            timeoutMs = 30_000,
            budgetRemaining = 1,
            runtimeInstalled = true,
        )
        val prepared = LocalInferencePolicy.prepare(ready)
        assertTrue(prepared.accepted)
        assertFalse(prepared.requestPermitted)
        assertEquals(LocalInferencePolicy.FIRST_MODEL, prepared.modelId)

        assertFalse(LocalInferencePolicy.prepare(ready.copy(modelId = "gemma3:cloud")).accepted)
        assertFalse(LocalInferencePolicy.prepare(ready.copy(endpointHost = "0.0.0.0")).accepted)
        assertFalse(LocalInferencePolicy.prepare(ready.copy(cloudDisabled = false)).accepted)
        assertFalse(LocalInferencePolicy.prepare(ready.copy(runtimeInstalled = false)).accepted)
        assertFalse(LocalInferencePolicy.prepare(ready.copy(contextTokens = 128_000)).accepted)
        assertFalse(ImportRules.check(ProviderId.LOCAL, "A pasted response", 1).accepted)
    }

    @Test
    fun twoLocalModelsCanBeCrossCheckedWithoutInventingAnswers() {
        val gemma = EligibleParticipant(
            ProviderId.LOCAL,
            assisted = false,
            billing = BillingEligibility.UNKNOWN,
            verifiedModelId = "gemma3:1b",
            participantKey = "LOCAL:gemma3:1b",
        )
        val llama = gemma.copy(verifiedModelId = "llama3.2:3b", participantKey = "LOCAL:llama3.2:3b")
        val assessment = ReviewIndependence.summarize(llama, listOf(gemma))
        assertEquals(ReviewClassification.CROSS_MODEL_REVIEW, assessment.classification)
        assertTrue(assessment.disclosure.contains("not proof"))
        val unknown = ReviewIndependence.classify("LOCAL:gemma3:1b", "unknown", "LOCAL:llama3.2:3b", "llama3.2:3b")
        assertEquals(ReviewClassification.INDEPENDENCE_UNVERIFIED, unknown)
    }
}

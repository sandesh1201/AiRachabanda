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
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode
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
        assertTrue(decision.liveCallPermitted)
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
    fun localProviderIsUnavailableAfterOfflineRemoval() {
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(ProviderAccessMode.UNAVAILABLE))
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

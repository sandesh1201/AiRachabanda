package com.airachabanda.app

import com.airachabanda.app.provider.gemini.CertificateFingerprint
import com.airachabanda.app.provider.gemini.GeminiFreeCatalog
import com.airachabanda.app.provider.gemini.GeminiLiveGate
import com.airachabanda.app.provider.gemini.GeminiOwnerSession
import com.airachabanda.app.provider.gemini.GeminiProjectBinding
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpResult
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Milestone005gTest {
    private val key = "test-key"
    private val token = "ya29.test-token"

    @Test
    fun cancelledOrMissingAuthorizationDoesNotCallGoogle() {
        val transport = RecordingTransport(emptyList())
        val session = GeminiOwnerSession(transport)
        val missing = runBlocking { session.verifyMetadata(key, "", metadataConsent = true) }
        val cancelled = GeminiLiveGate.metadata(
            GeminiLiveGate.Input(
                metadataConsent = true,
                inferenceConsent = false,
                oauthState = GeminiLiveGate.OauthState.CANCELLED,
                binding = GeminiProjectBinding.assess(null, null, null),
                modelId = "",
                freeTierKnown = false,
                sessionBudgetRemaining = 1,
                quotaExhausted = false,
            ),
        )
        val noConsent = runBlocking { session.verifyMetadata(key, token, metadataConsent = false) }
        assertNull(missing.record)
        assertFalse(cancelled.permitted)
        assertNull(noConsent.record)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun expiredAuthorizationAndMissingPermissionStopBeforeBilling() = runBlocking {
        val expiredTransport = RecordingTransport(listOf(http(401, "{}")))
        val deniedTransport = RecordingTransport(listOf(http(403, "{}")))
        val expired = GeminiOwnerSession(expiredTransport).verifyMetadata(key, token, true)
        val denied = GeminiOwnerSession(deniedTransport).verifyMetadata(key, token, true)
        assertTrue(expired.summary.contains("expired"))
        assertTrue(denied.summary.contains("apikeys.keys.lookup"))
        assertEquals(1, expiredTransport.requests.size)
        assertEquals(1, deniedTransport.requests.size)
        assertFalse(expiredTransport.requests.single().url.contains("cloudbilling"))
    }

    @Test
    fun projectMismatchAndEnabledBillingAreRejected() = runBlocking {
        val mismatch = GeminiOwnerSession(
            RecordingTransport(
                listOf(
                    http(200, """{"parent":"projects/111","name":"projects/111/locations/global/keys/1"}"""),
                    http(200, """{"name":"projects/other/billingInfo","projectId":"other","billingEnabled":false}"""),
                    http(200, """{"projectNumber":"999","projectId":"other"}"""),
                ),
            ),
        )
        val mismatchReport = mismatch.verifyMetadata(key, token, true)
        assertNull(mismatchReport.record)
        assertTrue(mismatchReport.summary.contains("different project"))

        val paidTransport = RecordingTransport(
            listOf(
                http(200, """{"parent":"projects/studio-free","name":"projects/studio-free/locations/global/keys/1"}"""),
                http(200, """{"name":"projects/studio-free/billingInfo","projectId":"studio-free","billingEnabled":true}"""),
            ),
        )
        val paid = GeminiOwnerSession(paidTransport).verifyMetadata(key, token, true)
        assertNull(paid.record)
        assertTrue(paid.summary.contains("billing enabled"))
        assertFalse(paidTransport.requests.any { it.url.contains("/models") })

        val unknown = GeminiOwnerSession(
            RecordingTransport(
                listOf(
                    http(200, """{"parent":"projects/studio-free","name":"projects/studio-free/locations/global/keys/1"}"""),
                    http(200, """{"name":"projects/studio-free/billingInfo","projectId":"studio-free"}"""),
                ),
            ),
        ).verifyMetadata(key, token, true)
        assertNull(unknown.record)
        assertTrue(unknown.summary.contains("whether billing is enabled"))
    }

    @Test
    fun discoverySelectsADocumentedFreeModelAndRejectsPaidModels() = runBlocking {
        val transport = RecordingTransport(
            listOf(
                http(200, """{"parent":"projects/111","name":"projects/111/locations/global/keys/1"}"""),
                http(200, """{"name":"projects/studio-free/billingInfo","projectId":"studio-free","billingEnabled":false}"""),
                http(200, """{"projectNumber":"111","projectId":"studio-free"}"""),
                http(200, """{"models":[{"name":"models/gemini-4-carbon","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}"""),
            ),
        )
        val report = GeminiOwnerSession(transport).verifyMetadata(key, token, true)
        assertEquals(GeminiFreeCatalog.PREFERRED, report.record?.modelId)
        assertFalse(GeminiFreeCatalog.eligible("gemini-4-carbon"))
        assertFalse(GeminiFreeCatalog.eligible("gemini-2.5-flash-lite"))
        assertTrue(report.summary.contains("billingEnabled is false"))
        assertFalse(report.summary.contains(key))
        assertFalse(report.summary.contains(token))
        assertFalse(report.summary.contains("keyString="))
    }

    @Test
    fun oneInferenceIsSentOnlyAfterRevalidatedBilling() = runBlocking {
        val record = record()
        val transport = RecordingTransport(
            listOf(
                http(200, """{"name":"projects/studio-free/billingInfo","projectId":"studio-free","billingEnabled":false}"""),
                http(
                    200,
                    """data: {"candidates":[{"content":{"parts":[{"text":"17 times 23 is 391."}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":8}}""",
                ),
            ),
        )
        val session = GeminiOwnerSession(transport)
        val blocked = session.infer(key, token, record, inferenceConsent = false, budgetRemaining = 1)
        assertFalse(blocked.sent)
        assertTrue(transport.requests.isEmpty())
        val changed = session.infer("other-key", token, record, inferenceConsent = true, budgetRemaining = 1)
        assertFalse(changed.sent)
        assertTrue(changed.summary.contains("changed"))
        val result = session.infer(key, token, record, inferenceConsent = true, budgetRemaining = 1)
        assertTrue(result.sent)
        assertEquals(ResponseDisposition.COMPLETED, result.disposition)
        assertEquals("17 times 23 is 391.", result.text)
        assertEquals("The returned text contains 391.", result.grade)
        assertEquals(12, result.promptTokens)
        assertEquals(8, result.outputTokens)
        assertEquals(0, result.budgetRemaining)
        assertEquals(GeminiFreeCatalog.PREFERRED, result.modelId)
        assertTrue(result.summary.contains("billingEnabled false"))
        assertFalse(result.summary.contains(key))
        assertFalse(result.summary.contains("ya29"))
        val post = transport.requests.last()
        assertTrue(post.url.contains("gemini-3.5-flash-lite:streamGenerateContent"))
        assertFalse(post.url.contains("key"))
        assertFalse(post.body.orEmpty().contains("tools"))
        assertFalse(post.body.orEmpty().contains("391"))
        assertTrue(post.body.orEmpty().contains("128"))
        val again = session.infer(key, token, record, inferenceConsent = true, budgetRemaining = result.budgetRemaining)
        assertFalse(again.sent)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun wrongAnswerRateLimitAndEnabledBillingStayHonest() = runBlocking {
        val record = record()
        val wrong = GeminiOwnerSession(
            RecordingTransport(
                listOf(
                    http(200, """{"projectId":"studio-free","billingEnabled":false}"""),
                    http(200, """data: {"candidates":[{"content":{"parts":[{"text":"The result is 392."}]},"finishReason":"STOP"}]}"""),
                ),
            ),
        ).infer(key, token, record, true, 1)
        assertEquals("The returned text does not contain 391.", wrong.grade)
        assertTrue(wrong.sent)

        val limited = GeminiOwnerSession(
            RecordingTransport(listOf(http(200, """{"projectId":"studio-free","billingEnabled":false}"""), http(429, "{}"))),
        ).infer(key, token, record, true, 1)
        assertTrue(limited.summary.contains("rate limited"))
        assertEquals("", limited.text)

        val enabledTransport = RecordingTransport(listOf(http(200, """{"projectId":"studio-free","billingEnabled":true}""")))
        val enabled = GeminiOwnerSession(enabledTransport).infer(key, token, record, true, 1)
        assertFalse(enabled.sent)
        assertEquals(1, enabledTransport.requests.size)
        assertTrue(enabled.summary.contains("Billing is enabled"))
    }

    @Test
    fun signingFingerprintIsFormattedFromTheCertificateBytes() {
        val fingerprint = CertificateFingerprint.sha1(byteArrayOf(0xE0.toByte(), 0x6F, 0x00, 0x4B))
        assertTrue(fingerprint.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){19}")))
    }

    private fun record() = GeminiOwnerSession.Record(
        keyFingerprint = GeminiOwnerSession.fingerprint(key),
        lookupParent = "projects/studio-free",
        projectId = "studio-free",
        billingProjectId = "studio-free",
        modelId = GeminiFreeCatalog.PREFERRED,
    )

    private fun http(status: Int, body: String) = HttpResult(status, emptyMap(), body)

    private class RecordingTransport(private val results: List<HttpResult>) : HttpTransport {
        val requests = mutableListOf<HttpExchange>()
        override suspend fun exchange(request: HttpExchange): HttpResult {
            requests += request
            return results[requests.lastIndex]
        }
    }
}

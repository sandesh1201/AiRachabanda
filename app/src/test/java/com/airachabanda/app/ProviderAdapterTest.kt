package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.ReviewClassification
import com.airachabanda.app.orchestration.ReviewIndependence
import com.airachabanda.app.orchestration.RoleAssignment
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.ConversationEntity
import com.airachabanda.app.persistence.ResearchSessionEntity
import com.airachabanda.app.persistence.ResearchTaskEntity
import com.airachabanda.app.provider.billing.CloudflareBillingPolicy
import com.airachabanda.app.provider.billing.GeminiBillingPolicy
import com.airachabanda.app.provider.billing.PlanState
import com.airachabanda.app.provider.billing.VerificationSource
import com.airachabanda.app.provider.cloudflare.CloudflareAdapter
import com.airachabanda.app.provider.cloudflare.CloudflareAuthorization
import com.airachabanda.app.provider.dispatch.PreparedCall
import com.airachabanda.app.provider.dispatch.PreparedProviderDispatch
import com.airachabanda.app.provider.gemini.GeminiAdapter
import com.airachabanda.app.provider.gemini.GeminiAuthorization
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpResult
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import com.airachabanda.app.security.CredentialAccess
import com.airachabanda.app.security.KeystoreCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProviderAdapterTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun credentialsStayIsolatedByProvider() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("credentials").toFile()
        val store = KeystoreCredentialStore(com.airachabanda.app.security.AndroidKeystoreGateway(directory, MemoryKeySource()))
        store.put(ProviderId.CLOUDFLARE.name, "api-token", "synthetic-cloudflare-token".toByteArray())
        store.put(ProviderId.GEMINI.name, "default", "synthetic-gemini-key".toByteArray())
        val cloudflare = store.get(ProviderId.CLOUDFLARE.name, "api-token") as CredentialAccess.Available
        val gemini = store.get(ProviderId.GEMINI.name, "default") as CredentialAccess.Available
        assertEquals("synthetic-cloudflare-token", cloudflare.secret.toString(Charsets.UTF_8))
        assertEquals("synthetic-gemini-key", gemini.secret.toString(Charsets.UTF_8))
        assertTrue(store.get(ProviderId.CLOUDFLARE.name, "default") is CredentialAccess.Missing)
        store.delete(ProviderId.CLOUDFLARE.name, "api-token")
        assertTrue(store.get(ProviderId.CLOUDFLARE.name, "api-token") is CredentialAccess.Missing)
        assertTrue(store.get(ProviderId.GEMINI.name, "default") is CredentialAccess.Available)
        directory.deleteRecursively()
        return@runBlocking
    }

    @Test
    fun unknownPlanAndPaidModelsAreRejected() {
        val unknown = CloudflareBillingPolicy.assess(false, false, "@cf/example/text", true, false, 1, false)
        assertEquals(PlanState.UNKNOWN, unknown.snapshot.plan)
        assertEquals(VerificationSource.INSUFFICIENT, unknown.snapshot.verificationSource)
        assertFalse(unknown.accepted)
        val paidPlan = CloudflareBillingPolicy.assess(true, true, "@cf/example/text", true, false, 1, false)
        assertEquals(PlanState.PAID, paidPlan.snapshot.plan)
        val paidModel = CloudflareBillingPolicy.assess(true, false, "@cf/moonshotai/kimi-k2.6", true, false, 1, false)
        assertTrue(paidModel.reason.contains("paid"))
        val exhausted = CloudflareBillingPolicy.assess(true, false, "@cf/example/text", true, true, 1, false)
        assertTrue(exhausted.reason.contains("exhausted"))
        val geminiKeyOnly = GeminiBillingPolicy.assess(null, true, false, false, false, 1)
        assertFalse(geminiKeyOnly.accepted)
        assertTrue(geminiKeyOnly.reason.contains("does not prove"))
        val geminiPaid = GeminiBillingPolicy.assess(false, false, true, false, false, 1)
        assertTrue(geminiPaid.reason.contains("not eligible"))
    }

    @Test
    fun productionMetadataAndInferenceDoNotCallTheNetwork() = runBlocking {
        val transport = CountingTransport { error("The production adapter called the transport.") }
        val cloudflare = CloudflareAdapter(transport, CloudflareAuthorization.PRODUCTION)
        val gemini = GeminiAdapter(transport, GeminiAuthorization.PRODUCTION)
        assertEquals(OpenRouterFailure.POLICY, cloudflare.verifyMetadata("a".repeat(32), "synthetic-token").failure)
        assertEquals(OpenRouterFailure.POLICY, gemini.verifyMetadata("synthetic-key").failure)
        val prepared = cloudflare.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true)
        assertEquals(OpenRouterFailure.POLICY, prepared.failure)
        assertTrue(prepared.request?.body?.contains("\"stream\":true") == true)
        assertFalse(prepared.request?.body?.contains("gateway") == true)
        assertEquals(0, transport.calls)
        val geminiRequest = gemini.complete("synthetic-key", "example-text", "Question", 1, billingLinked = false, freeTierKnown = true)
        assertFalse(geminiRequest.request?.body?.contains("tools") == true)
        assertFalse(geminiRequest.request?.url?.contains("key=") == true)
        assertEquals(0, transport.calls)
    }

    @Test
    fun fixturesCoverDiscoveryStreamingFailureAndBudget() = runBlocking {
        val cloudflareModels = CloudflareAdapter(unusedTransport(), CloudflareAuthorization.PRODUCTION).parseModels(
            """{"success":true,"result":[{"name":"@cf/example/text","task":{"name":"Text Generation"}},{"name":"@cf/moonshotai/kimi-k2.6","task":{"name":"Text Generation"}}]}""",
        )
        assertFalse(cloudflareModels[0].paidRequired)
        assertTrue(cloudflareModels[1].paidRequired)
        assertTrue(CloudflareAdapter(unusedTransport()).parseSubscriptions("""{"success":true,"result":[{"rate_plan":{"id":"WORKERS_PAID"}}]}"""))
        val geminiModels = GeminiAdapter(unusedTransport()).parseModels(
            """{"models":[{"name":"models/example-text","displayName":"Example","inputTokenLimit":1024,"outputTokenLimit":128,"supportedGenerationMethods":["generateContent"]}]}""",
        )
        assertFalse(geminiModels.single().freeTierKnown)
        assertEquals(false, GeminiAdapter(unusedTransport()).parseBillingInfo("""{"billingEnabled":false}"""))

        val stream = authorizedCloudflare(
            """data: {"response":"391"}
data: [DONE]
""",
        )
        val completed = stream.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true)
        assertEquals(ResponseDisposition.COMPLETED, completed.disposition)
        assertEquals("391", completed.text)

        val cut = authorizedCloudflare("data: {\"response\":\"partial\"}\n")
        assertEquals(ResponseDisposition.INTERRUPTED, cut.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true).disposition)

        val cancelled = CloudflareAdapter(throwingTransport(CancellationException("cancelled")), CloudflareAuthorization(inferenceAuthorized = true))
        var thrown = false
        try {
            cancelled.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true)
        } catch (expected: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)

        val timed = CloudflareAdapter(throwingTransport(SocketTimeoutException("timed out")), CloudflareAuthorization(inferenceAuthorized = true))
        assertEquals(ResponseDisposition.OUTCOME_UNKNOWN, timed.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true).disposition)

        val denied = authorizedCloudflare("{}", 401)
        assertEquals(OpenRouterFailure.AUTHENTICATION, denied.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true).failure)
        val limited = authorizedCloudflare("{}", 429)
        assertEquals(OpenRouterFailure.RATE_LIMIT, limited.complete("a".repeat(32), "synthetic-token", "@cf/example/text", "Question", 1, subscriptionsRead = true).failure)

        val gemini = GeminiAdapter(
            fixedTransport(
                """data: {"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"MAX_TOKENS"}]}""",
            ),
            GeminiAuthorization(inferenceAuthorized = true),
        )
        val truncated = gemini.complete("synthetic-key", "example-text", "Question", 1, billingLinked = false, freeTierKnown = true)
        assertEquals(ResponseDisposition.TRUNCATED, truncated.disposition)
        assertFalse(truncated.completed)

        val unknown = GeminiAdapter(fixedTransport(""), GeminiAuthorization(inferenceAuthorized = true))
        assertEquals(ResponseDisposition.OUTCOME_UNKNOWN, unknown.complete("synthetic-key", "example-text", "Question", 1, billingLinked = false, freeTierKnown = true).disposition)

        seed(budget = 1, consumed = 0)
        val dispatch = PreparedProviderDispatch(
            cloudflare = authorizedCloudflare("data: {\"response\":\"391\"}\ndata: [DONE]\n"),
            gemini = GeminiAdapter(unusedTransport()),
            council = database.councilDao(),
            discussion = database.discussionDao(),
            clock = { 10L },
            ids = { "message-1" },
        )
        val call = PreparedCall(ProviderId.CLOUDFLARE, "@cf/example/text", "synthetic-token", accountId = "a".repeat(32), subscriptionsRead = true)
        val first = dispatch.dispatch("session", "conversation", "task", 1, "Question", 1, call)
        val second = dispatch.dispatch("session", "conversation", "task", 1, "Question", 1, call)
        assertTrue(first.completed)
        assertEquals(OpenRouterFailure.DUPLICATE, second.failure)
        assertEquals(1, database.discussionDao().session("session")?.requestsConsumed)
        assertNull(database.discussionDao().completion("gemini:example-text"))

        val blocked = engine().dispatchPrepared("session", "task", call)
        assertEquals(OpenRouterFailure.BUDGET_BLOCKED, blocked.failure)
    }

    @Test
    fun distinctProvidersKeepDistinctRolesAndModelIdentity() {
        val people = listOf(
            EligibleParticipant(ProviderId.CLOUDFLARE, assisted = false, billing = BillingEligibility.VERIFIED_FREE, verifiedModelId = "@cf/example/text", participantKey = "CLOUDFLARE:@cf/example/text"),
            EligibleParticipant(ProviderId.GEMINI, assisted = false, billing = BillingEligibility.VERIFIED_FREE, verifiedModelId = "example-text", participantKey = "GEMINI:example-text"),
        )
        val roles = RoleAssignment.assign(people, QuestionComplexity.MODERATE, setOf(RoleAssignment.CRITIQUE))
        assertEquals(2, roles.map { it.participantKey }.distinct().size)
        assertEquals(
            ReviewClassification.CROSS_MODEL_REVIEW,
            ReviewIndependence.classify("CLOUDFLARE:@cf/example/text", "@cf/example/text", "GEMINI:example-text", "example-text"),
        )
        assertEquals(
            ReviewClassification.SAME_MODEL_REVIEW,
            ReviewIndependence.classify("CLOUDFLARE:shared", "shared-model", "GEMINI:shared", "shared-model"),
        )
    }

    private fun engine(): OrchestrationEngine = OrchestrationEngine(
        council = database.councilDao(),
        discussion = database.discussionDao(),
        participants = ParticipantSource { emptyList() },
        prepared = PreparedProviderDispatch(
            CloudflareAdapter(unusedTransport()),
            GeminiAdapter(unusedTransport()),
            database.councilDao(),
            database.discussionDao(),
            { 10L },
            { "unused" },
        ),
    )

    private suspend fun seed(budget: Int, consumed: Int) {
        database.councilDao().insertConversation(ConversationEntity("conversation", "Council", ResearchMode.STANDARD.name, 1L, 1L))
        database.councilDao().insertSession(
            ResearchSessionEntity(
                id = "session",
                conversationId = "conversation",
                researchMode = ResearchMode.STANDARD.name,
                createdAtEpochMs = 1L,
                requestBudget = budget,
                requestsConsumed = consumed,
            ),
        )
        database.councilDao().insertTask(
            ResearchTaskEntity(
                id = "task",
                sessionId = "session",
                conversationId = "conversation",
                providerId = ProviderId.CLOUDFLARE.name,
                roundNumber = 1,
                state = "READY",
                prompt = "Question",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
        )
    }

    private fun authorizedCloudflare(body: String, status: Int = 200) = CloudflareAdapter(
        fixedTransport(body, status),
        CloudflareAuthorization(inferenceAuthorized = true),
    )

    private fun fixedTransport(body: String, status: Int = 200) = object : HttpTransport {
        override suspend fun exchange(request: HttpExchange) = HttpResult(status, emptyMap(), body)
    }

    private fun unusedTransport() = object : HttpTransport {
        override suspend fun exchange(request: HttpExchange): HttpResult = error("This test must not send a request.")
    }

    private fun throwingTransport(error: Exception) = object : HttpTransport {
        override suspend fun exchange(request: HttpExchange): HttpResult = throw error
    }
}

private class CountingTransport(
    private val responder: (HttpExchange) -> HttpResult,
) : HttpTransport {
    var calls = 0
    override suspend fun exchange(request: HttpExchange): HttpResult {
        calls += 1
        return responder(request)
    }
}

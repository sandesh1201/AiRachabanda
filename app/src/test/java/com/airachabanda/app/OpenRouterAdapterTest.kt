package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.ResearchStart
import com.airachabanda.app.orchestration.ReviewClassification
import com.airachabanda.app.orchestration.ReviewIndependence
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.ConversationEntity
import com.airachabanda.app.provider.openrouter.DiscoveredModel
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpResult
import com.airachabanda.app.provider.openrouter.OpenRouterAdapter
import com.airachabanda.app.provider.openrouter.OpenRouterAuthorization
import com.airachabanda.app.provider.openrouter.OpenRouterCandidate
import com.airachabanda.app.provider.openrouter.OpenRouterDispatch
import com.airachabanda.app.provider.openrouter.ConnectionState
import com.airachabanda.app.provider.openrouter.OpenRouterEndpoints
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import com.airachabanda.app.security.RedactedLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OpenRouterAdapterTest {
    private val freePricing = mapOf("prompt" to "0", "completion" to "0", "request" to "0")

    @Test
    fun productionGateDoesNotCallTheTransport() = runBlocking {
        val calls = AtomicInteger()
        val adapter = OpenRouterAdapter(transport = { calls.incrementAndGet(); error("transport") })
        val outcome = adapter.complete("synthetic-key", freeCandidate(), "What does the note say?")
        assertEquals(OpenRouterFailure.POLICY, outcome.failure)
        assertEquals(0, calls.get())
        assertFalse(OpenRouterAuthorization.PRODUCTION.inferenceAuthorized)
        assertFalse(OpenRouterAuthorization.PRODUCTION.metadataChecksAuthorized)
        val body = outcome.request?.body.orEmpty()
        assertTrue(body.contains("\"stream\":true"))
        assertFalse(body.contains("\"models\""))
        assertFalse(body.contains("\"tools\""))
        assertTrue(body.contains("\"allow_fallbacks\":false"))
        assertTrue(body.contains("\"enabled\":false"))
        assertTrue(body.contains("\"id\":\"web\""))
        assertFalse(body.contains("\"tools\""))
        assertFalse(outcome.request?.headers?.get("Authorization")?.let { body.contains(it) } ?: false)
    }

    @Test
    fun rejectsPaidUnknownPricingFallbacksAndMissingQuota() {
        val adapter = OpenRouterAdapter(transport = { error("transport") })
        assertTrue(adapter.assess(freeCandidate(modelId = "openrouter/free")).reason.contains("routing"))
        assertTrue(adapter.assess(freeCandidate(pricing = null)).reason.contains("Unknown pricing"))
        assertTrue(adapter.assess(freeCandidate(pricing = mapOf("prompt" to "0", "completion" to "0.0001"))).reason.contains("non-zero"))
        assertTrue(adapter.assess(freeCandidate(fallbacks = listOf("openai/gpt-4o"))).reason.contains("Fallback"))
        assertTrue(adapter.assess(freeCandidate(dailyRemaining = null)).reason.contains("not sufficient") || adapter.assess(freeCandidate(dailyRemaining = null)).reason.contains("not been observed"))
        assertTrue(adapter.assess(freeCandidate(tools = true)).reason.contains("Tools"))
        assertTrue(adapter.assess(freeCandidate(budget = 0)).reason.contains("budget"))
        assertTrue(adapter.assess(freeCandidate(limit = null)).reason.contains("unknown"))
    }

    @Test
    fun parsesStreamErrorsAndDoesNotRetry() = runBlocking {
        val adapter = OpenRouterAdapter(transport = { error("transport") })
        val complete = adapter.parseStream(stream(done = true))
        assertTrue(complete.completed)
        assertEquals(ResponseDisposition.COMPLETED, complete.disposition)
        assertEquals("gen-test", complete.generationId)
        assertEquals("example/vendor-model:free", complete.modelId)
        assertEquals("Hello", complete.text)
        val partial = adapter.parseStream(stream(done = false))
        assertFalse(partial.completed)
        assertEquals(ResponseDisposition.INTERRUPTED, partial.disposition)
        val truncated = adapter.parseStream(stream(done = true, finish = "length"))
        assertFalse(truncated.completed)
        assertEquals(ResponseDisposition.TRUNCATED, truncated.disposition)
        assertEquals("Hello", truncated.text)
        assertEquals(ResponseDisposition.OUTCOME_UNKNOWN, adapter.parseStream("").disposition)
        assertEquals(ResponseDisposition.OUTCOME_UNKNOWN, adapter.classifyFailure(SocketTimeoutException("timed out")).disposition)
        assertEquals(ResponseDisposition.CANCELLED, adapter.classifyFailure(kotlinx.coroutines.CancellationException("stop")).disposition)
        assertEquals(OpenRouterFailure.MALFORMED, adapter.parseStream("data: {").failure)
        assertEquals(OpenRouterFailure.AUTHENTICATION, adapter.classifyHttp(401, emptyMap()).failure)
        assertEquals(OpenRouterFailure.PAYMENT_REQUIRED, adapter.classifyHttp(402, emptyMap()).failure)
        assertEquals(OpenRouterFailure.BUDGET_BLOCKED, adapter.classifyHttp(403, emptyMap()).failure)
        val limited = adapter.classifyHttp(429, mapOf("retry-after" to "2"))
        assertEquals(OpenRouterFailure.RATE_LIMIT, limited.failure)
        assertTrue(limited.reason.contains("No automatic retry"))
        assertEquals(OpenRouterFailure.TIMEOUT, adapter.classifyFailure(SocketTimeoutException("timed out")).failure)
        assertEquals(OpenRouterFailure.CANCELLED, adapter.classifyFailure(kotlinx.coroutines.CancellationException("stop")).failure)
        assertTrue(adapter.classifyFailure(SocketTimeoutException("timed out")).reason.contains("not retried"))
        val redacted = RedactedLog.redact("authorization: Bearer synthetic-key")
        assertFalse(redacted.contains("synthetic-key"))
    }

    @Test
    fun duplicateCompletionIsNotStoredTwiceAndModelsStaySeparate() = runBlocking {
        val calls = AtomicInteger()
        val adapter = OpenRouterAdapter(
            transport = {
                calls.incrementAndGet()
                HttpResult(200, emptyMap(), stream(done = true))
            },
            authorization = OpenRouterAuthorization(metadataChecksAuthorized = false, inferenceAuthorized = true),
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        database.councilDao().insertConversation(ConversationEntity("c1", "Dispatch", ResearchMode.STANDARD.name, 1, 1))
        database.councilDao().insertSession(
            com.airachabanda.app.persistence.ResearchSessionEntity(
                id = "s1",
                conversationId = "c1",
                researchMode = ResearchMode.STANDARD.name,
                createdAtEpochMs = 1,
                requestBudget = 1,
            ),
        )
        val dispatch = OpenRouterDispatch(adapter, database.councilDao(), database.discussionDao(), clock = { 5 }, ids = { "m-${calls.get()}" })
        val command = com.airachabanda.app.provider.openrouter.DispatchCommand(
            sessionId = "s1",
            conversationId = "c1",
            taskId = "t1",
            prompt = "Question",
            candidate = freeCandidate(),
            apiKey = "synthetic-key",
            roundNumber = 1,
        )
        assertTrue(dispatch.dispatch(command).completed)
        val second = dispatch.dispatch(command)
        assertEquals(OpenRouterFailure.DUPLICATE, second.failure)
        assertEquals(1, database.councilDao().messages("c1").size)
        assertEquals(1, database.discussionDao().session("s1")?.requestsConsumed)
        val models = adapter.participantsFor(
            listOf(
                DiscoveredModel("example/one:free", freePricing, listOf("temperature"), pricingKnown = true),
                DiscoveredModel("example/two:free", freePricing, listOf("temperature"), pricingKnown = true),
                DiscoveredModel("example/missing:free", emptyMap(), emptyList()),
                DiscoveredModel("openai/gpt-4o", mapOf("prompt" to "0.1", "completion" to "0.1"), emptyList(), pricingKnown = true),
            ),
        )
        assertEquals(listOf("OPENROUTER:example/one:free", "OPENROUTER:example/two:free"), models.map { it.participantKey })
        database.close()
    }

    @Test
    fun engineDispatchStaysBlockedAndReviewLabelsAreExplicit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        var sequence = 0
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                listOf(EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED))
            },
            clock = { 1_000L },
            ids = { "id-${sequence++}" },
        )
        database.councilDao().insertConversation(ConversationEntity("c1", "Council", ResearchMode.STANDARD.name, 1, 1))
        val started = engine.acceptQuestion(
            ResearchStart("c1", "Question", ResearchMode.STANDARD, QuestionComplexity.MODERATE, roundLimit = 1, requestBudget = 1),
        )
        val task = database.councilDao().tasksForSession(started.sessionId).first()
        val blocked = engine.dispatchTask(started.sessionId, task.id, "synthetic-key", freeCandidate())
        assertEquals(OpenRouterFailure.POLICY, blocked.failure)
        assertFalse(blocked.completed)
        val sameModel = ReviewIndependence.classify("a", "shared-model", "b", "shared-model")
        assertEquals(ReviewClassification.SAME_MODEL_REVIEW, sameModel)
        val unknown = ReviewIndependence.classify("a", ModelIdentity.UNKNOWN, "b", "example/one:free")
        assertEquals(ReviewClassification.INDEPENDENCE_UNVERIFIED, unknown)
        val self = ReviewIndependence.classify("same", "example/one:free", "same", "example/one:free")
        assertEquals(ReviewClassification.SELF_CRITIQUE, self)
        val cross = ReviewIndependence.classify("a", "example/one:free", "b", "example/two:free")
        assertEquals(ReviewClassification.CROSS_MODEL_REVIEW, cross)
        assertTrue(ReviewIndependence.summarize(
            EligibleParticipant(ProviderId.OPENROUTER, false, BillingEligibility.UNKNOWN, verifiedModelId = "example/one:free", participantKey = "a"),
            listOf(EligibleParticipant(ProviderId.OPENROUTER, false, BillingEligibility.UNKNOWN, verifiedModelId = "example/two:free", participantKey = "b")),
        ).disclosure.contains("not proof"))
        database.close()
    }

    @Test
    fun metadataConsentParsesLimitsAndDoesNotTreatZeroAsEligible() = runBlocking {
        val calls = AtomicInteger()
        val blocked = OpenRouterAdapter(transport = { calls.incrementAndGet(); error("transport") })
        val withheld = blocked.verifyMetadata("synthetic-secret")
        assertEquals(OpenRouterFailure.POLICY, withheld.failure)
        assertEquals(0, calls.get())
        assertEquals(ConnectionState.VERIFICATION_REQUIRED, withheld.state)

        val consented = OpenRouterAdapter(
            transport = { request ->
                calls.incrementAndGet()
                assertTrue(request.url == OpenRouterEndpoints.KEY || request.url == OpenRouterEndpoints.MODELS)
                assertFalse(request.url == OpenRouterEndpoints.CHAT)
                when (request.url) {
                    OpenRouterEndpoints.KEY -> HttpResult(200, emptyMap(), keyBody(limit = "0", remaining = null))
                    else -> HttpResult(200, emptyMap(), modelCatalog())
                }
            },
            authorization = OpenRouterAuthorization(metadataChecksAuthorized = true, inferenceAuthorized = false),
        )
        val report = consented.verifyMetadata("synthetic-secret")
        assertEquals(2, calls.get())
        assertEquals(ConnectionState.ZERO_COST_ELIGIBILITY_UNVERIFIED, report.state)
        assertFalse(report.summary.contains("synthetic-secret"))
        assertTrue(report.remainingQuota.contains("Unknown"))
        assertEquals(listOf("example/priced-zero"), report.eligibleModels.map { it.id })
        assertFalse(report.usageLimits.contains("Free-model requests remaining: 50"))
        val chat = consented.complete("synthetic-secret", freeCandidate(), "Do not send")
        assertEquals(OpenRouterFailure.POLICY, chat.failure)
        assertEquals(2, calls.get())

        val unlimited = OpenRouterAdapter(
            transport = { request ->
                if (request.url == OpenRouterEndpoints.KEY) HttpResult(200, emptyMap(), keyBody(limit = "null", remaining = "10"))
                else HttpResult(200, emptyMap(), modelCatalog())
            },
            authorization = OpenRouterAuthorization(metadataChecksAuthorized = true, inferenceAuthorized = false),
        ).verifyMetadata("synthetic-secret")
        assertEquals(ConnectionState.BLOCKED, unlimited.state)

        val rejected = OpenRouterAdapter(
            transport = { HttpResult(401, emptyMap(), """{"error":{"message":"no"}}""") },
            authorization = OpenRouterAuthorization(metadataChecksAuthorized = true, inferenceAuthorized = false),
        ).verifyMetadata("synthetic-secret")
        assertEquals(ConnectionState.AUTHENTICATION_FAILED, rejected.state)

        val limited = OpenRouterAdapter(
            transport = { HttpResult(429, mapOf("retry-after" to "3"), "") },
            authorization = OpenRouterAuthorization(metadataChecksAuthorized = true, inferenceAuthorized = false),
        ).verifyMetadata("synthetic-secret")
        assertEquals(OpenRouterFailure.RATE_LIMIT, limited.failure)
        assertEquals(ConnectionState.VERIFICATION_REQUIRED, limited.state)
        assertEquals("Unknown", limited.remainingQuota)

        val offline = OpenRouterAdapter(
            transport = { throw java.io.IOException("down") },
            authorization = OpenRouterAuthorization(metadataChecksAuthorized = true, inferenceAuthorized = false),
        ).verifyMetadata("synthetic-secret")
        assertEquals(ConnectionState.VERIFICATION_REQUIRED, offline.state)

        val proven = OpenRouterAdapter(transport = { error("unused") }).classifyMetadata(
            com.airachabanda.app.provider.openrouter.KeyObservation(
                limit = 0.0,
                limitRemaining = 0.0,
                limitReset = null,
                dailyUsed = 1,
                dailyLimit = 50,
                dailyRemaining = 4,
                freeTierFlag = true,
                usage = 0.0,
                usageDaily = 0.0,
                usageWeekly = 0.0,
                usageMonthly = 0.0,
                expiresAt = null,
                managementKey = false,
                provisioningKey = false,
                deprecatedRateLimitRequests = null,
                pluginDefaultsPresent = true,
            ),
            listOf(DiscoveredModel("example/priced-zero", freePricing, emptyList(), pricingKnown = true)),
        )
        assertEquals(ConnectionState.ZERO_COST_ELIGIBLE, proven.state)
        assertFalse(OpenRouterAuthorization.PRODUCTION.inferenceAuthorized)
    }

    private fun freeCandidate(
        modelId: String = "example/vendor-model:free",
        pricing: Map<String, String>? = freePricing,
        fallbacks: List<String> = emptyList(),
        tools: Boolean = false,
        dailyRemaining: Int? = 4,
        budget: Int = 1,
        limit: Double? = 0.0,
    ) = OpenRouterCandidate(
        modelId = modelId,
        pricing = pricing,
        fallbackModelIds = fallbacks,
        toolsRequested = tools,
        observedKeyLimit = limit,
        observedDailyRemaining = dailyRemaining,
        sessionBudgetRemaining = budget,
    )

    private fun stream(done: Boolean, finish: String = "stop"): String = buildString {
        appendLine("""data: {"id":"gen-test","model":"example/vendor-model:free","choices":[{"index":0,"delta":{"content":"Hello"}}]}""")
        appendLine("""data: {"id":"gen-test","model":"example/vendor-model:free","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"$finish"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
        if (done) appendLine("data: [DONE]")
    }

    private fun keyBody(limit: String, remaining: String?): String {
        val dailyRemaining = if (remaining == null) "null" else remaining
        return """
            {"data":{"limit":$limit,"limit_remaining":0,"is_free_tier":false,"free_model_daily_requests":{"limit":50,"remaining":$dailyRemaining,"used":0},"usage":0,"rate_limit":{"requests":1000,"note":"deprecated"}}}
        """.trimIndent()
    }

    private fun modelCatalog(): String = """
        {"data":[
          {"id":"example/priced-zero","name":"Priced zero","canonical_slug":"example/priced-zero","context_length":8192,"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0","request":"0"},"supported_parameters":["temperature"]},
          {"id":"example/missing:free","name":"Suffix only","pricing":{}},
          {"id":"example/paid","name":"Paid","pricing":{"prompt":"0.1","completion":"0.2"}},
          {"id":"openrouter/free","name":"Router","pricing":{"prompt":"0","completion":"0"}}
        ]}
    """.trimIndent()
}

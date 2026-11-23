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
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
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
        assertFalse(body.contains("plugins"))
        assertFalse(body.contains("\"tools\""))
        assertTrue(body.contains("\"allow_fallbacks\":false"))
        assertFalse(outcome.request?.headers?.get("Authorization")?.let { body.contains(it) } ?: false)
    }

    @Test
    fun rejectsPaidUnknownPricingFallbacksAndMissingQuota() {
        val adapter = OpenRouterAdapter(transport = { error("transport") })
        assertTrue(adapter.assess(freeCandidate(modelId = "openai/gpt-4o")).reason.contains("paid"))
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
        assertEquals("gen-test", complete.generationId)
        assertEquals("example/vendor-model:free", complete.modelId)
        assertEquals("Hello", complete.text)
        val partial = adapter.parseStream(stream(done = false))
        assertFalse(partial.completed)
        assertEquals(OpenRouterFailure.INCOMPLETE, partial.failure)
        assertEquals(OpenRouterFailure.MALFORMED, adapter.parseStream("data: {").failure)
        assertEquals(OpenRouterFailure.AUTHENTICATION, adapter.classifyHttp(401, emptyMap()).failure)
        assertEquals(OpenRouterFailure.PAYMENT_REQUIRED, adapter.classifyHttp(402, emptyMap()).failure)
        assertEquals(OpenRouterFailure.BUDGET_BLOCKED, adapter.classifyHttp(403, emptyMap()).failure)
        val limited = adapter.classifyHttp(429, mapOf("retry-after" to "2"))
        assertEquals(OpenRouterFailure.RATE_LIMIT, limited.failure)
        assertTrue(limited.reason.contains("No automatic retry"))
        assertEquals(OpenRouterFailure.TIMEOUT, adapter.classifyFailure(SocketTimeoutException("timed out")).failure)
        assertEquals(OpenRouterFailure.CANCELLED, adapter.classifyFailure(kotlinx.coroutines.CancellationException("stop")).failure)
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
        val models = adapter.participantsFor(
            listOf(
                DiscoveredModel("example/one:free", freePricing, listOf("temperature")),
                DiscoveredModel("example/two:free", freePricing, listOf("temperature")),
                DiscoveredModel("openai/gpt-4o", mapOf("prompt" to "0.1", "completion" to "0.1"), emptyList()),
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

    private fun stream(done: Boolean): String = buildString {
        appendLine("""data: {"id":"gen-test","model":"example/vendor-model:free","choices":[{"index":0,"delta":{"content":"Hello"}}]}""")
        appendLine("""data: {"id":"gen-test","model":"example/vendor-model:free","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
        if (done) appendLine("data: [DONE]")
    }
}

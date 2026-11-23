package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ConclusionKind
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ObjectionCategory
import com.airachabanda.app.core.model.ObjectionSeverity
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResolutionStatus
import com.airachabanda.app.core.model.SessionState
import com.airachabanda.app.core.model.VerificationOutcome
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.ResearchStart
import com.airachabanda.app.orchestration.RoleAssignment
import com.airachabanda.app.persistence.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrchestrationEngineTest {
    private lateinit var database: AppDatabase
    private var now = 1_000L
    private var sequence = 0

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
    fun rolesFollowComplexityAndAnExtraCapability() {
        val people = listOf(
            EligibleParticipant(ProviderId.CHATGPT, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
            EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
        )
        val low = RoleAssignment.assign(people, QuestionComplexity.LOW, emptySet()).map { it.role }.toSet()
        assertFalse(low.contains(ParticipantRole.DEVILS_ADVOCATE))
        assertTrue(low.contains(ParticipantRole.FINAL_SYNTHESIZER))
        val withCritique = RoleAssignment.assign(people, QuestionComplexity.LOW, setOf(RoleAssignment.CRITIQUE))
        val advocate = withCritique.single { it.role == ParticipantRole.DEVILS_ADVOCATE }
        val synthesizer = withCritique.single { it.role == ParticipantRole.FINAL_SYNTHESIZER }
        assertEquals(ProviderId.CHATGPT, advocate.providerId)
        assertEquals(ProviderId.CLAUDE, synthesizer.providerId)
    }

    @Test
    fun assistedParticipantDoesNotBlockAutomatedWork() = runBlocking {
        val engine = engine(
            EligibleParticipant(ProviderId.GROQ, assisted = false, billing = BillingEligibility.VERIFIED_FREE),
            EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
        )
        val conversationId = insertConversation()
        val started = engine.acceptQuestion(start(conversationId, QuestionComplexity.LOW, budget = 2))
        assertEquals(SessionState.INDEPENDENT_RESEARCH, started.state)
        assertEquals(1, started.requestsConsumed)
        val tasks = database.councilDao().tasksForSession(started.sessionId)
        assertTrue(tasks.any { it.providerId == "CLAUDE" && it.state == "AWAITING_USER_RESPONSE" })
        val groq = tasks.single { it.providerId == "GROQ" && it.automated }
        val afterGroq = engine.recordParticipantResponse(started.sessionId, groq.id, "A supplied Groq note.")
        assertEquals(SessionState.AWAITING_USER_RESPONSE, afterGroq.state)
        assertTrue(database.councilDao().tasksForSession(started.sessionId).any { it.providerId == "CLAUDE" && it.state == "AWAITING_USER_RESPONSE" })
    }

    @Test
    fun chatGptCannotSpendBudgetAndAssistedCritiqueDoesNotEither() = runBlocking {
        val blocked = engine(
            EligibleParticipant(ProviderId.CHATGPT, assisted = false, billing = BillingEligibility.VERIFIED_FREE),
        )
        val conversationId = insertConversation()
        val denied = blocked.acceptQuestion(start(conversationId, QuestionComplexity.LOW, budget = 3))
        assertEquals(SessionState.BLOCKED, denied.state)
        assertEquals(0, denied.requestsConsumed)

        val assisted = engine(
            EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
            EligibleParticipant(ProviderId.CHATGPT, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
        )
        val second = insertConversation()
        val started = assisted.acceptQuestion(start(second, QuestionComplexity.MODERATE, budget = 0, limit = 2))
        assertEquals(0, started.requestsConsumed)
        assertEquals(SessionState.AWAITING_USER_RESPONSE, started.state)
    }

    @Test
    fun critiqueLoopStopsAndKeepsObjections() = runBlocking {
        val engine = engine(
            EligibleParticipant(ProviderId.CHATGPT, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
            EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
        )
        val conversationId = insertConversation()
        val started = engine.acceptQuestion(start(conversationId, QuestionComplexity.MODERATE, budget = 0, limit = 2))
        database.councilDao().tasksForSession(started.sessionId)
            .filter { it.kind == "RESEARCH" }
            .forEach { task ->
                engine.recordParticipantResponse(
                    started.sessionId,
                    task.id,
                    "The supplied claim from ${task.providerId}.",
                    userDeclaredModelId = if (task.providerId == "CLAUDE") "claude-sonnet-test" else null,
                )
            }
        val afterResearch = engine.advance(started.sessionId)
        assertEquals(SessionState.AWAITING_USER_RESPONSE, afterResearch.state)
        val critique = database.councilDao().tasksForSession(started.sessionId).single { it.kind == "CRITIQUE" }
        engine.recordParticipantResponse(started.sessionId, critique.id, "The critique text supplied by the user.")
        val claim = database.discussionDao().claims(started.sessionId).first()
        val author = database.discussionDao().participants(started.sessionId).first { it.providerId == "CHATGPT" }
        val objectionId = engine.recordObjection(
            sessionId = started.sessionId,
            targetClaimId = claim.id,
            authorParticipantId = author.id,
            category = ObjectionCategory.FACTUAL_ERROR,
            severity = ObjectionSeverity.HIGH,
            explanation = "The supplied claim is not supported.",
            supportingEvidence = "",
            requestedVerification = "Check the claim against a primary source.",
        )
        engine.continueReview(started.sessionId)
        engine.recordRebuttal(started.sessionId, objectionId, "The original wording stands.")
        val reassessed = engine.continueReview(started.sessionId)
        assertTrue(reassessed.reviewRound >= 2 || reassessed.state == SessionState.AWAITING_USER_RESPONSE || reassessed.state == SessionState.CRITICAL_REVIEW)
        val secondCritique = database.councilDao().tasksForSession(started.sessionId).filter { it.kind == "CRITIQUE" }
        assertEquals(2, secondCritique.size)
        secondCritique.filter { it.state != "COMPLETED" }.forEach {
            engine.recordParticipantResponse(started.sessionId, it.id, "Second critique supplied by the user.")
        }
        val synthesisReady = engine.continueReview(started.sessionId)
        assertTrue(
            synthesisReady.state == SessionState.SYNTHESIS ||
                synthesisReady.state == SessionState.AWAITING_USER_RESPONSE,
        )
        engine.recordPreliminaryConclusion(started.sessionId, "Preliminary wording.")
        val finished = engine.recordFinalConclusion(started.sessionId, "Final wording that still leaves the objection open.")
        assertEquals(SessionState.COMPLETED, finished.state)
        val objection = database.discussionDao().objection(objectionId)
        assertEquals(ResolutionStatus.OPEN.name, objection?.resolution)
        assertEquals("The supplied claim is not supported.", objection?.explanation)
        val conclusions = database.discussionDao().conclusions(started.sessionId)
        assertTrue(conclusions.any { it.kind == ConclusionKind.PRELIMINARY.name })
        assertTrue(conclusions.any { it.kind == ConclusionKind.FINAL.name })
        val claude = database.discussionDao().participants(started.sessionId).first { it.providerId == "CLAUDE" }
        val identity = database.discussionDao().modelIdentity(claude.id)
        assertEquals("claude-sonnet-test", identity?.userDeclaredModelId)
        assertEquals(ModelIdentity.UNKNOWN, identity?.verifiedModelId)
        assertFalse(identity?.independentlyVerified == true)
        assertTrue(database.discussionDao().checkpoints(started.sessionId).size > 3)
    }

    @Test
    fun pauseResumeCancelDeadlineAndFollowUpAreBounded() = runBlocking {
        val engine = engine(
            EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
        )
        val conversationId = insertConversation()
        val started = engine.acceptQuestion(
            start(conversationId, QuestionComplexity.LOW, budget = 0, limit = 1, deadline = 5_000L),
        )
        val paused = engine.pause(started.sessionId)
        assertEquals(SessionState.PAUSED, paused.state)
        val taskId = database.councilDao().tasksForSession(started.sessionId).single().id
        val refused = engine.recordParticipantResponse(started.sessionId, taskId, "Should stay paused.")
        assertEquals(SessionState.PAUSED, refused.state)
        val resumed = engine.resume(started.sessionId)
        assertEquals(SessionState.AWAITING_USER_RESPONSE, resumed.state)
        val followUp = engine.routeFollowUp(started.sessionId, ProviderId.CLAUDE, "A second question.")
        assertTrue(followUp.statusNote.contains("round limit"))
        val cancelled = engine.cancel(started.sessionId)
        assertEquals(SessionState.CANCELLED, cancelled.state)

        val timed = engine(
            EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED),
        )
        val other = insertConversation()
        val open = timed.acceptQuestion(start(other, QuestionComplexity.LOW, budget = 0, deadline = 1_500L))
        now = 2_000L
        val failed = timed.advance(open.sessionId)
        assertEquals(SessionState.FAILED, failed.state)
    }

    private fun engine(vararg people: EligibleParticipant): OrchestrationEngine {
        val source = ParticipantSource { people.toList() }
        return OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = source,
            clock = { now },
            ids = { "id-${sequence++}" },
        )
    }

    private suspend fun insertConversation(): String {
        val id = "conversation-${sequence++}"
        database.councilDao().insertConversation(
            com.airachabanda.app.persistence.ConversationEntity(
                id = id,
                title = "Council",
                researchMode = ResearchMode.STANDARD.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        return id
    }

    private fun start(
        conversationId: String,
        complexity: QuestionComplexity,
        budget: Int,
        limit: Int = 1,
        deadline: Long? = null,
    ) = ResearchStart(
        conversationId = conversationId,
        question = "What does the supplied material actually show?",
        mode = ResearchMode.STANDARD,
        complexity = complexity,
        roundLimit = limit,
        requestBudget = budget,
        deadlineEpochMs = deadline,
    )
}

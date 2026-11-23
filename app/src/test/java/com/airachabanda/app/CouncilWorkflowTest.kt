package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResolutionStatus
import com.airachabanda.app.orchestration.CouncilAction
import com.airachabanda.app.orchestration.CouncilWorkflow
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.ProtocolRequest
import com.airachabanda.app.orchestration.RoleAssignment
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.ConversationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CouncilWorkflowTest {
    @After
    fun clean() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("workflow-recovery.db")
    }

    @Test
    fun assistedCouncilKeepsAnOpenObjectionThroughPauseAndRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("workflow-recovery.db")
        val first = open("workflow-recovery.db")
        val conversationId = "conversation-1"
        first.database.councilDao().insertConversation(
            ConversationEntity(conversationId, "Milestone 004A test", ResearchMode.STANDARD.name, 1_000L, 1_000L),
        )
        val started = first.workflow.start(
            conversationId = conversationId,
            question = "Milestone 004A test question",
            mode = ResearchMode.STANDARD,
            roundLimit = 2,
            selectedProviders = setOf(ProviderId.CLAUDE),
        )
        assertTrue(started.contains("assisted"))
        val research = first.database.councilDao().tasksForSession(first.workflow.inspect(conversationId).sessionId!!).single { it.kind == "RESEARCH" }
        first.workflow.importTask(conversationId, research.id, "Milestone 004A test response. The supplied claim is unchecked.", null)
        first.workflow.advance(conversationId)
        val critique = first.workflow.prepareCritique(conversationId)
        assertTrue(critique.contains("Devil's Advocate") || critique.contains("Critique"))
        val critiqueTask = first.database.councilDao().tasksForSession(first.workflow.inspect(conversationId).sessionId!!).single { it.kind == "CRITIQUE" }
        first.workflow.importTask(conversationId, critiqueTask.id, "Milestone 004A test critique. The claim lacks support.", "claude-test")
        val recorded = first.workflow.recordObjection(conversationId, "The test claim is not supported by the imported text.")
        assertTrue(recorded.contains("open"))
        val rebuttal = first.workflow.requestRebuttal(conversationId)
        assertTrue(rebuttal.contains("Accepted"))
        val beforePause = first.workflow.inspect(conversationId)
        assertEquals(1, beforePause.unresolvedObjections)
        assertTrue(first.workflow.pause(conversationId).contains("Paused"))
        assertFalse(first.workflow.resume(conversationId).contains("No discussion"))
        first.database.close()

        val second = open("workflow-recovery.db")
        val recovered = second.workflow.inspect(conversationId)
        assertTrue(recovered.sessionId != null)
        assertEquals(1, recovered.unresolvedObjections)
        assertTrue(recovered.participants.single().contains("Verified model unknown"))
        second.database.close()
    }

    @Test
    fun sameVerifiedModelIsNotAnIndependentReviewerAndUnknownStaysUnknown() {
        val shared = EligibleParticipant(
            ProviderId.CHATGPT,
            assisted = true,
            billing = BillingEligibility.USER_CONFIGURATION_REQUIRED,
            verifiedModelId = "shared-model",
        )
        val otherHost = EligibleParticipant(
            ProviderId.CLAUDE,
            assisted = true,
            billing = BillingEligibility.USER_CONFIGURATION_REQUIRED,
            verifiedModelId = "shared-model",
        )
        val grants = RoleAssignment.assign(
            participants = listOf(shared, otherHost),
            complexity = QuestionComplexity.MODERATE,
            requiredCapabilities = setOf(RoleAssignment.CRITIQUE),
            claimAuthors = setOf(ProviderId.CHATGPT),
        )
        val critique = grants.single { it.role == ParticipantRole.DEVILS_ADVOCATE }
        assertEquals(ProviderId.CLAUDE, critique.providerId)
        assertTrue(critique.reason.contains("not an independent review"))
        val unknown = RoleAssignment.assign(
            participants = listOf(
                EligibleParticipant(ProviderId.CLAUDE, true, BillingEligibility.USER_CONFIGURATION_REQUIRED),
            ),
            complexity = QuestionComplexity.LOW,
            requiredCapabilities = setOf(RoleAssignment.CRITIQUE),
        ).single { it.role == ParticipantRole.DEVILS_ADVOCATE }
        assertTrue(unknown.reason.contains(ModelIdentity.UNKNOWN))
    }

    @Test
    fun protocolCannotExpandTheBudgetAndReviewCapacityIsHeld() = runBlocking {
        val harness = memory()
        val conversationId = "budget"
        harness.database.councilDao().insertConversation(
            ConversationEntity(conversationId, "Budget", ResearchMode.STANDARD.name, 1L, 1L),
        )
        val engine = OrchestrationEngine(
            council = harness.database.councilDao(),
            discussion = harness.database.discussionDao(),
            participants = ParticipantSource {
                listOf(
                    EligibleParticipant(ProviderId.GROQ, assisted = false, billing = BillingEligibility.VERIFIED_FREE),
                    EligibleParticipant(ProviderId.GEMINI, assisted = false, billing = BillingEligibility.VERIFIED_FREE),
                )
            },
            clock = { 1_000L },
            ids = { "budget-${harness.nextId()}" },
        )
        val started = engine.acceptQuestion(
            com.airachabanda.app.orchestration.ResearchStart(
                conversationId = conversationId,
                question = "Budget question",
                mode = ResearchMode.STANDARD,
                complexity = QuestionComplexity.MODERATE,
                roundLimit = 2,
                requestBudget = 1,
            ),
        )
        assertEquals(0, started.requestsConsumed)
        val tasks = harness.database.councilDao().tasksForSession(started.sessionId)
        assertTrue(tasks.filter { it.kind == "RESEARCH" }.all { it.state == "BLOCKED" })
        val actor = harness.database.discussionDao().participants(started.sessionId).first().id
        val rejected = engine.submitAction(
            ProtocolRequest(
                sessionId = started.sessionId,
                actorParticipantId = actor,
                action = CouncilAction.REQUEST_ADDITIONAL_RESEARCH,
                body = "Try to spend more than the budget.",
                requestedRequests = 9,
            ),
        )
        assertFalse(rejected.accepted)
        assertTrue(rejected.reason.contains("budget"))
        assertEquals(1, harness.database.discussionDao().session(started.sessionId)?.requestBudget)
        harness.database.close()
    }

    private fun memory(): Harness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        return Harness(database, workflow(database), ids = 0)
    }

    private fun open(name: String): Harness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        return Harness(database, workflow(database), ids = 0)
    }

    private fun workflow(database: AppDatabase): CouncilWorkflow {
        var sequence = 0
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                listOf(EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED))
            },
            clock = { 5_000L },
            ids = { "wf-${sequence++}" },
        )
        return CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { 5_000L })
    }

    private data class Harness(
        val database: AppDatabase,
        val workflow: CouncilWorkflow,
        var ids: Int,
    ) {
        fun nextId(): Int = ids++
    }
}

package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.orchestration.BudgetLedger
import com.airachabanda.app.orchestration.CouncilWorkflow
import com.airachabanda.app.orchestration.DiscussionPlanner
import com.airachabanda.app.orchestration.DiscussionQuality
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.ExecutionGate
import com.airachabanda.app.orchestration.ExecutionIntent
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.SynthesisBrief
import com.airachabanda.app.orchestration.TaskContext
import com.airachabanda.app.orchestration.TaskGraph
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.ConversationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Milestone006bTest {
    @Test
    fun planningUsesStructureAndKeepsQuickReviewSmall() {
        val quick = DiscussionPlanner.plan(
            DiscussionPlanner.Signals(
                objective = "TEST FIXTURE what is 17 multiplied by 23?",
                mode = ResearchMode.QUICK,
                explicitCapabilities = emptySet(),
                participantCount = 3,
                requestBudget = 1,
                deadlineEpochMs = null,
                nowEpochMs = 1L,
                consequences = false,
            ),
        )
        assertEquals(DiscussionPlanner.SOURCE, quick.source)
        assertFalse(quick.decompose)
        assertFalse(quick.critiqueRequired)
        assertTrue(quick.note.contains("Deterministic"))
        assertFalse(quick.note.contains("confidence"))
        val split = DiscussionPlanner.plan(
            DiscussionPlanner.Signals(
                objective = "1. TEST FIXTURE alpha length\n2. TEST FIXTURE beta length",
                mode = ResearchMode.STANDARD,
                explicitCapabilities = emptySet(),
                participantCount = 3,
                requestBudget = 3,
                deadlineEpochMs = null,
                nowEpochMs = 1L,
                consequences = false,
            ),
        )
        assertTrue(split.decompose)
        assertEquals(2, split.concurrencyLimit)
        assertEquals(3, split.tasks.size)
        assertFalse(TaskGraph.hasCycle(split.tasks.flatMap { task -> task.dependsOn.map { task.localId to it } }))
        assertTrue(TaskGraph.hasCycle(listOf("a" to "b", "b" to "a")))
    }

    @Test
    fun parallelTasksStayIsolatedUntilDependenciesFinish() = runBlocking {
        val harness = harness()
        val id = "parallel"
        harness.database.councilDao().insertConversation(ConversationEntity(id, "Parallel", ResearchMode.STANDARD.name, 1L, 1L))
        val question = "1. TEST FIXTURE alpha length\n2. TEST FIXTURE beta length\n3. TEST FIXTURE gamma length"
        harness.workflow.start(id, question, ResearchMode.STANDARD, 3, setOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val session = harness.workflow.inspect(id).sessionId!!
        val tasks = harness.database.councilDao().tasksForSession(session)
        val research = tasks.filter { it.kind == "RESEARCH" }
        val analysis = tasks.single { it.kind == "ANALYSIS" }
        assertEquals(3, research.size)
        assertEquals(AssistedTaskState.QUEUED.name, analysis.state)
        assertEquals(2, research.count { it.state == AssistedTaskState.AWAITING_USER_RESPONSE.name })
        assertEquals(1, research.count { it.state == AssistedTaskState.QUEUED.name })
        val alpha = research.single { it.objective.contains("alpha") }
        val beta = research.single { it.objective.contains("beta") }
        assertFalse(alpha.prompt.contains("beta"))
        assertFalse(beta.prompt.contains("alpha"))
        assertTrue(alpha.prompt.contains("withheld") || alpha.prompt.contains("initial answers"))
        harness.workflow.importTask(id, alpha.id, "TEST FIXTURE alpha is recorded.", null)
        harness.workflow.advance(id)
        val after = harness.database.councilDao().tasksForSession(session)
        assertEquals(AssistedTaskState.AWAITING_USER_RESPONSE.name, after.single { it.objective.contains("gamma") }.state)
        assertEquals(AssistedTaskState.QUEUED.name, after.single { it.kind == "ANALYSIS" }.state)
        harness.database.councilDao().updateTaskState(beta.id, AssistedTaskState.CANCELLED.name, 2L)
        harness.workflow.advance(id)
        assertEquals(
            AssistedTaskState.CANCELLED.name,
            harness.database.councilDao().task(analysis.id)?.state,
        )
        harness.database.close()
    }

    @Test
    fun delegationBudgetContextAndRestartStayBounded() = runBlocking {
        assertFalse(BudgetLedger.reserve(remaining = 1, requested = 1, reviewReserve = 1))
        assertEquals(2, BudgetLedger.bounded(current = 2, proposed = 9))
        assertFalse(ExecutionGate.allow(ExecutionIntent.ENABLE_BILLING))
        assertFalse(ExecutionGate.allow(ExecutionIntent.READ_CREDENTIAL))
        assertFalse(ExecutionGate.allow(ExecutionIntent.OPEN_NETWORK))
        assertFalse(ExecutionGate.allow(ExecutionIntent.EXECUTE_CODE))
        assertFalse(ExecutionGate.allow(ExecutionIntent.RAISE_BUDGET))
        assertFalse(ExecutionGate.retryUnknownOutcome())
        val summary = TaskContext.targeted(
            objective = "TEST FIXTURE combine the parts.",
            claims = listOf("x".repeat(1_500)),
            objections = emptyList(),
            evidence = listOf("source:test-fixture-locator"),
        )
        assertTrue(summary.contains("not the original source"))
        assertTrue(summary.contains("source:test-fixture-locator"))
        val quality = DiscussionQuality.assess(
            DiscussionQuality.Check(
                requirementsAddressed = true,
                claimsMissingEvidence = false,
                materialDisagreement = false,
                mathVerificationRequired = false,
                mathVerificationDone = false,
                critiqueCompletedOrUnavailable = true,
                furtherResearchWouldHelp = false,
                budgetRemaining = 4,
            ),
        )
        assertTrue(quality.readyForSynthesis)
        assertTrue(quality.note.contains("not a reason to continue"))
        val brief = SynthesisBrief.prepare(
            SynthesisBrief.Input(
                objective = "TEST FIXTURE objective",
                corrections = listOf("TEST FIXTURE correction"),
                findings = listOf("TEST FIXTURE finding"),
                evidence = emptyList(),
                contradictions = emptyList(),
                unresolved = listOf("TEST FIXTURE objection"),
                verificationLimits = "No live verifier ran.",
                participants = listOf("TEST FIXTURE participant. Verified unknown."),
            ),
        )
        assertTrue(brief.contains("not the final answer"))
        assertTrue(brief.contains("TEST FIXTURE participant"))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("milestone-006b.db")
        val first = fileHarness(context)
        val id = "restart"
        first.database.councilDao().insertConversation(ConversationEntity(id, "Restart", ResearchMode.STANDARD.name, 1L, 1L))
        first.workflow.start(
            id,
            "1. TEST FIXTURE alpha length\n2. TEST FIXTURE beta length",
            ResearchMode.STANDARD,
            3,
            setOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI),
        )
        val session = first.workflow.inspect(id).sessionId!!
        assertTrue(first.database.discussionDao().dependenciesForSession(session).isNotEmpty())
        val correction = first.workflow.addContext(id, "TEST FIXTURE the split is invalid.", invalidateOpenWork = true)
        assertTrue(correction.contains("cancelled"))
        assertTrue(
            first.database.councilDao().tasksForSession(session)
                .filter { it.kind == "ANALYSIS" || it.kind == "RESEARCH" }
                .all { it.state == AssistedTaskState.CANCELLED.name },
        )
        first.database.close()
        val second = fileHarness(context)
        assertTrue(second.database.discussionDao().dependenciesForSession(session).isNotEmpty())
        assertTrue(second.workflow.inspect(id).planNote.contains("Deterministic"))
        second.database.close()
    }

    private fun harness(): Harness = wire(memory())

    private fun fileHarness(context: android.content.Context): Harness =
        wire(Room.databaseBuilder(context, AppDatabase::class.java, "milestone-006b.db").allowMainThreadQueries().build())

    private fun memory(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    private fun wire(database: AppDatabase): Harness {
        var sequence = 0
        val providers = listOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI)
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                providers.mapIndexed { index, provider ->
                    EligibleParticipant(
                        providerId = provider,
                        assisted = true,
                        billing = BillingEligibility.USER_CONFIGURATION_REQUIRED,
                        userDeclaredModelId = "TEST FIXTURE participant ${index + 1}",
                        verifiedModelId = "unknown",
                    )
                }
            },
            clock = { 7_000L },
            ids = { "m6b-${sequence++}" },
        )
        return Harness(database, CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { 7_000L }))
    }

    private data class Harness(val database: AppDatabase, val workflow: CouncilWorkflow)
}

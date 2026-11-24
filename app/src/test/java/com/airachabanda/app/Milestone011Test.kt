package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.SessionState
import com.airachabanda.app.documents.GeneratedDocumentService
import com.airachabanda.app.orchestration.ContinuityCandidate
import com.airachabanda.app.orchestration.CouncilWorkflow
import com.airachabanda.app.orchestration.DiscussionNotificationCopy
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.ProviderFault
import com.airachabanda.app.orchestration.TaskReassignment
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.ConversationEntity
import com.airachabanda.app.persistence.GeneratedDocumentEntity
import com.airachabanda.app.provider.openrouter.OpenRouterCandidate
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
class Milestone011Test {
    @Test
    fun reassignmentRefusesIncapablePaidAndExpiredCandidates() {
        val incapable = ContinuityCandidate("a", ProviderId.CLAUDE, true, BillingEligibility.USER_CONFIGURATION_REQUIRED, "unknown", "ELIGIBLE", emptySet())
        val paid = ContinuityCandidate("b", ProviderId.OPENROUTER, false, BillingEligibility.USER_CONFIGURATION_REQUIRED, "unknown", "ELIGIBLE", setOf("critique"))
        val missed = TaskReassignment.select(ParticipantRole.DEVILS_ADVOCATE, listOf(incapable, paid), emptySet(), emptySet(), 4, null, 10L)
        assertFalse(missed.accepted)
        assertTrue(missed.reason.contains("Paid inference was not activated"))
        val late = TaskReassignment.select(ParticipantRole.RESEARCHER, listOf(incapable.copy(capabilities = setOf("research"))), emptySet(), emptySet(), 1, 5L, 10L)
        assertFalse(late.accepted)
        assertTrue(late.reason.contains("deadline"))
    }

    @Test
    fun oneFailureLeavesTheOthersWorking() = runBlocking {
        val harness = harness(listOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val id = "three"
        harness.database.councilDao().insertConversation(ConversationEntity(id, "TEST FIXTURE continuity", ResearchMode.STANDARD.name, 1L, 1L))
        harness.workflow.start(id, "TEST FIXTURE what remains if one participant fails?", ResearchMode.STANDARD, 3, setOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val session = harness.workflow.inspect(id).sessionId!!
        val research = harness.database.councilDao().tasksForSession(session).filter { it.kind == "RESEARCH" }
        assertTrue(research.size >= 3)
        harness.workflow.importTask(id, research[0].id, "TEST FIXTURE kept finding.", null)
        val failing = research[1]
        val beforeBudget = harness.database.discussionDao().session(session)!!.requestsConsumed
        val note = harness.engine.reportProviderFault(session, failing.participantId!!, ProviderFault.QUOTA_EXHAUSTED, true)
        val after = harness.database.councilDao().tasksForSession(session)
        assertEquals(session, harness.workflow.inspect(id).sessionId)
        assertEquals(AssistedTaskState.COMPLETED.name, after.first { it.id == research[0].id }.state)
        assertEquals(AssistedTaskState.CANCELLED.name, after.first { it.id == failing.id }.state)
        assertTrue(after.any { it.id != failing.id && it.participantId != failing.participantId && it.kind == "RESEARCH" && it.state != AssistedTaskState.CANCELLED.name })
        assertEquals(beforeBudget, harness.database.discussionDao().session(session)!!.requestsConsumed)
        assertTrue(note.statusNote.contains("not restarted"))
        assertEquals(0, harness.database.councilDao().providerConfigCount())
        assertTrue(harness.database.discussionDao().faults(session).any { it.fault == "QUOTA_EXHAUSTED" && it.outcome == "KNOWN" })
        harness.database.close()
    }

    @Test
    fun devilsAdvocateMovesToAnotherEligibleParticipant() = runBlocking {
        val harness = harness(listOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val id = "advocate"
        harness.database.councilDao().insertConversation(ConversationEntity(id, "Advocate", ResearchMode.STANDARD.name, 1L, 1L))
        harness.workflow.start(id, "TEST FIXTURE compare the two lengths.", ResearchMode.STANDARD, 3, setOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val session = harness.workflow.inspect(id).sessionId!!
        val research = harness.database.councilDao().tasksForSession(session).filter { it.kind == "RESEARCH" }
        research.forEach { harness.workflow.importTask(id, it.id, "TEST FIXTURE length note ${it.providerId}.", null) }
        harness.workflow.advance(id)
        val critique = harness.database.councilDao().tasksForSession(session).first { it.kind == "CRITIQUE" }
        val advocate = critique.participantId!!
        harness.engine.reportProviderFault(session, advocate, ProviderFault.AUTHENTICATION, true)
        val next = harness.database.councilDao().tasksForSession(session).filter { it.kind == "CRITIQUE" && it.state != AssistedTaskState.CANCELLED.name }
        assertTrue(next.isNotEmpty())
        assertTrue(next.all { it.participantId != advocate })
        assertEquals(session, harness.workflow.inspect(id).sessionId)
        harness.database.close()
    }

    @Test
    fun singleAndMissingParticipantsAreDisclosed() = runBlocking {
        val one = harness(listOf(ProviderId.CLAUDE))
        one.database.councilDao().insertConversation(ConversationEntity("one", "One", ResearchMode.STANDARD.name, 1L, 1L))
        one.workflow.start("one", "TEST FIXTURE one participant.", ResearchMode.STANDARD, 2, setOf(ProviderId.CLAUDE))
        assertTrue(one.workflow.inspect("one").continuityNote.contains("Independent multi-model review is unavailable"))
        one.database.close()
        val none = harness(emptyList())
        none.database.councilDao().insertConversation(ConversationEntity("none", "None", ResearchMode.STANDARD.name, 1L, 1L))
        val started = none.workflow.start("none", "TEST FIXTURE no participant.", ResearchMode.STANDARD, 2, emptySet())
        val stage = none.workflow.inspect("none")
        assertTrue(started.contains("No AI response was invented"))
        assertTrue(stage.continuityNote.contains("Local document processing"))
        assertTrue(stage.continuityNote.contains("No AI response was invented"))
        assertEquals(0, none.database.councilDao().messages("none").size)
        none.database.close()
    }

    @Test
    fun pauseResumeStopFinishAndLateResponsesStayLocal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                listOf(ProviderId.CLAUDE, ProviderId.CHATGPT).map {
                    EligibleParticipant(it, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED)
                }
            },
            clock = { 6_000L },
            ids = { java.util.UUID.randomUUID().toString() },
        )
        val workflow = CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { 6_000L })
        val id = "controls"
        database.councilDao().insertConversation(ConversationEntity(id, "Controls", ResearchMode.STANDARD.name, 1L, 1L))
        workflow.start(id, "TEST FIXTURE pause and stop.", ResearchMode.STANDARD, 2, setOf(ProviderId.CLAUDE, ProviderId.CHATGPT))
        val session = workflow.inspect(id).sessionId!!
        val tasks = database.councilDao().tasksForSession(session)
        val running = tasks.first()
        database.councilDao().updateTaskState(running.id, AssistedTaskState.RUNNING.name, 6_000L)
        val before = database.councilDao().tasksForSession(session).size
        val paused = engine.pause(session)
        assertEquals(SessionState.PAUSED, paused.state)
        val blocked = database.councilDao().task(running.id)!!
        assertEquals("UNKNOWN", blocked.dispatchOutcome)
        assertEquals(AssistedTaskState.BLOCKED.name, blocked.state)
        val resumed = engine.resume(session)
        assertEquals(before, database.councilDao().tasksForSession(session).size)
        assertEquals(AssistedTaskState.BLOCKED.name, database.councilDao().task(running.id)!!.state)
        assertFalse(resumed.statusNote.contains("retried").not())
        val refused = engine.dispatchTask(session, tasks.last().id, "synthetic-key", OpenRouterCandidate("synthetic", null))
        workflow.pause(id)
        val pausedDispatch = engine.dispatchTask(session, tasks.last().id, "synthetic-key", OpenRouterCandidate("synthetic", null))
        assertTrue(pausedDispatch.reason.contains("Paused"))
        assertFalse(pausedDispatch.completed)
        workflow.resume(id)
        val documentId = "doc-1"
        database.councilDao().insertGeneratedDocument(
            GeneratedDocumentEntity(documentId, id, "TEST FIXTURE", "letter", "TXT", "local.txt", "text/plain", 0L, "", "GENERATING", "", 1, "TEST FIXTURE", false, 6_000L),
        )
        val documents = GeneratedDocumentService(context, database.councilDao(), database.discussionDao(), clock = { 6_000L }, ids = { "doc-id" })
        val stopping = engine.copyStop(documents, id, session)
        assertEquals(id, stopping)
        assertEquals("CANCELLED", database.councilDao().generatedDocument(documentId)!!.status)
        val late = workflow.importTask(id, tasks.last().id, "TEST FIXTURE late answer.", null)
        assertTrue(late.contains("not recorded") || late.contains("late response"))
        assertTrue(database.councilDao().messages(id).none { it.body.contains("late answer") })
        assertTrue(database.councilDao().conversation(id) != null)
        assertTrue(refused.reason.isNotBlank())
        database.close()
    }

    @Test
    fun finishSkipWaitingAndRestartKeepTheBudget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("continuity-restart.db")
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "continuity-restart.db").allowMainThreadQueries().build()
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                listOf(EligibleParticipant(ProviderId.CLAUDE, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED))
            },
            clock = { 7_000L },
            ids = { java.util.UUID.randomUUID().toString() },
        )
        val workflow = CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { 7_000L })
        val id = "finish"
        database.councilDao().insertConversation(ConversationEntity(id, "Finish", ResearchMode.QUICK.name, 1L, 1L))
        workflow.start(id, "TEST FIXTURE finish with the current note.", ResearchMode.QUICK, 1, setOf(ProviderId.CLAUDE))
        val session = workflow.inspect(id).sessionId!!
        val person = database.discussionDao().participants(session).single()
        val waiting = engine.reportProviderFault(session, person.id, ProviderFault.ASSISTED_WAITING, true)
        assertTrue(waiting.statusNote.contains("waiting"))
        assertTrue(database.councilDao().tasksForSession(session).any { it.state == AssistedTaskState.AWAITING_USER_RESPONSE.name })
        val skipped = engine.skipParticipant(session, person.id)
        assertTrue(skipped.statusNote.contains("not disabled globally"))
        assertEquals("SKIPPED", database.discussionDao().participants(session).single().availability)
        assertEquals(0, database.councilDao().providerConfigCount())
        val finished = engine.finishNow(session)
        assertTrue(finished.statusNote.contains("not fully verified"))
        assertEquals(0, database.discussionDao().session(session)!!.requestsConsumed)
        database.close()
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, "continuity-restart.db").allowMainThreadQueries().build()
        val stored = reopened.discussionDao().session(session)!!
        assertEquals(SessionState.COMPLETED.name, stored.state)
        assertEquals(0, stored.requestsConsumed)
        reopened.close()
    }

    @Test
    fun pausedSessionSurvivesReopenWithoutResumingItself() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("continuity-pause.db")
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "continuity-pause.db").allowMainThreadQueries().build()
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                listOf(EligibleParticipant(ProviderId.GEMINI, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED))
            },
            clock = { 8_000L },
            ids = { java.util.UUID.randomUUID().toString() },
        )
        val workflow = CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { 8_000L })
        database.councilDao().insertConversation(ConversationEntity("pause", "Pause", ResearchMode.STANDARD.name, 1L, 1L))
        workflow.start("pause", "TEST FIXTURE pause across restart.", ResearchMode.STANDARD, 2, setOf(ProviderId.GEMINI))
        val session = workflow.inspect("pause").sessionId!!
        val count = database.councilDao().tasksForSession(session).size
        engine.pause(session)
        database.close()
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, "continuity-pause.db").allowMainThreadQueries().build()
        val stored = reopened.discussionDao().session(session)!!
        assertEquals(SessionState.PAUSED.name, stored.state)
        assertEquals("PAUSED", stored.dispatchHold)
        assertEquals(count, reopened.councilDao().tasksForSession(session).size)
        assertEquals(0, stored.requestsConsumed)
        reopened.close()
    }

    @Test
    fun notificationOmitsTheQuestion() {
        val text = DiscussionNotificationCopy.privateText("TEST FIXTURE continuity", "Independent research", 1, 2, 3, true)
        assertTrue(text.contains("Completed 1"))
        assertTrue(text.contains("Pending 2"))
        assertTrue(text.contains("Active participants 3"))
        assertTrue(text.contains("Waiting for your response"))
        assertFalse(DiscussionNotificationCopy.publicText().contains("TEST FIXTURE"))
    }

    private fun harness(providers: List<ProviderId>): Harness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                providers.map { EligibleParticipant(it, assisted = true, billing = BillingEligibility.USER_CONFIGURATION_REQUIRED) }
            },
            clock = { 6_000L },
            ids = { java.util.UUID.randomUUID().toString() },
        )
        return Harness(database, CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { 6_000L }), engine)
    }

    private data class Harness(
        val database: AppDatabase,
        val workflow: CouncilWorkflow,
        val engine: OrchestrationEngine,
    )
}

private suspend fun OrchestrationEngine.copyStop(documents: GeneratedDocumentService, conversationId: String, sessionId: String): String {
    documents.cancelPending(conversationId)
    cancel(sessionId)
    return conversationId
}

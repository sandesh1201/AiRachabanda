package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.ConclusionKind
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResolutionStatus
import com.airachabanda.app.orchestration.AccuracyBenchmark
import com.airachabanda.app.orchestration.ClaimLedger
import com.airachabanda.app.orchestration.CorrectionPropagation
import com.airachabanda.app.orchestration.CouncilWorkflow
import com.airachabanda.app.orchestration.CritiquePrompt
import com.airachabanda.app.orchestration.DeferredSourceRetriever
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.EvidenceNotes
import com.airachabanda.app.orchestration.LocalMath
import com.airachabanda.app.orchestration.ObjectionExtractor
import com.airachabanda.app.orchestration.ObjectionLifecycle
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.orchestration.ParticipantSource
import com.airachabanda.app.orchestration.ReviewClassification
import com.airachabanda.app.orchestration.ReviewIndependence
import com.airachabanda.app.orchestration.VerificationStop
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.ClaimEntity
import com.airachabanda.app.persistence.ConclusionEntity
import com.airachabanda.app.persistence.ConversationEntity
import com.airachabanda.app.persistence.ResearchTaskEntity
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
class Milestone007Test {
    @Test
    fun localMathVerifiesSupportedClaimsAndRejectsUnsafeExpressions() {
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("17 * 23 = 391").status)
        assertEquals(LocalMath.Status.CONTRADICTED, LocalMath.check("17 * 23 = 392").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("(2 + 3) * 4 = 20").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("2 + 3 * 4 = 14").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("3/4 = 0.75").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("3:4 = 0.75").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("20% of 50 = 10").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("50 * 20% = 10").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("1 km to m = 1000").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("100 C to F = 212").status)
        assertEquals(LocalMath.Status.VERIFIED, LocalMath.check("round 1.235 to 2 = 1.24").status)
        assertEquals(LocalMath.Status.UNVERIFIED, LocalMath.check("17 * 23").status)
        assertEquals(LocalMath.Status.REJECTED, LocalMath.check("2 / 0").status)
        assertEquals(LocalMath.Status.REJECTED, LocalMath.check("sqrt(9)").status)
        assertEquals(LocalMath.Status.REJECTED, LocalMath.check("while (true) { 1 }").status)
        assertEquals(LocalMath.Status.REJECTED, LocalMath.check("(".repeat(20) + "1" + ")".repeat(20)).status)
        assertEquals(LocalMath.Status.REJECTED, LocalMath.check("1+".repeat(80)).status)
        assertEquals(ClaimLedger.Kind.MATHEMATICAL, ClaimLedger.kindFor("17 * 23 = 391"))
        assertEquals(ClaimLedger.Kind.SOURCE_DEPENDENT, ClaimLedger.kindFor("TEST FIXTURE the bridge is 14 meters long."))
    }

    @Test
    fun evidenceObjectionsIndependenceAndBenchmarkStayBounded() {
        val citation = EvidenceNotes.citation("https://example.test/fixture")
        assertEquals(EvidenceKindCheck.UNVERIFIED, citation.status)
        assertTrue(citation.excerpt.contains("not verified"))
        val retrieval = DeferredSourceRetriever.retrieve(citation.excerpt)
        assertFalse(retrieval.performed)
        assertFalse(retrieval.supportsClaim)
        assertFalse(ObjectionLifecycle.disagreementResolves())
        val blocked = ObjectionLifecycle.transition(ResolutionStatus.OPEN, ResolutionStatus.CORRECTED, "Another participant disagrees.")
        assertFalse(blocked.accepted)
        val blank = ObjectionLifecycle.transition(ResolutionStatus.OPEN, ResolutionStatus.UNDER_REVIEW, " ")
        assertFalse(blank.accepted)
        val opened = ObjectionLifecycle.transition(ResolutionStatus.UNRESOLVED, ResolutionStatus.OPEN, "New contradictory evidence was recorded.")
        assertTrue(opened.accepted)
        assertEquals(
            ReviewClassification.SAME_MODEL_REVIEW,
            ReviewIndependence.classify("host-a", "shared-model", "host-b", "shared-model"),
        )
        assertEquals(
            ReviewClassification.CROSS_MODEL_REVIEW,
            ReviewIndependence.classify("host-a", "model-one", "host-b", "model-two"),
        )
        val summary = ReviewIndependence.summarize(
            reviewer = participant("model-one", "a"),
            comparedWith = listOf(participant("model-two", "b")),
        )
        assertTrue(summary.disclosure.contains("Majority agreement is not proof"))
        assertTrue(summary.disclosure.contains("same source"))
        val prompt = CritiquePrompt.prepare(
            reviewerName = "TEST FIXTURE reviewer",
            modeLabel = "Standard",
            roundNumber = 1,
            messages = listOf(
                ChatMessage("m", "c", ProviderId.CLAUDE, MessageSource.USER_SUPPLIED_PROVIDER_ATTRIBUTED, "TEST FIXTURE claim.", 1, 1L),
            ),
            disclosure = "TEST FIXTURE",
        )
        assertTrue(prompt.contains("fabricated citations"))
        assertTrue(prompt.contains("no objection was found"))
        assertTrue(ObjectionExtractor.extract("No material error was identified.").isEmpty())
        val stop = VerificationStop.assess(
            userStopped = false,
            deadlineReached = false,
            budgetExhausted = true,
            requirementsAddressed = false,
            materialVerificationStillJustified = true,
            verifierAvailable = false,
            insufficientEvidence = false,
            unresolvedDisagreement = false,
        )
        assertEquals(VerificationStop.Reason.BUDGET, stop.reason)
        val report = AccuracyBenchmark.report(
            AccuracyBenchmark.HeldOutQuestion("q1", "TEST FIXTURE", "391"),
            listOf(
                AccuracyBenchmark.Submission(
                    arm = AccuracyBenchmark.Arm.DISCUSSION_WITH_ADVOCATE,
                    answer = "17 * 23 = 391",
                    citationValid = false,
                    falseObjections = 1,
                    corrections = 0,
                    errorsIntroduced = 0,
                    unresolved = 0,
                    requests = 2,
                    latencyMs = 5L,
                ),
            ),
        )
        assertTrue(report.contains("not a measured improvement"))
        assertTrue(report.contains("Live benchmarking is deferred") || report.contains("TEST FIXTURE") || report.contains("DISCUSSION_WITH_ADVOCATE"))
        assertFalse(report.contains("accuracy improved"))
    }

    @Test
    fun correctionsPropagateAndRestartKeepsTheOriginalClaim() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("milestone-007.db")
        val harness = harness(context)
        val id = "claims"
        harness.database.councilDao().insertConversation(ConversationEntity(id, "Claims", ResearchMode.QUICK.name, 1L, 1L))
        harness.workflow.start(id, "TEST FIXTURE product check.", ResearchMode.QUICK, 3, setOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val session = harness.workflow.inspect(id).sessionId!!
        val people = harness.database.discussionDao().participants(session)
        assertEquals(3, people.size)
        assertTrue(
            people.all { person ->
                harness.database.discussionDao().modelIdentity(person.id)?.userDeclaredModelId?.startsWith("TEST FIXTURE") == true
            },
        )
        val researchTasks = harness.database.councilDao().tasksForSession(session).filter { it.kind == "RESEARCH" }
        assertEquals(3, researchTasks.size)
        researchTasks.dropLast(1).forEach { task ->
            harness.workflow.importTask(id, task.id, "17 * 23 = 391", "TEST FIXTURE participant 1")
        }
        val original = harness.database.discussionDao().claims(session).first()
        val parsed = ClaimLedger.parse(original.statement)
        assertEquals(ClaimLedger.Kind.MATHEMATICAL, parsed.kind)
        assertEquals("17 * 23 = 391", parsed.body)
        assertTrue(harness.database.discussionDao().verifications(session).any { it.outcome == "SUPPORTED" && it.targetClaimId == original.id })
        harness.database.discussionDao().insertClaim(
            ClaimEntity(
                id = "dependent-claim",
                sessionId = session,
                messageId = null,
                authorParticipantId = original.authorParticipantId,
                roundNumber = 1,
                statement = ClaimLedger.format(ClaimLedger.Kind.LOGICAL_CONCLUSION, researchTasks.first().id, "TEST FIXTURE the product settles the question.", dependsOn = original.id),
                createdAtEpochMs = 7_100L,
            ),
        )
        harness.database.councilDao().insertTask(
            ResearchTaskEntity(
                id = "pending-review",
                sessionId = session,
                conversationId = id,
                providerId = ProviderId.CHATGPT.name,
                roundNumber = 1,
                state = AssistedTaskState.PENDING.name,
                prompt = "TEST FIXTURE pending review",
                createdAtEpochMs = 7_100L,
                updatedAtEpochMs = 7_100L,
                targetId = original.id,
            ),
        )
        harness.database.councilDao().insertTask(
            ResearchTaskEntity(
                id = "finished-note",
                sessionId = session,
                conversationId = id,
                providerId = ProviderId.GEMINI.name,
                roundNumber = 1,
                state = AssistedTaskState.COMPLETED.name,
                prompt = "TEST FIXTURE completed note",
                createdAtEpochMs = 7_100L,
                updatedAtEpochMs = 7_100L,
            ),
        )
        harness.database.discussionDao().insertConclusion(
            ConclusionEntity(id = "old-final", sessionId = session, kind = ConclusionKind.FINAL.name, body = "TEST FIXTURE outdated final.", roundNumber = 1, createdAtEpochMs = 7_000L),
        )
        harness.now = 8_000L
        harness.engine.correctClaim(session, original.id, "17 * 23 = 391 remains the checked product.")
        val stored = harness.database.discussionDao().claims(session)
        assertEquals("17 * 23 = 391", ClaimLedger.parse(stored.first { it.id == original.id }.statement).body)
        assertTrue(stored.any { ClaimLedger.parse(it.statement).corrects == original.id })
        assertTrue(harness.database.discussionDao().conclusions(session).any { it.body.contains("dependent-claim") })
        assertEquals(AssistedTaskState.CANCELLED.name, harness.database.councilDao().task("pending-review")!!.state)
        assertEquals(AssistedTaskState.COMPLETED.name, harness.database.councilDao().task("finished-note")!!.state)
        assertNull(harness.workflow.inspect(id).finalAnswer)
        assertTrue(harness.workflow.inspect(id).claimLines.any { it.contains("verified calculation") })
        harness.workflow.importTask(id, researchTasks.last().id, "17 * 23 = 391", "TEST FIXTURE participant 3")
        harness.workflow.advance(id)
        val synthesis = harness.database.councilDao().tasksForSession(session).first { it.kind == "SYNTHESIS" }
        assertTrue(synthesis.prompt.contains("remains the checked product"))
        assertTrue(synthesis.prompt.contains("not the final answer"))
        val objection = harness.workflow.recordObjection(id, "TEST FIXTURE this objection is not settled by disagreement.")
        assertTrue(objection.contains("open"))
        val objectionId = harness.database.discussionDao().objections(session).single().id
        harness.engine.transitionObjection(session, objectionId, ResolutionStatus.CORRECTED, "Another participant disagrees.")
        assertEquals(ResolutionStatus.OPEN.name, harness.database.discussionDao().objections(session).single().resolution)
        harness.engine.transitionObjection(session, objectionId, ResolutionStatus.UNRESOLVED, "The stored material does not settle the objection.")
        val objectionTarget = harness.database.discussionDao().objections(session).single().targetClaimId
        harness.engine.recordContradictoryEvidence(session, objectionTarget, "TEST FIXTURE a later record conflicts with the objection.")
        assertEquals(ResolutionStatus.OPEN.name, harness.database.discussionDao().objections(session).single().resolution)
        val cited = "cited"
        harness.database.councilDao().insertConversation(ConversationEntity(cited, "Cited", ResearchMode.QUICK.name, 2L, 2L))
        harness.workflow.start(cited, "TEST FIXTURE citation.", ResearchMode.QUICK, 3, setOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI))
        val citedSession = harness.workflow.inspect(cited).sessionId!!
        val citedTask = harness.database.councilDao().tasksForSession(citedSession).first { it.kind == "RESEARCH" }
        harness.workflow.importTask(cited, citedTask.id, "See https://example.test/fixture for the TEST FIXTURE source.", "TEST FIXTURE participant 2")
        val note = harness.database.discussionDao().evidenceFor(
            harness.database.discussionDao().claims(citedSession).map { it.id },
        ).single().note
        assertTrue(note.contains("UNVERIFIED"))
        assertTrue(note.contains("not verified"))
        harness.database.close()
        val reopened = harness(context)
        val restored = reopened.database.discussionDao().claims(session).first { it.id == original.id }
        assertTrue(restored.statement.contains("17 * 23 = 391"))
        assertTrue(reopened.workflow.inspect(id).claimLines.any { it.contains("verified calculation") })
        reopened.database.close()
    }

    private fun participant(model: String, key: String) = EligibleParticipant(
        providerId = ProviderId.CLAUDE,
        assisted = true,
        billing = BillingEligibility.USER_CONFIGURATION_REQUIRED,
        userDeclaredModelId = "TEST FIXTURE $model",
        verifiedModelId = model,
        participantKey = key,
    )

    private fun harness(context: android.content.Context): Harness {
        var sequence = 0
        var now = 7_000L
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "milestone-007.db").allowMainThreadQueries().build()
        val engine = OrchestrationEngine(
            council = database.councilDao(),
            discussion = database.discussionDao(),
            participants = ParticipantSource {
                listOf(ProviderId.CLAUDE, ProviderId.CHATGPT, ProviderId.GEMINI).mapIndexed { index, provider ->
                    EligibleParticipant(
                        providerId = provider,
                        assisted = true,
                        billing = BillingEligibility.USER_CONFIGURATION_REQUIRED,
                        userDeclaredModelId = "TEST FIXTURE participant ${index + 1}",
                        verifiedModelId = "unknown",
                    )
                }
            },
            clock = { now },
            ids = { "m7-${sequence++}" },
        )
        return Harness(
            database = database,
            engine = engine,
            workflow = CouncilWorkflow(engine, database.councilDao(), database.discussionDao(), clock = { now }),
            readNow = { now },
            writeNow = { now = it },
        )
    }

    private class Harness(
        val database: AppDatabase,
        val engine: OrchestrationEngine,
        val workflow: CouncilWorkflow,
        private val readNow: () -> Long,
        private val writeNow: (Long) -> Unit,
    ) {
        var now: Long
            get() = readNow()
            set(value) = writeNow(value)
    }
}

private object EvidenceKindCheck {
    const val UNVERIFIED = "UNVERIFIED"
}

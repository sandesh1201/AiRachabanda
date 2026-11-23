package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ObjectionCategory
import com.airachabanda.app.core.model.ObjectionSeverity
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResolutionStatus
import com.airachabanda.app.core.model.SessionState
import com.airachabanda.app.persistence.CouncilDao
import com.airachabanda.app.persistence.DiscussionDao
import com.airachabanda.app.provider.ProviderRegistry

data class CouncilStage(
    val sessionId: String?,
    val stage: String,
    val statusNote: String,
    val participants: List<String>,
    val roles: List<String>,
    val pendingTasks: Int,
    val completedTasks: Int,
    val waitingForUser: Boolean,
    val objectionCount: Int,
    val unresolvedObjections: Int,
    val rebuttalCount: Int,
    val verification: String,
    val requestsUsed: Int,
    val requestBudget: Int,
    val finalAnswer: String?,
    val assistedOnly: Boolean,
    val critiqueNote: String,
    val reviewClassification: String = "Not assessed",
    val reviewDisclosure: String = "Reviewer independence has not been assessed.",
)

class CouncilWorkflow(
    private val engine: OrchestrationEngine,
    private val council: CouncilDao,
    private val discussion: DiscussionDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun inspect(conversationId: String): CouncilStage {
        val session = council.latestSession(conversationId)
        if (session == null) {
            return CouncilStage(
                sessionId = null,
                stage = "No session",
                statusNote = "Start a discussion to assign participants. No provider has been contacted.",
                participants = emptyList(),
                roles = emptyList(),
                pendingTasks = 0,
                completedTasks = 0,
                waitingForUser = false,
                objectionCount = 0,
                unresolvedObjections = 0,
                rebuttalCount = 0,
                verification = "Not started",
                requestsUsed = 0,
                requestBudget = 0,
                finalAnswer = null,
                assistedOnly = true,
                critiqueNote = "Critical review has not started.",
            )
        }
        val people = discussion.participants(session.id)
        val roles = people.flatMap { person ->
            discussion.roles(person.id).map { role ->
                val name = ProviderRegistry.definition(ProviderId.valueOf(person.providerId)).displayName
                "$name: ${roleLabel(role.role)}"
            }
        }
        val tasks = council.tasksForSession(session.id)
        val objections = discussion.objections(session.id)
        val rebuttals = objections.sumOf { discussion.rebuttals(it.id).size }
        val verification = discussion.verifications(session.id).lastOrNull()?.outcome ?: "Not recorded"
        val finalAnswer = discussion.conclusions(session.id).lastOrNull { it.kind == "FINAL" }?.body
        val assistedOnly = people.isEmpty() || people.all { it.participationMode == "ASSISTED" }
        val critique = tasks.firstOrNull { it.kind == "CRITIQUE" }
        val review = reviewAssessment(people)
        return CouncilStage(
            sessionId = session.id,
            stage = stageLabel(session.state),
            statusNote = session.statusNote,
            participants = people.map { person ->
                val name = ProviderRegistry.definition(ProviderId.valueOf(person.providerId)).displayName
                val identity = discussion.modelIdentity(person.id)
                val declared = identity?.userDeclaredModelId ?: ModelIdentity.UNKNOWN
                val verified = identity?.verifiedModelId ?: ModelIdentity.UNKNOWN
                "$name, ${person.participationMode.lowercase()}. Declared model $declared. Verified model $verified."
            },
            roles = roles,
            pendingTasks = tasks.count { it.state in OPEN },
            completedTasks = tasks.count { it.state == AssistedTaskState.COMPLETED.name },
            waitingForUser = session.state == SessionState.AWAITING_USER_RESPONSE.name ||
                tasks.any { it.state == AssistedTaskState.AWAITING_USER_RESPONSE.name },
            objectionCount = objections.size,
            unresolvedObjections = objections.count { it.resolution in UNRESOLVED },
            rebuttalCount = rebuttals,
            verification = verification,
            requestsUsed = session.requestsConsumed,
            requestBudget = session.requestBudget,
            finalAnswer = finalAnswer,
            assistedOnly = assistedOnly,
            critiqueNote = when {
                critique == null -> "Critical review has not produced a task."
                critique.state in OPEN -> "Critical review is waiting for your imported response."
                else -> "A critique response has been imported. It is not authenticated or independently verified."
            },
            reviewClassification = review.classification.name,
            reviewDisclosure = review.disclosure,
        )
    }

    private suspend fun reviewAssessment(people: List<com.airachabanda.app.persistence.ParticipantEntity>): ReviewAssessment {
        if (people.isEmpty()) {
            return ReviewAssessment(
                ReviewClassification.INDEPENDENCE_UNVERIFIED,
                "Review classification: INDEPENDENCE_UNVERIFIED. No participant is assigned.",
            )
        }
        val advocate = people.firstOrNull { person ->
            discussion.roles(person.id).any { it.role == ParticipantRole.DEVILS_ADVOCATE.name }
        }
        val researchers = people.filter { person ->
            discussion.roles(person.id).any { it.role == ParticipantRole.RESEARCHER.name }
        }
        if (advocate == null) {
            return ReviewAssessment(
                ReviewClassification.INDEPENDENCE_UNVERIFIED,
                "Review classification: INDEPENDENCE_UNVERIFIED. No Devil's Advocate is assigned.",
            )
        }
        val advocateModel = discussion.modelIdentity(advocate.id)?.verifiedModelId ?: ModelIdentity.UNKNOWN
        val peers = (if (researchers.isEmpty()) listOf(advocate) else researchers).map { person ->
            EligibleParticipant(
                providerId = ProviderId.valueOf(person.providerId),
                assisted = person.participationMode == "ASSISTED",
                billing = com.airachabanda.app.core.model.BillingEligibility.UNKNOWN,
                verifiedModelId = discussion.modelIdentity(person.id)?.verifiedModelId ?: ModelIdentity.UNKNOWN,
                participantKey = person.id,
            )
        }
        return ReviewIndependence.summarize(
            reviewer = EligibleParticipant(
                providerId = ProviderId.valueOf(advocate.providerId),
                assisted = advocate.participationMode == "ASSISTED",
                billing = com.airachabanda.app.core.model.BillingEligibility.UNKNOWN,
                verifiedModelId = advocateModel,
                participantKey = advocate.id,
            ),
            comparedWith = peers,
        )
    }

    suspend fun start(
        conversationId: String,
        question: String,
        mode: ResearchMode,
        roundLimit: Int,
        selectedProviders: Set<ProviderId>,
    ): String {
        val existing = council.latestSession(conversationId)
        if (existing != null && SessionState.valueOf(existing.state) !in SessionTransitions.terminal) {
            return "A discussion is already open. Pause, stop, or finish it before starting another."
        }
        val complexity = when (mode) {
            ResearchMode.QUICK -> QuestionComplexity.LOW
            ResearchMode.DEEP_RESEARCH -> QuestionComplexity.HIGH
            ResearchMode.STANDARD, ResearchMode.CUSTOM -> QuestionComplexity.MODERATE
        }
        val excluded = ProviderRegistry.all.map { it.id }.filter { it !in selectedProviders }.toSet()
        val snapshot = engine.acceptQuestion(
            ResearchStart(
                conversationId = conversationId,
                question = question,
                mode = mode,
                complexity = complexity,
                roundLimit = roundLimit.coerceIn(1, 8),
                requestBudget = roundLimit.coerceIn(0, 8),
                deadlineEpochMs = clock() + SESSION_LIMIT_MS,
                excludedProviders = excluded,
            ),
        )
        val assisted = inspect(conversationId).assistedOnly
        val assistance = if (assisted) {
            " Only assisted providers are available. You need to submit each prompt yourself and import the response."
        } else {
            ""
        }
        return "Discussion started. Stage: ${stageLabel(snapshot.state.name)}. Automated budget ${snapshot.requestsConsumed} of ${roundLimit.coerceIn(0, 8)}. Provider quotas are unknown. Session limit is 60 minutes.$assistance ${snapshot.statusNote}"
    }

    suspend fun prepareResearchPrompt(conversationId: String, providerId: ProviderId, question: String, mode: ResearchMode, roundLimit: Int, selected: Set<ProviderId>): String {
        val session = activeSession(conversationId)
            ?: return start(conversationId, question, mode, roundLimit, selected.ifEmpty { setOf(providerId) })
        val task = council.tasksForSession(session.id).firstOrNull {
            it.providerId == providerId.name && it.kind == "RESEARCH" && it.state in OPEN
        } ?: council.tasksForSession(session.id).firstOrNull { it.providerId == providerId.name && it.kind == "RESEARCH" }
        return task?.prompt ?: "No research task is open for ${ProviderRegistry.definition(providerId).displayName}."
    }

    suspend fun importTask(conversationId: String, taskId: String, body: String, declaredModel: String?): String {
        val session = council.latestSession(conversationId) ?: return "No discussion is open."
        val snapshot = engine.recordParticipantResponse(session.id, taskId, body, declaredModel)
        return "Imported a user-supplied response. It is not authenticated. Stage: ${stageLabel(snapshot.state.name)}."
    }

    suspend fun advance(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        val snapshot = engine.advance(session.id)
        return "Stage: ${stageLabel(snapshot.state.name)}. ${snapshot.statusNote}"
    }

    suspend fun prepareCritique(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "Critical review waits until a discussion is open."
        engine.advance(session.id)
        val task = council.tasksForSession(session.id).lastOrNull { it.kind == "CRITIQUE" }
            ?: return "Critical review is not ready. Finish the open research tasks first. No critique was invented."
        return task.prompt
    }

    suspend fun recordObjection(conversationId: String, explanation: String): String {
        val text = explanation.trim()
        if (text.isEmpty()) return "An objection needs your text. None was invented."
        val session = activeSession(conversationId) ?: return "No discussion is open."
        val claim = discussion.claims(session.id).lastOrNull() ?: return "There is no claim to challenge yet."
        val author = discussion.participants(session.id).firstOrNull { person ->
            discussion.roles(person.id).any { it.role == ParticipantRole.DEVILS_ADVOCATE.name }
        } ?: discussion.participants(session.id).firstOrNull()
            ?: return "No participant is available to author the objection."
        engine.recordObjection(
            sessionId = session.id,
            targetClaimId = claim.id,
            authorParticipantId = author.id,
            category = ObjectionCategory.UNSUPPORTED_ASSUMPTION,
            severity = ObjectionSeverity.MEDIUM,
            explanation = text,
            supportingEvidence = "",
            requestedVerification = "Check this objection against the imported material.",
        )
        return "Objection recorded as open. It is untrusted until it is checked. No confidence score was assigned."
    }

    suspend fun requestRebuttal(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        val objection = discussion.objections(session.id).lastOrNull { it.resolution in UNRESOLVED }
            ?: return "There is no open objection to rebut."
        val actor = actorFor(session.id, ParticipantRole.DEVILS_ADVOCATE) ?: return "No Devil's Advocate is assigned."
        val result = engine.submitAction(
            ProtocolRequest(
                sessionId = session.id,
                actorParticipantId = actor,
                action = CouncilAction.REQUEST_REBUTTAL,
                body = "Request a rebuttal for objection ${objection.id}.",
                targetId = objection.id,
            ),
        )
        return result.reason
    }

    suspend fun requestVerification(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        val actor = actorFor(session.id, ParticipantRole.EVIDENCE_VERIFIER)
            ?: actorFor(session.id, ParticipantRole.MODERATOR)
            ?: return "No evidence verifier is assigned."
        val result = engine.submitAction(
            ProtocolRequest(
                sessionId = session.id,
                actorParticipantId = actor,
                action = CouncilAction.REQUEST_VERIFICATION,
                body = "Request verification of the recorded claims and objections.",
            ),
        )
        return result.reason
    }

    suspend fun prepareSynthesis(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        engine.continueReview(session.id)
        val task = council.tasksForSession(session.id).lastOrNull { it.kind == "SYNTHESIS" }
        return task?.prompt ?: inspect(conversationId).statusNote
    }

    suspend fun pause(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        return "Stage: ${stageLabel(engine.pause(session.id).state.name)}."
    }

    suspend fun resume(conversationId: String): String {
        val session = council.latestSession(conversationId) ?: return "No discussion is open."
        return "Stage: ${stageLabel(engine.resume(session.id).state.name)}."
    }

    suspend fun stop(conversationId: String): String {
        val session = council.latestSession(conversationId) ?: return "No discussion is open."
        return "Stage: ${stageLabel(engine.cancel(session.id).state.name)}."
    }

    suspend fun skip(conversationId: String, taskId: String) {
        val session = council.latestSession(conversationId) ?: return
        engine.skipTask(session.id, taskId)
    }

    private suspend fun activeSession(conversationId: String) = council.latestSession(conversationId)?.takeIf {
        SessionState.valueOf(it.state) !in SessionTransitions.terminal
    }

    private suspend fun actorFor(sessionId: String, role: ParticipantRole): String? =
        discussion.participants(sessionId).firstOrNull { person ->
            discussion.roles(person.id).any { it.role == role.name }
        }?.id

    private companion object {
        const val SESSION_LIMIT_MS = 60L * 60L * 1000L
        val OPEN = setOf(
            AssistedTaskState.PENDING.name,
            AssistedTaskState.READY.name,
            AssistedTaskState.RUNNING.name,
            AssistedTaskState.AWAITING_USER_RESPONSE.name,
            AssistedTaskState.DEFERRED.name,
        )
        val UNRESOLVED = setOf(
            ResolutionStatus.OPEN.name,
            ResolutionStatus.UNRESOLVED.name,
            ResolutionStatus.INSUFFICIENT_EVIDENCE.name,
        )
    }
}

private fun stageLabel(state: String): String = when (state) {
    "CREATED" -> "Created"
    "PLANNING" -> "Planning"
    "INDEPENDENT_RESEARCH" -> "Independent research"
    "COLLECTING_RESPONSES" -> "Collecting responses"
    "PRELIMINARY_ANALYSIS" -> "Preliminary findings"
    "CRITICAL_REVIEW" -> "Critical review"
    "REBUTTAL" -> "Rebuttals"
    "EVIDENCE_VERIFICATION" -> "Verification"
    "REASSESSMENT" -> "Reassessment"
    "SYNTHESIS" -> "Synthesis"
    "COMPLETED" -> "Completed"
    "AWAITING_USER_RESPONSE" -> "Waiting for your response"
    "PAUSED" -> "Paused"
    "BLOCKED" -> "Blocked"
    "CANCELLED" -> "Stopped"
    "FAILED" -> "Failed"
    else -> state
}

private fun roleLabel(role: String): String = when (role) {
    "MODERATOR" -> "Moderator"
    "RESEARCHER" -> "Researcher"
    "ANALYST" -> "Analyst"
    "DOMAIN_SPECIALIST" -> "Domain Specialist"
    "DEVILS_ADVOCATE" -> "Devil's Advocate"
    "EVIDENCE_VERIFIER" -> "Evidence Verifier"
    "FINAL_SYNTHESIZER" -> "Final Synthesizer"
    else -> role
}

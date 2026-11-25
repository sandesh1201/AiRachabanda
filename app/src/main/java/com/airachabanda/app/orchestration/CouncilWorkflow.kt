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
    val exchanges: List<String> = emptyList(),
    val recordedSynthesis: String = "",
    val planNote: String = "",
    val stoppingReason: String = "",
    val taskGraph: List<String> = emptyList(),
    val claimLines: List<String> = emptyList(),
    val researchLines: List<String> = emptyList(),
    val participantControls: List<ParticipantControl> = emptyList(),
    val activeParticipantCount: Int = 0,
    val continuityNote: String = "",
)

data class ParticipantControl(
    val id: String,
    val label: String,
    val availability: String,
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
        val verifications = discussion.verifications(session.id)
        val verification = verifications.lastOrNull()?.outcome ?: "Not recorded"
        val claims = discussion.claims(session.id)
        val latestFinal = discussion.conclusions(session.id).lastOrNull { it.kind == "FINAL" }
        val correctionAt = claims.filter { ClaimLedger.parse(it.statement).corrects.isNotEmpty() }.maxOfOrNull { it.createdAtEpochMs }
        val staleFinal = latestFinal != null && correctionAt != null &&
            CorrectionPropagation.finalIsStale(latestFinal.createdAtEpochMs, correctionAt)
        val finalAnswer = if (staleFinal) null else latestFinal?.body
        val assistedOnly = people.isEmpty() || people.all { it.participationMode == "ASSISTED" }
        val critique = tasks.firstOrNull { it.kind == "CRITIQUE" }
        val review = reviewAssessment(people)
        val evidence = if (claims.isEmpty()) emptyList() else discussion.evidenceFor(claims.map { it.id })
        val dependencies = discussion.dependenciesForSession(session.id)
        val exchanges = discussion.protocolActions(session.id).map { action ->
            val outcome = if (action.accepted) "accepted" else "rejected"
            "${action.action} $outcome. ${action.reason}"
        }
        val synthesis = DiscussionSynthesis.compose(
            DiscussionSynthesis.Input(
                importedFinal = finalAnswer,
                claims = claims.map { claim ->
                    val parsed = ClaimLedger.parse(claim.statement)
                    "${parsed.kind.name}: ${parsed.body}"
                },
                evidence = evidence.map { it.locator },
                assumptions = objections.filter { it.category == "UNSUPPORTED_ASSUMPTION" }.map { it.explanation },
                corrections = claims.map { ClaimLedger.parse(it.statement) }.filter { it.corrects.isNotEmpty() }.map { it.body },
                alternatives = objections.filter { it.category == "ALTERNATIVE_EXPLANATION" }.map { it.explanation },
                unresolved = objections.filter { it.resolution in UNRESOLVED }.map { it.explanation },
                participants = people.map { person ->
                    val name = ProviderRegistry.definition(ProviderId.valueOf(person.providerId)).displayName
                    val identity = discussion.modelIdentity(person.id)
                    "$name. Declared ${identity?.userDeclaredModelId ?: ModelIdentity.UNKNOWN}. Verified ${identity?.verifiedModelId ?: ModelIdentity.UNKNOWN}."
                },
                verification = verification,
                independence = review.disclosure,
                verifiedCalculations = verifications.filter { it.outcome == "SUPPORTED" }.map { it.note },
            ),
        )
        val claimLines = claims.map { claim ->
            val parsed = ClaimLedger.parse(claim.statement)
            val outcome = verifications.lastOrNull { it.targetClaimId == claim.id }?.outcome
            val math = when {
                parsed.kind == ClaimLedger.Kind.MATHEMATICAL && outcome == "SUPPORTED" -> "verified calculation"
                parsed.kind == ClaimLedger.Kind.MATHEMATICAL && outcome == "CONTRADICTED" -> "contradicted calculation"
                parsed.kind == ClaimLedger.Kind.MATHEMATICAL -> "unverified calculation"
                else -> "participant assertion"
            }
            val related = objections.count { it.targetClaimId == claim.id }
            val correction = if (parsed.corrects.isNotEmpty()) " Corrects an earlier claim." else ""
            "${parsed.kind.name}. $math. Objections $related.$correction ${parsed.body}"
        }
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
            exchanges = exchanges,
            recordedSynthesis = synthesis,
            planNote = session.planNote,
            stoppingReason = session.stoppingReason,
            taskGraph = tasks.map { task ->
                val waiting = dependencies.count { it.taskId == task.id }
                val dependency = if (waiting == 0) "no waiting dependency" else "depends on $waiting task(s)"
                val objective = task.objective.ifBlank { "No separate objective was stored." }
                "${task.kind} ${task.state}. $dependency. Assigned to ${task.providerId}. $objective"
            },
            claimLines = claimLines,
            researchLines = researchLines(evidence, council.eventsForConversation(session.conversationId)),
            participantControls = people.map { person ->
                val name = ProviderRegistry.definition(ProviderId.valueOf(person.providerId)).displayName
                ParticipantControl(person.id, name, person.availability)
            },
            activeParticipantCount = people.count { it.availability == "ELIGIBLE" },
            continuityNote = when {
                people.isEmpty() -> "No AI participant is available. Independent multi-model review is unavailable. Local document processing, mathematical verification, public-source research, and document generation remain available. No AI response was invented."
                people.count { it.availability == "ELIGIBLE" } <= 1 -> "Independent multi-model review is unavailable because only one participant is eligible."
                else -> ""
            },
        )
    }

    suspend fun searchPublic(conversationId: String, query: String, connector: String, consent: Boolean): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        return engine.searchPublicSource(session.id, query, connector, null, consent)
    }

    suspend fun retrievePublicUrl(conversationId: String, url: String, consent: Boolean): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        return engine.retrievePublicUrl(session.id, url, null, consent)
    }

    private fun researchLines(
        evidence: List<com.airachabanda.app.persistence.EvidenceReferenceEntity>,
        events: List<com.airachabanda.app.persistence.WorkflowEventEntity>,
    ): List<String> {
        val failures = events.filter { it.type == "RESEARCH_FAILED" || it.type == "RESEARCH_PROPOSAL" || it.type == "RESEARCH_RETRIEVED" }
            .map { "${it.type.replace('_', ' ')}. ${it.summary}" }
        val sources = evidence.map { EvidenceNotes.parse(it.note) }
            .filter { it.kind == EvidenceKind.RETRIEVED_SOURCE }
            .map { record ->
                val published = record.published.ifBlank { "publication date not in the source" }
                val title = record.title.ifBlank { "Untitled source" }
                "Source $title. Published $published. Retrieved ${record.retrieved}. Verification ${record.verification.ifBlank { "NOT_RETRIEVED" }}. ${record.canonical}"
            }
        return (failures + sources).ifEmpty { emptyList() }
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
        custom: DiscussionBudgets? = null,
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
        val budgets = DiscussionBudgets.resolve(
            mode,
            custom ?: DiscussionBudgets.standard().copy(rounds = roundLimit.coerceIn(1, DiscussionBudgets.MAX_ROUNDS)),
        )
        val capabilities = if (budgets.verificationRequired) setOf(RoleAssignment.VERIFICATION) else emptySet()
        val accepted = engine.acceptQuestion(
            ResearchStart(
                conversationId = conversationId,
                question = question,
                mode = mode,
                complexity = complexity,
                requiredCapabilities = capabilities,
                roundLimit = budgets.rounds,
                requestBudget = budgets.aiRequests,
                deadlineEpochMs = clock() + budgets.deadlineMinutes * 60_000L,
                excludedProviders = excluded,
                concurrencyLimit = budgets.concurrency,
                delegationDepthLimit = budgets.delegationDepth,
                researchSources = budgets.researchSources,
                researchRequests = budgets.researchRequests,
            ),
        )
        val snapshot = engine.runInstalledModel(accepted.sessionId)
        val assisted = inspect(conversationId).assistedOnly
        val assistance = if (assisted) {
            " Only assisted providers are available. You need to submit each prompt yourself and import the response."
        } else {
            ""
        }
        return "Discussion started. Stage: ${stageLabel(snapshot.state.name)}. Automated budget ${snapshot.requestsConsumed} of ${budgets.aiRequests}. Discussion rounds ${budgets.rounds}. Provider quotas are unknown. Session limit is ${budgets.deadlineMinutes} minutes.$assistance ${snapshot.statusNote}"
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
        if (snapshot.statusNote.contains("not recorded") || snapshot.statusNote.contains("late response")) return snapshot.statusNote
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

    suspend fun addContext(conversationId: String, text: String, invalidateOpenWork: Boolean): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        engine.addUserInstruction(session.id, text, invalidateOpenWork)
        return if (invalidateOpenWork) {
            "The correction was added to this discussion. Open research tasks were cancelled."
        } else {
            "The information was added to this discussion. Completed work was kept."
        }
    }

    suspend fun requestFollowUp(conversationId: String, instruction: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        val actor = actorFor(session.id, ParticipantRole.RESEARCHER)
            ?: actorFor(session.id, ParticipantRole.MODERATOR)
            ?: return "No participant can accept a follow-up."
        val claim = discussion.claims(session.id).lastOrNull()
        val result = engine.submitAction(
            ProtocolRequest(
                sessionId = session.id,
                actorParticipantId = actor,
                action = CouncilAction.REQUEST_ADDITIONAL_RESEARCH,
                body = DiscussionExchange.taskPrompt(
                    sender = actor,
                    recipient = actor,
                    purpose = "ADDITIONAL_RESEARCH",
                    claim = claim?.statement.orEmpty(),
                    expectedOutput = "A sourced answer to the follow-up, or a statement that no source was supplied.",
                    roundNumber = session.reviewRound.coerceAtLeast(1),
                    depth = 1,
                    instruction = instruction,
                ),
                targetId = claim?.id,
                purpose = "ADDITIONAL_RESEARCH",
                expectedOutput = "A sourced answer to the follow-up, or a statement that no source was supplied.",
                recipientParticipantId = actor,
            ),
        )
        engine.runInstalledModel(session.id)
        return result.reason
    }

    suspend fun requestAnotherReview(conversationId: String): String {
        val session = activeSession(conversationId) ?: return "No discussion is open."
        val advocate = actorFor(session.id, ParticipantRole.DEVILS_ADVOCATE)
            ?: return "No Devil's Advocate is assigned. Independent review is unavailable, and no objection was invented."
        val snapshot = engine.continueReview(session.id)
        return "Another review stays with the assigned Devil's Advocate. ${snapshot.statusNote}"
    }

    suspend fun stop(conversationId: String): String {
        val session = council.latestSession(conversationId) ?: return "No discussion is open."
        val snapshot = engine.cancel(session.id)
        return "Stage: ${stageLabel(snapshot.state.name)}. ${snapshot.statusNote}"
    }

    suspend fun finishNow(conversationId: String): String {
        val session = council.latestSession(conversationId) ?: return "No discussion is open."
        return engine.finishNow(session.id).statusNote
    }

    suspend fun skipParticipant(conversationId: String, participantId: String): String {
        val session = council.latestSession(conversationId) ?: return "No discussion is open."
        return engine.skipParticipant(session.id, participantId).statusNote
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
            AssistedTaskState.QUEUED.name,
        )
        val UNRESOLVED = setOf(
            ResolutionStatus.OPEN.name,
            ResolutionStatus.UNDER_REVIEW.name,
            ResolutionStatus.REBUTTAL_REQUESTED.name,
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

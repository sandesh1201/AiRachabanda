package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ConclusionKind
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ObjectionCategory
import com.airachabanda.app.core.model.ObjectionSeverity
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResolutionStatus
import com.airachabanda.app.core.model.SessionState
import com.airachabanda.app.core.model.VerificationOutcome
import com.airachabanda.app.persistence.ClaimEntity
import com.airachabanda.app.persistence.ConclusionEntity
import com.airachabanda.app.persistence.CouncilDao
import com.airachabanda.app.persistence.DiscussionDao
import com.airachabanda.app.persistence.DiscussionRoundEntity
import com.airachabanda.app.persistence.EvidenceReferenceEntity
import com.airachabanda.app.persistence.MessageEntity
import com.airachabanda.app.persistence.ModelIdentityEntity
import com.airachabanda.app.persistence.ObjectionEntity
import com.airachabanda.app.persistence.ParticipantEntity
import com.airachabanda.app.persistence.ParticipantRoleEntity
import com.airachabanda.app.persistence.RebuttalEntity
import com.airachabanda.app.persistence.RequestBudgetEntryEntity
import com.airachabanda.app.persistence.ResearchSessionEntity
import com.airachabanda.app.persistence.ResearchTaskEntity
import com.airachabanda.app.persistence.TaskDependencyEntity
import com.airachabanda.app.persistence.VerificationResultEntity
import com.airachabanda.app.persistence.WorkflowCheckpointEntity
import com.airachabanda.app.persistence.WorkflowEventEntity
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderRegistry
import com.airachabanda.app.provider.dispatch.PreparedCall
import com.airachabanda.app.provider.dispatch.PreparedProviderDispatch
import com.airachabanda.app.provider.openrouter.DispatchCommand
import com.airachabanda.app.provider.openrouter.DispatchOutcome
import com.airachabanda.app.provider.openrouter.OpenRouterCandidate
import com.airachabanda.app.provider.openrouter.OpenRouterDispatch
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

fun interface ParticipantSource {
    suspend fun load(): List<EligibleParticipant>
}

class DatabaseParticipantSource(
    private val dao: CouncilDao,
) : ParticipantSource {
    override suspend fun load(): List<EligibleParticipant> {
        val rows = dao.providerConfigs()
        return ProviderRegistry.all.mapNotNull { definition ->
            val row = rows.firstOrNull { it.providerId == definition.id.name }
            val participation = row?.participationMode?.let(ParticipationMode::valueOf)
                ?: definition.defaultParticipation
            val billing = row?.billingEligibility?.let(BillingEligibility::valueOf)
                ?: definition.defaultBilling
            when (ProviderAccess.active(definition.id)) {
                ParticipationMode.ASSISTED -> EligibleParticipant(definition.id, assisted = true, billing = billing)
                ParticipationMode.AUTOMATED -> EligibleParticipant(definition.id, assisted = false, billing = billing)
                ParticipationMode.UNAVAILABLE -> null
            }
        }
    }
}

data class ResearchStart(
    val conversationId: String,
    val question: String,
    val mode: ResearchMode,
    val complexity: QuestionComplexity,
    val requiredCapabilities: Set<String> = emptySet(),
    val roundLimit: Int,
    val requestBudget: Int,
    val deadlineEpochMs: Long? = null,
    val knownQuotas: Map<ProviderId, Int?> = emptyMap(),
    val excludedProviders: Set<ProviderId> = emptySet(),
)

data class SessionSnapshot(
    val sessionId: String,
    val state: SessionState,
    val requestsConsumed: Int,
    val reviewRound: Int,
    val statusNote: String,
)

class OrchestrationEngine(
    private val council: CouncilDao,
    private val discussion: DiscussionDao,
    private val participants: ParticipantSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: OpenRouterDispatch? = null,
    private val prepared: PreparedProviderDispatch? = null,
    private val researchTransport: ResearchTransport = PublicHttp(),
    private val nameResolver: (String) -> List<java.net.InetAddress> = { host -> java.net.InetAddress.getAllByName(host).toList() },
) {
    private val mutex = Mutex()

    suspend fun acceptQuestion(start: ResearchStart): SessionSnapshot = mutex.withLock {
        require(start.question.isNotBlank()) { "A research question is required." }
        require(start.roundLimit in 1..8) { "Round limit must stay between 1 and 8." }
        require(start.requestBudget >= 0) { "Request budget cannot be negative." }
        require(council.conversation(start.conversationId) != null) { "Conversation not found." }
        val now = clock()
        val sessionId = ids()
        val question = start.question.trim()
        council.insertSession(
            ResearchSessionEntity(
                id = sessionId,
                conversationId = start.conversationId,
                researchMode = start.mode.name,
                createdAtEpochMs = now,
                state = SessionState.CREATED.name,
                question = question,
                roundLimit = start.roundLimit,
                requestBudget = start.requestBudget,
                deadlineEpochMs = start.deadlineEpochMs,
                updatedAtEpochMs = now,
                complexity = start.complexity.name,
                statusNote = "Session created. No provider has been contacted.",
            ),
        )
        checkpoint(sessionId, SessionState.CREATED, "Session created. No provider has been contacted.")
        if (expired(fresh(sessionId))) {
            return@withLock fail(sessionId, "The research deadline had already passed. No request was sent.")
        }
        move(sessionId, SessionState.PLANNING, "Planning participant roles.")
        val eligible = select(participants.load()).filter { it.providerId !in start.excludedProviders }
        if (eligible.isEmpty()) {
            move(sessionId, SessionState.BLOCKED, "No eligible participant. No response was invented.")
            return@withLock snapshot(sessionId)
        }
        val plan = DiscussionPlanner.plan(
            DiscussionPlanner.Signals(
                objective = question,
                mode = start.mode,
                explicitCapabilities = start.requiredCapabilities,
                participantCount = eligible.size,
                requestBudget = start.requestBudget,
                deadlineEpochMs = start.deadlineEpochMs,
                nowEpochMs = now,
                consequences = start.mode == ResearchMode.DEEP_RESEARCH,
            ),
        )
        discussion.updatePlan(
            id = sessionId,
            planSource = plan.source,
            concurrencyLimit = plan.concurrencyLimit,
            delegationDepthLimit = plan.delegationDepthLimit,
            fanOutLimit = plan.fanOutLimit,
            planNote = plan.note,
            verificationRequired = plan.verificationRequired,
            updatedAt = clock(),
        )
        val capabilities = start.requiredCapabilities.toMutableSet()
        capabilities += plan.addedCapabilities
        val grants = RoleAssignment.assign(
            participants = eligible,
            complexity = start.complexity,
            requiredCapabilities = capabilities,
            remainingBudget = start.requestBudget,
            knownQuotas = start.knownQuotas,
            excludedProviders = start.excludedProviders,
        )
        persistParticipants(sessionId, eligible, grants)
        openRound(sessionId, 1)
        val userContext = council.messages(start.conversationId)
            .filter { it.source == MessageSource.USER.name }
            .map { it.body }
        if (plan.decompose && TaskGraph.hasCycle(plan.tasks.flatMap { work -> work.dependsOn.map { dependency -> work.localId to dependency } })) {
            discussion.updatePlan(
                id = sessionId,
                planSource = plan.source,
                concurrencyLimit = plan.concurrencyLimit,
                delegationDepthLimit = plan.delegationDepthLimit,
                fanOutLimit = plan.fanOutLimit,
                planNote = "${plan.note} Circular dependency rejected. One research task was kept.",
                verificationRequired = plan.verificationRequired,
                updatedAt = clock(),
            )
            createIndependentResearch(sessionId, grants, question, userContext)
        } else if (plan.decompose) {
            createPlannedWork(sessionId, grants, plan.tasks, userContext, question)
            applySchedule(sessionId)
        } else {
            createIndependentResearch(sessionId, grants, question, userContext)
        }
        enterCollection(sessionId)
        pump(sessionId)
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = start.conversationId,
                providerId = null,
                type = "DISCUSSION_STARTED",
                summary = "Discussion started in ${start.mode.name}. No provider has been contacted.",
                createdAtEpochMs = clock(),
            ),
        )
        snapshot(sessionId)
    }

    suspend fun advance(sessionId: String): SessionSnapshot = mutex.withLock {
        guard(sessionId) ?: return@withLock snapshot(sessionId)
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun recordParticipantResponse(
        sessionId: String,
        taskId: String,
        body: String,
        userDeclaredModelId: String? = null,
    ): SessionSnapshot = mutex.withLock {
        val text = body.trim()
        require(text.isNotEmpty()) { "A response body is required." }
        val session = guard(sessionId) ?: return@withLock snapshot(sessionId)
        val task = council.task(taskId) ?: throw IllegalArgumentException("Task not found.")
        require(task.sessionId == sessionId) { "Task does not belong to this session." }
        if (task.state !in OPEN_TASKS) return@withLock snapshot(sessionId)
        val now = clock()
        val messageId = ids()
        council.insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = session.conversationId,
                providerId = task.providerId,
                source = MessageSource.USER_SUPPLIED_PROVIDER_ATTRIBUTED.name,
                body = text,
                roundNumber = task.roundNumber,
                createdAtEpochMs = now,
            ),
        )
        council.insertImport(
            com.airachabanda.app.persistence.ImportedResponseEntity(
                id = ids(),
                conversationId = session.conversationId,
                taskId = taskId,
                providerId = task.providerId,
                roundNumber = task.roundNumber,
                body = text,
                userSupplied = true,
                authenticated = false,
                createdAtEpochMs = now,
                userDeclaredModelId = ModelIdentity.declared(userDeclaredModelId),
                verifiedModelId = ModelIdentity.UNKNOWN,
            ),
        )
        council.updateTaskState(taskId, AssistedTaskState.COMPLETED.name, now)
        task.participantId?.let { participantId ->
            discussion.updateDeclaredModel(participantId, ModelIdentity.declared(userDeclaredModelId))
            discussion.insertClaim(
                ClaimEntity(
                    id = ids(),
                    sessionId = sessionId,
                    messageId = messageId,
                    authorParticipantId = participantId,
                    roundNumber = task.roundNumber,
                    statement = ClaimLedger.format(
                        kind = ClaimLedger.kindFor(text),
                        taskId = task.id,
                        body = text,
                    ),
                    createdAtEpochMs = now,
                ),
            )
            recordStructuredNotes(sessionId, task, text)
        }
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = session.conversationId,
                providerId = task.providerId,
                type = "RESPONSE_RECORDED",
                summary = "A supplied response was recorded. AI Rachabanda did not contact the provider.",
                createdAtEpochMs = now,
            ),
        )
        val current = fresh(sessionId)
        if (current.state == SessionState.AWAITING_USER_RESPONSE.name && current.awaitState != null) {
            move(
                sessionId,
                SessionState.valueOf(current.awaitState),
                "A supplied response was recorded.",
                await = null,
            )
        }
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun recordClaim(
        sessionId: String,
        authorParticipantId: String,
        statement: String,
        evidence: List<Pair<String, String>> = emptyList(),
    ): String = mutex.withLock {
        val text = statement.trim()
        require(text.isNotEmpty()) { "A claim needs a statement." }
        if (guard(sessionId) == null) throw IllegalStateException("The session is not accepting claims.")
        val claimId = ids()
        val now = clock()
        discussion.insertClaim(
            ClaimEntity(
                id = claimId,
                sessionId = sessionId,
                messageId = null,
                authorParticipantId = authorParticipantId,
                roundNumber = fresh(sessionId).reviewRound.coerceAtLeast(1),
                statement = text,
                createdAtEpochMs = now,
            ),
        )
        evidence.forEach { (locator, note) ->
            if (locator.isNotBlank()) {
                discussion.insertEvidence(
                    EvidenceReferenceEntity(
                        id = ids(),
                        claimId = claimId,
                        locator = locator.trim(),
                        note = note.trim(),
                        createdAtEpochMs = now,
                    ),
                )
            }
        }
        claimId
    }

    suspend fun recordObjection(
        sessionId: String,
        targetClaimId: String,
        authorParticipantId: String,
        category: ObjectionCategory,
        severity: ObjectionSeverity,
        explanation: String,
        supportingEvidence: String,
        requestedVerification: String,
        userDeclaredModelId: String? = null,
    ): String = mutex.withLock {
        require(explanation.isNotBlank()) { "An objection needs an explanation." }
        if (guard(sessionId) == null) throw IllegalStateException("The session is not accepting objections.")
        require(discussion.claim(targetClaimId)?.sessionId == sessionId) { "Claim not found in this session." }
        val id = ids()
        discussion.insertObjection(
            ObjectionEntity(
                id = id,
                sessionId = sessionId,
                targetClaimId = targetClaimId,
                authorParticipantId = authorParticipantId,
                userDeclaredModelId = ModelIdentity.declared(userDeclaredModelId),
                verifiedModelId = ModelIdentity.UNKNOWN,
                roundNumber = fresh(sessionId).reviewRound.coerceAtLeast(1),
                category = category.name,
                severity = severity.name,
                explanation = explanation.trim(),
                supportingEvidence = supportingEvidence.trim(),
                requestedVerification = requestedVerification.trim(),
                resolution = ResolutionStatus.OPEN.name,
                createdAtEpochMs = clock(),
            ),
        )
        id
    }

    suspend fun continueReview(sessionId: String): SessionSnapshot = mutex.withLock {
        val session = guard(sessionId) ?: return@withLock snapshot(sessionId)
        if (session.state !in setOf(SessionState.CRITICAL_REVIEW.name, SessionState.AWAITING_USER_RESPONSE.name, SessionState.REBUTTAL.name, SessionState.EVIDENCE_VERIFICATION.name)) {
            note(sessionId, "Critical review cannot continue from ${session.state}.")
            return@withLock snapshot(sessionId)
        }
        val critiqueOpen = council.tasksForSession(sessionId).any {
            it.kind == KIND_CRITIQUE && it.roundNumber == session.reviewRound && it.state in OPEN_TASKS
        }
        if (critiqueOpen) {
            note(sessionId, "Critical review is still waiting on a participant.")
            return@withLock snapshot(sessionId)
        }
        val unresolved = discussion.objections(sessionId).filter { it.resolution in UNRESOLVED }
        val needsRebuttal = unresolved.filter { discussion.rebuttals(it.id).isEmpty() }
        if (needsRebuttal.isNotEmpty()) {
            needsRebuttal.forEach { objection ->
                val claim = discussion.claim(objection.targetClaimId) ?: return@forEach
                val author = discussion.participants(sessionId).first { it.id == claim.authorParticipantId }
                createTask(
                    participant = author,
                    role = ParticipantRole.RESEARCHER,
                    kind = KIND_REBUTTAL,
                    prompt = "Respond to objection ${objection.id}. Use evidence. Do not invent citations. Objection: ${objection.explanation}",
                    round = session.reviewRound,
                    targetId = objection.id,
                )
            }
            move(sessionId, SessionState.REBUTTAL, "Rebuttals were requested for open objections.")
            pump(sessionId)
            return@withLock snapshot(sessionId)
        }
        val verifier = roleParticipant(sessionId, ParticipantRole.EVIDENCE_VERIFIER)
        val verificationOpen = council.tasksForSession(sessionId).any { it.kind == KIND_VERIFICATION && it.state in OPEN_TASKS }
        val verificationDone = discussion.verifications(sessionId).isNotEmpty()
        if (verifier != null && !verificationDone && !verificationOpen && discussion.claims(sessionId).isNotEmpty()) {
            createTask(
                participant = verifier,
                role = ParticipantRole.EVIDENCE_VERIFIER,
                kind = KIND_VERIFICATION,
                prompt = "Check the recorded claims and objections against their evidence. Do not invent sources.",
                round = session.reviewRound,
                targetId = null,
            )
            move(sessionId, SessionState.EVIDENCE_VERIFICATION, "Evidence verification was requested.")
            pump(sessionId)
            return@withLock snapshot(sessionId)
        }
        if (session.state != SessionState.REASSESSMENT.name) {
            move(sessionId, SessionState.REASSESSMENT, "The review round is ready for reassessment.")
        }
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun recordRebuttal(
        sessionId: String,
        objectionId: String,
        body: String,
        resolution: ResolutionStatus? = null,
    ): SessionSnapshot = mutex.withLock {
        val text = body.trim()
        require(text.isNotEmpty()) { "A rebuttal needs text." }
        val session = guard(sessionId) ?: return@withLock snapshot(sessionId)
        val objection = discussion.objection(objectionId)
        require(objection?.sessionId == sessionId) { "Objection not found in this session." }
        val author = discussion.claim(objection.targetClaimId)?.authorParticipantId
            ?: throw IllegalArgumentException("Claim author not found.")
        discussion.insertRebuttal(
            RebuttalEntity(
                id = ids(),
                objectionId = objectionId,
                authorParticipantId = author,
                body = text,
                createdAtEpochMs = clock(),
            ),
        )
        if (resolution != null) {
            discussion.updateObjectionResolution(objectionId, resolution.name)
        }
        council.tasksForSession(sessionId)
            .filter { it.kind == KIND_REBUTTAL && it.targetId == objectionId && it.state in OPEN_TASKS }
            .forEach { council.updateTaskState(it.id, AssistedTaskState.COMPLETED.name, clock()) }
        if (session.state == SessionState.AWAITING_USER_RESPONSE.name && session.awaitState == SessionState.REBUTTAL.name) {
            move(sessionId, SessionState.REBUTTAL, "A rebuttal was recorded. The original objection was kept.")
        }
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun recordVerification(
        sessionId: String,
        verifierParticipantId: String,
        outcome: VerificationOutcome,
        note: String,
        targetClaimId: String? = null,
        objectionId: String? = null,
    ): SessionSnapshot = mutex.withLock {
        guard(sessionId) ?: return@withLock snapshot(sessionId)
        discussion.insertVerification(
            VerificationResultEntity(
                id = ids(),
                sessionId = sessionId,
                targetClaimId = targetClaimId,
                objectionId = objectionId,
                verifierParticipantId = verifierParticipantId,
                outcome = outcome.name,
                note = note.trim(),
                createdAtEpochMs = clock(),
            ),
        )
        council.tasksForSession(sessionId)
            .filter { it.kind == KIND_VERIFICATION && it.state in OPEN_TASKS }
            .forEach { council.updateTaskState(it.id, AssistedTaskState.COMPLETED.name, clock()) }
        val session = fresh(sessionId)
        if (session.state == SessionState.AWAITING_USER_RESPONSE.name && session.awaitState == SessionState.EVIDENCE_VERIFICATION.name) {
            move(sessionId, SessionState.EVIDENCE_VERIFICATION, "A verification result was recorded.")
        }
        if (fresh(sessionId).state == SessionState.EVIDENCE_VERIFICATION.name) {
            move(sessionId, SessionState.REASSESSMENT, "Verification finished. The result is stored separately from the original claims.")
        }
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun recordPreliminaryConclusion(sessionId: String, body: String): SessionSnapshot = mutex.withLock {
        val text = body.trim()
        require(text.isNotEmpty()) { "A conclusion needs text." }
        val session = guard(sessionId) ?: return@withLock snapshot(sessionId)
        discussion.insertConclusion(
            ConclusionEntity(
                id = ids(),
                sessionId = sessionId,
                kind = ConclusionKind.PRELIMINARY.name,
                body = text,
                roundNumber = session.reviewRound,
                createdAtEpochMs = clock(),
            ),
        )
        note(sessionId, "A preliminary conclusion was added. Earlier conclusions were kept.")
        snapshot(sessionId)
    }

    suspend fun recordFinalConclusion(sessionId: String, body: String): SessionSnapshot = mutex.withLock {
        val text = body.trim()
        require(text.isNotEmpty()) { "A final conclusion needs text." }
        val session = guard(sessionId) ?: return@withLock snapshot(sessionId)
        discussion.insertConclusion(
            ConclusionEntity(
                id = ids(),
                sessionId = sessionId,
                kind = ConclusionKind.FINAL.name,
                body = text,
                roundNumber = session.reviewRound,
                createdAtEpochMs = clock(),
            ),
        )
        council.tasksForSession(sessionId)
            .filter { it.kind == KIND_SYNTHESIS && it.state in OPEN_TASKS }
            .forEach { council.updateTaskState(it.id, AssistedTaskState.COMPLETED.name, clock()) }
        if (session.state != SessionState.COMPLETED.name) {
            move(sessionId, SessionState.COMPLETED, "Final conclusion recorded. Objections and earlier conclusions were kept.")
        }
        snapshot(sessionId)
    }

    suspend fun routeFollowUp(sessionId: String, providerId: ProviderId, question: String): SessionSnapshot = mutex.withLock {
        val text = question.trim()
        require(text.isNotEmpty()) { "A follow-up question is required." }
        val session = guard(sessionId) ?: return@withLock snapshot(sessionId)
        val collecting = session.state == SessionState.INDEPENDENT_RESEARCH.name ||
            session.state == SessionState.COLLECTING_RESPONSES.name ||
            (session.state == SessionState.AWAITING_USER_RESPONSE.name && session.awaitState == SessionState.INDEPENDENT_RESEARCH.name)
        if (!collecting) {
            note(sessionId, "A follow-up was not created outside research collection.")
            return@withLock snapshot(sessionId)
        }
        val roundCount = discussion.rounds(sessionId).size
        if (roundCount >= session.roundLimit) {
            note(sessionId, "A follow-up was not created because the round limit has been reached.")
            return@withLock snapshot(sessionId)
        }
        val nextRound = roundCount + 1
        openRound(sessionId, nextRound)
        createTask(
            participant = participantFor(sessionId, providerId),
            role = ParticipantRole.RESEARCHER,
            kind = KIND_FOLLOW_UP,
            prompt = text,
            round = nextRound,
            targetId = null,
        )
        if (session.state != SessionState.COLLECTING_RESPONSES.name) {
            move(sessionId, SessionState.COLLECTING_RESPONSES, "A follow-up question was routed. Other participants were not cancelled.")
        }
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun pause(sessionId: String): SessionSnapshot = mutex.withLock {
        val session = fresh(sessionId)
        val state = SessionState.valueOf(session.state)
        if (state in SessionTransitions.terminal || state == SessionState.PAUSED) return@withLock snapshot(sessionId)
        move(sessionId, SessionState.PAUSED, "Session paused.", await = session.awaitState, resume = session.state)
        snapshot(sessionId)
    }

    suspend fun resume(sessionId: String): SessionSnapshot = mutex.withLock {
        val session = fresh(sessionId)
        if (session.state != SessionState.PAUSED.name) return@withLock snapshot(sessionId)
        if (expired(session)) {
            return@withLock fail(sessionId, "The research deadline passed while the session was paused. No answer was invented.")
        }
        val target = session.resumeState?.let(SessionState::valueOf) ?: SessionState.AWAITING_USER_RESPONSE
        move(sessionId, target, "Session resumed.", await = session.awaitState, resume = session.resumeState)
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun cancel(sessionId: String): SessionSnapshot = mutex.withLock {
        val session = fresh(sessionId)
        if (SessionState.valueOf(session.state) in SessionTransitions.terminal) return@withLock snapshot(sessionId)
        cancelOpenTasks(sessionId, AssistedTaskState.CANCELLED)
        discussion.updateStoppingReason(
            sessionId,
            VerificationStop.assess(
                userStopped = true,
                deadlineReached = false,
                budgetExhausted = false,
                requirementsAddressed = false,
                materialVerificationStillJustified = false,
                verifierAvailable = false,
                insufficientEvidence = false,
                unresolvedDisagreement = false,
            ).note,
            clock(),
        )
        move(sessionId, SessionState.CANCELLED, "Session cancelled. No further requests will be prepared.")
        snapshot(sessionId)
    }

    suspend fun skipTask(sessionId: String, taskId: String): SessionSnapshot = mutex.withLock {
        val task = council.task(taskId) ?: return@withLock snapshot(sessionId)
        if (task.sessionId != sessionId || task.state !in OPEN_TASKS) return@withLock snapshot(sessionId)
        council.updateTaskState(taskId, AssistedTaskState.SKIPPED.name, clock())
        val session = fresh(sessionId)
        if (session.state == SessionState.AWAITING_USER_RESPONSE.name && session.awaitState != null) {
            move(
                sessionId,
                SessionState.valueOf(session.awaitState),
                "A task was skipped. No response was invented.",
                await = null,
            )
        }
        pump(sessionId)
        snapshot(sessionId)
    }

    suspend fun submitAction(request: ProtocolRequest): ProtocolResult = mutex.withLock {
        val session = discussion.session(request.sessionId)
            ?: return@withLock ProtocolResult(false, "Session not found.", null)
        val actor = discussion.participants(session.id).firstOrNull { it.id == request.actorParticipantId }
        val roles = actor?.let { discussion.roles(it.id).map { role -> role.role }.toSet() }.orEmpty()
        val people = discussion.participants(session.id)
        val duplicate = discussion.protocolActions(session.id).any {
            it.accepted && it.actorParticipantId == request.actorParticipantId && it.action == request.action.name &&
                it.targetId == request.targetId && it.body == request.body.trim()
        }
        val acceptedFollowUps = discussion.protocolActions(session.id).count {
            it.accepted && it.actorParticipantId == request.actorParticipantId &&
                it.action == CouncilAction.REQUEST_ADDITIONAL_RESEARCH.name
        }
        val decision = CouncilProtocol.validate(
            request = request,
            sessionState = session.state,
            actorFound = actor != null,
            actorRoles = roles,
            requestBudget = session.requestBudget,
            requestsConsumed = session.requestsConsumed,
            duplicate = duplicate,
            recipientFound = request.recipientParticipantId.isNullOrBlank() || people.any { it.id == request.recipientParticipantId },
            maxDepth = session.delegationDepthLimit,
            fanOutExceeded = request.action == CouncilAction.REQUEST_ADDITIONAL_RESEARCH && acceptedFollowUps >= session.fanOutLimit,
        )
        discussion.insertProtocol(
            com.airachabanda.app.persistence.ProtocolActionEntity(
                id = ids(),
                sessionId = session.id,
                actorParticipantId = request.actorParticipantId,
                action = request.action.name,
                targetId = request.targetId,
                body = request.body.trim(),
                accepted = decision.accepted,
                reason = decision.reason,
                createdAtEpochMs = clock(),
            ),
        )
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = session.conversationId,
                providerId = actor?.providerId,
                type = if (decision.accepted) "PARTICIPANT_REQUEST" else "PARTICIPANT_REQUEST_REJECTED",
                summary = "${request.action.name} ${if (decision.accepted) "accepted" else "rejected"}. ${decision.reason}",
                createdAtEpochMs = clock(),
            ),
        )
        if (!decision.accepted || actor == null) {
            return@withLock ProtocolResult(false, decision.reason, snapshot(session.id))
        }
        when (request.action) {
            CouncilAction.CHALLENGE_CLAIM -> {
                val claim = discussion.claim(request.targetId.orEmpty())
                if (claim == null || claim.sessionId != session.id) {
                    return@withLock ProtocolResult(false, "The claim is not in this session.", snapshot(session.id))
                }
                discussion.insertObjection(
                    ObjectionEntity(
                        id = ids(),
                        sessionId = session.id,
                        targetClaimId = claim.id,
                        authorParticipantId = actor.id,
                        userDeclaredModelId = ModelIdentity.UNKNOWN,
                        verifiedModelId = ModelIdentity.UNKNOWN,
                        roundNumber = session.reviewRound.coerceAtLeast(1),
                        category = com.airachabanda.app.core.model.ObjectionCategory.UNSUPPORTED_ASSUMPTION.name,
                        severity = com.airachabanda.app.core.model.ObjectionSeverity.MEDIUM.name,
                        explanation = request.body.trim(),
                        supportingEvidence = "",
                        requestedVerification = "Check the challenged claim against its evidence.",
                        resolution = com.airachabanda.app.core.model.ResolutionStatus.OPEN.name,
                        createdAtEpochMs = clock(),
                    ),
                )
            }
            CouncilAction.REQUEST_REBUTTAL -> {
                val objectionId = request.targetId
                val objection = objectionId?.let { discussion.objection(it) }
                if (objection == null || objection.sessionId != session.id) {
                    return@withLock ProtocolResult(false, "The objection is not in this session.", snapshot(session.id))
                }
                val claim = discussion.claim(objection.targetClaimId)
                val author = claim?.let { target ->
                    discussion.participants(session.id).firstOrNull { it.id == target.authorParticipantId }
                }
                if (author != null && discussion.rebuttals(objection.id).isEmpty()) {
                    createTask(
                        participant = author,
                        role = ParticipantRole.RESEARCHER,
                        kind = KIND_REBUTTAL,
                        prompt = "Respond to objection ${objection.id}. Do not invent citations. Objection: ${objection.explanation}",
                        round = session.reviewRound.coerceAtLeast(1),
                        targetId = objection.id,
                    )
                }
            }
            CouncilAction.REQUEST_VERIFICATION -> {
                val verifier = roleParticipant(session.id, ParticipantRole.EVIDENCE_VERIFIER)
                if (verifier == null) {
                    return@withLock ProtocolResult(false, "No evidence verifier is assigned.", snapshot(session.id))
                }
                createTask(
                    participant = verifier,
                    role = ParticipantRole.EVIDENCE_VERIFIER,
                    kind = KIND_VERIFICATION,
                    prompt = "Check the challenged material. Do not invent sources. ${request.body.trim()}",
                    round = session.reviewRound.coerceAtLeast(1),
                    targetId = request.targetId,
                )
            }
            CouncilAction.REQUEST_ADDITIONAL_RESEARCH -> {
                if (discussion.rounds(session.id).size >= session.roundLimit) {
                    return@withLock ProtocolResult(false, "The round limit has been reached.", snapshot(session.id))
                }
                val nextRound = discussion.rounds(session.id).size + 1
                openRound(session.id, nextRound)
                createTask(
                    participant = actor,
                    role = ParticipantRole.RESEARCHER,
                    kind = KIND_FOLLOW_UP,
                    prompt = request.body.trim(),
                    round = nextRound,
                    targetId = null,
                )
            }
            CouncilAction.SUBMIT_CORRECTION -> {
                discussion.insertClaim(
                    ClaimEntity(
                        id = ids(),
                        sessionId = session.id,
                        messageId = null,
                        authorParticipantId = actor.id,
                        roundNumber = session.reviewRound.coerceAtLeast(1),
                        statement = request.body.trim(),
                        createdAtEpochMs = clock(),
                    ),
                )
            }
            CouncilAction.RECOMMEND_SYNTHESIS -> {
                val critiqueOpen = council.tasksForSession(session.id).any {
                    it.kind == KIND_CRITIQUE && it.state in OPEN_TASKS
                }
                if (critiqueOpen) {
                    return@withLock ProtocolResult(false, "Critical review is still open.", snapshot(session.id))
                }
                ensureSynthesisTask(fresh(session.id))
            }
        }
        ProtocolResult(true, decision.reason, snapshot(session.id))
    }

    private suspend fun enterCollection(sessionId: String) {
        val tasks = council.tasksForSession(sessionId)
        val automatedOpen = tasks.any { it.automated && it.state in OPEN_TASKS }
        val assistedOpen = tasks.any { !it.automated && it.state in OPEN_TASKS }
        when {
            automatedOpen -> move(
                sessionId,
                SessionState.INDEPENDENT_RESEARCH,
                "Independent research started. An assisted participant does not block an automated participant.",
            )
            assistedOpen -> move(
                sessionId,
                SessionState.AWAITING_USER_RESPONSE,
                "Waiting for assisted research. No response was invented.",
                await = SessionState.INDEPENDENT_RESEARCH.name,
            )
            else -> move(sessionId, SessionState.BLOCKED, "No research task could be started.")
        }
    }

    private suspend fun pump(sessionId: String) {
        repeat(MAX_STEPS) {
            val session = fresh(sessionId)
            val state = SessionState.valueOf(session.state)
            if (state in SessionTransitions.terminal || state == SessionState.PAUSED || state == SessionState.BLOCKED) return
            if (expired(session)) {
                fail(sessionId, "The research deadline passed. Open tasks were cancelled. No answer was invented.")
                return
            }
            val scheduled = applySchedule(sessionId)
            if (!step(fresh(sessionId)) && !scheduled) return
        }
    }

    private suspend fun step(session: ResearchSessionEntity): Boolean {
        return when (SessionState.valueOf(session.state)) {
            SessionState.INDEPENDENT_RESEARCH, SessionState.COLLECTING_RESPONSES -> progressCollection(session)
            SessionState.PRELIMINARY_ANALYSIS -> openCritiqueOrSynthesis(session)
            SessionState.CRITICAL_REVIEW -> progressWaiting(session, KIND_CRITIQUE, SessionState.CRITICAL_REVIEW)
            SessionState.REBUTTAL -> progressWaiting(session, KIND_REBUTTAL, SessionState.REBUTTAL)
            SessionState.EVIDENCE_VERIFICATION -> {
                if (progressWaiting(session, KIND_VERIFICATION, SessionState.EVIDENCE_VERIFICATION)) return true
                val verification = council.tasksForSession(session.id).filter { it.kind == KIND_VERIFICATION }
                val stillOpen = verification.any { it.state in OPEN_TASKS || it.state == AssistedTaskState.QUEUED.name }
                if (verification.isNotEmpty() && !stillOpen) {
                    move(session.id, SessionState.REASSESSMENT, "Verification finished. The result is stored separately from the original claims.")
                    return true
                }
                false
            }
            SessionState.REASSESSMENT -> progressReassessment(session)
            SessionState.SYNTHESIS -> progressWaiting(session, KIND_SYNTHESIS, SessionState.SYNTHESIS)
            else -> false
        }
    }

    private suspend fun progressCollection(session: ResearchSessionEntity): Boolean {
        val tasks = council.tasksForSession(session.id).filter { it.kind in COLLECTION_KINDS }
        val open = tasks.filter { it.state in OPEN_TASKS }
        val automatedOpen = open.any { it.automated }
        val assistedOpen = open.any { !it.automated }
        return when {
            automatedOpen && session.state != SessionState.INDEPENDENT_RESEARCH.name -> {
                move(session.id, SessionState.INDEPENDENT_RESEARCH, "Automated research can continue while an assisted participant is still waiting.")
                true
            }
            !automatedOpen && assistedOpen && session.state != SessionState.AWAITING_USER_RESPONSE.name -> {
                move(
                    session.id,
                    SessionState.AWAITING_USER_RESPONSE,
                    "Waiting for an assisted participant. No automated participant is ready, and no response was invented.",
                    await = SessionState.INDEPENDENT_RESEARCH.name,
                )
                true
            }
            open.isEmpty() && tasks.any { it.state == AssistedTaskState.QUEUED.name } -> false
            open.isEmpty() && tasks.isNotEmpty() && session.state != SessionState.PRELIMINARY_ANALYSIS.name -> {
                move(session.id, SessionState.PRELIMINARY_ANALYSIS, "Research tasks are complete. No conclusion has been written yet.")
                true
            }
            else -> false
        }
    }

    private suspend fun openCritiqueOrSynthesis(session: ResearchSessionEntity): Boolean {
        val roles = allRoles(session.id)
        if (roles.any { it.role == ParticipantRole.DEVILS_ADVOCATE.name }) {
            if (session.reviewRound == 0) setReviewRound(session.id, 1)
            ensureCritiqueTasks(fresh(session.id))
            move(
                session.id,
                SessionState.CRITICAL_REVIEW,
                "Critical review is open. This critique is not independent verification unless a different verified model identity is on record.",
            )
            return true
        }
        val mode = ResearchMode.valueOf(session.researchMode)
        val disclosure = when (mode) {
            ResearchMode.QUICK -> "Quick mode did not run a critical review because no assisted reviewer was available and the automated request budget did not permit one."
            ResearchMode.STANDARD, ResearchMode.DEEP_RESEARCH -> "Critical review is unavailable. No reviewer could be assigned. No objection was invented."
            ResearchMode.CUSTOM -> "Critical review was not requested for this custom session."
        }
        if (openVerificationIfRequired(fresh(session.id))) return true
        if (roles.any { it.role == ParticipantRole.FINAL_SYNTHESIZER.name }) {
            ensureSynthesisTask(fresh(session.id))
            move(session.id, SessionState.SYNTHESIS, disclosure)
            return true
        }
        move(session.id, SessionState.COMPLETED, disclosure)
        return true
    }

    private fun englishTask(kind: String): String = when (kind) {
        KIND_CRITIQUE -> "critical review"
        KIND_REBUTTAL -> "rebuttal"
        KIND_VERIFICATION -> "verification"
        KIND_SYNTHESIS -> "synthesis"
        KIND_RESEARCH -> "research task"
        KIND_FOLLOW_UP -> "follow-up"
        KIND_ANALYSIS -> "analysis"
        else -> "task"
    }

    private suspend fun progressWaiting(
        session: ResearchSessionEntity,
        kind: String,
        workState: SessionState,
    ): Boolean {
        val tasks = council.tasksForSession(session.id).filter { it.kind == kind }
        val open = tasks.filter { it.state in OPEN_TASKS }
        val automatedOpen = open.any { it.automated }
        if (automatedOpen) return false
        if (open.isNotEmpty() && session.state != SessionState.AWAITING_USER_RESPONSE.name) {
            move(
                session.id,
                SessionState.AWAITING_USER_RESPONSE,
                "Waiting for an assisted ${englishTask(kind)}. No response was invented.",
                await = workState.name,
            )
            return true
        }
        return false
    }

    private suspend fun progressReassessment(session: ResearchSessionEntity): Boolean {
        val objections = discussion.objections(session.id)
        val explanations = objections.map { it.explanation.trim() }
        val repeated = explanations.size != explanations.toSet().size
        val unresolved = objections.any { it.resolution in UNRESOLVED }
        if (repeated) {
            if (allRoles(session.id).any { it.role == ParticipantRole.FINAL_SYNTHESIZER.name }) {
                ensureSynthesisTask(fresh(session.id))
                move(session.id, SessionState.SYNTHESIS, DiscussionStop.assess(
                    cancelled = false,
                    deadlinePassed = false,
                    reviewRound = session.reviewRound,
                    roundLimit = session.roundLimit,
                    remainingAutomatedBudget = 1,
                    automatedWorkStillOpen = false,
                    repeatedArgument = true,
                    researchComplete = true,
                    critiqueRequired = true,
                    critiqueComplete = true,
                    materialDisagreement = unresolved,
                ).note)
            } else {
                move(session.id, SessionState.COMPLETED, "A repeated argument stopped the discussion. Unresolved objections are preserved.")
            }
            return true
        }
        if (unresolved && session.reviewRound < session.roundLimit) {
            val next = session.reviewRound + 1
            setReviewRound(session.id, next)
            openRound(session.id, next)
            ensureCritiqueTasks(fresh(session.id))
            move(
                session.id,
                SessionState.CRITICAL_REVIEW,
                "Another critical review round started because objections remain and the round limit allows it.",
            )
            return true
        }
        if (openVerificationIfRequired(fresh(session.id))) return true
        if (allRoles(session.id).any { it.role == ParticipantRole.FINAL_SYNTHESIZER.name }) {
            ensureSynthesisTask(fresh(session.id))
            move(
                session.id,
                SessionState.SYNTHESIS,
                "Reassessment stopped. Unresolved objections are preserved. No final answer was invented.",
            )
            return true
        }
        move(session.id, SessionState.COMPLETED, "Reassessment stopped at the round limit. No final synthesizer was assigned.")
        return true
    }

    private suspend fun ensureCritiqueTasks(session: ResearchSessionEntity) {
        val existing = council.tasksForSession(session.id).any {
            it.kind == KIND_CRITIQUE && it.roundNumber == session.reviewRound
        }
        if (existing) return
        val advocate = roleParticipant(session.id, ParticipantRole.DEVILS_ADVOCATE) ?: return
        val messages = council.messages(session.conversationId).map { entity ->
            com.airachabanda.app.core.model.ChatMessage(
                id = entity.id,
                conversationId = entity.conversationId,
                providerId = entity.providerId?.let(ProviderId::valueOf),
                source = MessageSource.valueOf(entity.source),
                body = entity.body,
                roundNumber = entity.roundNumber,
                createdAtEpochMs = entity.createdAtEpochMs,
            )
        }
        val prompt = if (messages.any { it.source != MessageSource.USER && it.body.isNotBlank() }) {
            CritiquePrompt.prepare(
                reviewerName = ProviderRegistry.definition(ProviderId.valueOf(advocate.providerId)).displayName,
                modeLabel = session.researchMode,
                roundNumber = session.reviewRound,
                messages = messages,
                disclosure = "This critique is assisted. No underlying model identity is on record, so it is not independent verification.",
            )
        } else {
            "Critique the recorded claims in this session. Do not invent evidence, citations, or a confidence percentage."
        }
        createTask(
            participant = advocate,
            role = ParticipantRole.DEVILS_ADVOCATE,
            kind = KIND_CRITIQUE,
            prompt = prompt,
            round = session.reviewRound.coerceAtLeast(1),
            targetId = null,
        )
    }

    private suspend fun ensureSynthesisTask(session: ResearchSessionEntity) {
        val existing = council.tasksForSession(session.id).any { it.kind == KIND_SYNTHESIS }
        if (existing) return
        val synthesizer = roleParticipant(session.id, ParticipantRole.FINAL_SYNTHESIZER) ?: return
        val people = discussion.participants(session.id)
        val claims = discussion.claims(session.id)
        val objections = discussion.objections(session.id)
        val evidence = if (claims.isEmpty()) emptyList() else discussion.evidenceFor(claims.map { it.id })
        val corrections = council.messages(session.conversationId)
            .filter { it.source == MessageSource.USER.name && it.body != session.question }
            .map { it.body }
        val quality = DiscussionQuality.assess(
            DiscussionQuality.Check(
                requirementsAddressed = claims.isNotEmpty() || session.question.isNotBlank(),
                claimsMissingEvidence = claims.isNotEmpty() && evidence.isEmpty(),
                materialDisagreement = objections.any { it.resolution in UNRESOLVED },
                mathVerificationRequired = session.verificationRequired,
                mathVerificationDone = discussion.verifications(session.id).isNotEmpty(),
                critiqueCompletedOrUnavailable = people.none { person ->
                    discussion.roles(person.id).any { it.role == ParticipantRole.DEVILS_ADVOCATE.name }
                } || council.tasksForSession(session.id).any { it.kind == KIND_CRITIQUE && it.state == AssistedTaskState.COMPLETED.name },
                furtherResearchWouldHelp = false,
                budgetRemaining = (session.requestBudget - session.requestsConsumed).coerceAtLeast(0),
            ),
        )
        val verifications = discussion.verifications(session.id)
        val stop = VerificationStop.assess(
            userStopped = false,
            deadlineReached = false,
            budgetExhausted = false,
            requirementsAddressed = quality.readyForSynthesis,
            materialVerificationStillJustified = !quality.readyForSynthesis,
            verifierAvailable = roleParticipant(session.id, ParticipantRole.EVIDENCE_VERIFIER) != null,
            insufficientEvidence = verifications.any { it.outcome == com.airachabanda.app.core.model.VerificationOutcome.INSUFFICIENT_EVIDENCE.name } ||
                objections.any { it.resolution == ResolutionStatus.INSUFFICIENT_EVIDENCE.name },
            unresolvedDisagreement = objections.any {
                it.resolution in setOf(
                    ResolutionStatus.OPEN.name,
                    ResolutionStatus.UNDER_REVIEW.name,
                    ResolutionStatus.REBUTTAL_REQUESTED.name,
                    ResolutionStatus.UNRESOLVED.name,
                )
            },
        )
        discussion.updateStoppingReason(session.id, stop.note, clock())
        createTask(
            participant = synthesizer,
            role = ParticipantRole.FINAL_SYNTHESIZER,
            kind = KIND_SYNTHESIS,
            prompt = SynthesisBrief.prepare(
                SynthesisBrief.Input(
                    objective = session.question,
                    corrections = corrections + claims.map { ClaimLedger.parse(it.statement) }.filter { it.corrects.isNotEmpty() }.map { it.body },
                    findings = claims.map { ClaimLedger.parse(it.statement).body },
                    evidence = evidence.map { it.locator },
                    contradictions = objections.map { it.explanation },
                    unresolved = objections.filter { it.resolution in UNRESOLVED }.map { it.explanation },
                    verificationLimits = "${stop.note} ${quality.note}",
                    verifiedCalculations = verifications.filter { it.outcome == com.airachabanda.app.core.model.VerificationOutcome.SUPPORTED.name }.map { it.note },
                    assumptions = objections.filter { it.category == com.airachabanda.app.core.model.ObjectionCategory.UNSUPPORTED_ASSUMPTION.name }.map { it.explanation },
                    alternatives = objections.filter { it.category == com.airachabanda.app.core.model.ObjectionCategory.ALTERNATIVE_EXPLANATION.name }.map { it.explanation },
                    participants = people.map { person ->
                        val identity = discussion.modelIdentity(person.id)
                        "${person.providerId}. Declared ${identity?.userDeclaredModelId ?: ModelIdentity.UNKNOWN}. Verified ${identity?.verifiedModelId ?: ModelIdentity.UNKNOWN}."
                    },
                ),
            ),
            round = session.reviewRound.coerceAtLeast(1),
            targetId = null,
            objective = "Prepare the final synthesis from stored records.",
            requiredCapability = RoleAssignment.SYNTHESIS,
            expectedOutput = "A synthesis that keeps unresolved objections and does not invent evidence.",
        )
    }

    private suspend fun openVerificationIfRequired(session: ResearchSessionEntity): Boolean {
        if (!session.verificationRequired) return false
        if (council.tasksForSession(session.id).any { it.kind == KIND_VERIFICATION }) return false
        val verifier = roleParticipant(session.id, ParticipantRole.EVIDENCE_VERIFIER) ?: return false
        createTask(
            participant = verifier,
            role = ParticipantRole.EVIDENCE_VERIFIER,
            kind = KIND_VERIFICATION,
            prompt = "Check the numeric or explicit verification requirement. Do not invent a source. Record VERIFICATION outcome=INSUFFICIENT_EVIDENCE when no source was supplied.",
            round = session.reviewRound.coerceAtLeast(1),
            targetId = null,
            objective = "Verify the required calculation or explicit verification request.",
            requiredCapability = RoleAssignment.VERIFICATION,
            expectedOutput = "A verification result tied to a stored claim, or a statement that the evidence is insufficient.",
        )
        val from = SessionState.valueOf(fresh(session.id).state)
        if (SessionTransitions.permits(from, SessionState.EVIDENCE_VERIFICATION)) {
            move(session.id, SessionState.EVIDENCE_VERIFICATION, "Verification is required before synthesis. No answer was invented.")
        }
        return true
    }

    private suspend fun reservedReviewSlots(sessionId: String): Int {
        val people = discussion.participants(sessionId)
        val roles = people.flatMap { discussion.roles(it.id) }
        fun automated(role: ParticipantRole): Boolean = roles.any { grant ->
            grant.role == role.name &&
                people.firstOrNull { it.id == grant.participantId }?.participationMode == ParticipationMode.AUTOMATED.name
        }
        var slots = 0
        if (automated(ParticipantRole.DEVILS_ADVOCATE)) slots += 1
        if (automated(ParticipantRole.FINAL_SYNTHESIZER)) slots += 1
        return slots
    }

    private suspend fun createTask(
        participant: ParticipantEntity,
        role: ParticipantRole,
        kind: String,
        prompt: String,
        round: Int,
        targetId: String?,
        objective: String = "",
        parentTaskId: String? = null,
        requiredCapability: String = "",
        expectedOutput: String = "",
        priority: Int = 0,
        deadlineEpochMs: Long? = null,
        taskBudget: Int = 0,
        queued: Boolean = false,
    ): String {
        val session = fresh(participant.sessionId)
        val automated = participant.participationMode == ParticipationMode.AUTOMATED.name &&
            ZeroCostPolicy.automatedInferenceAllowed(
                ProviderId.valueOf(participant.providerId),
                BillingEligibility.valueOf(participant.billingEligibility),
            )
        var state = if (automated) AssistedTaskState.READY else AssistedTaskState.AWAITING_USER_RESPONSE
        if (queued) state = AssistedTaskState.QUEUED
        var markAutomated = false
        if (!queued && participant.participationMode == ParticipationMode.AUTOMATED.name) {
            val ceiling = if (kind == KIND_RESEARCH || kind == KIND_FOLLOW_UP) {
                (session.requestBudget - reservedReviewSlots(session.id)).coerceAtLeast(0)
            } else {
                session.requestBudget
            }
            if (!automated || session.requestsConsumed >= ceiling) {
                state = AssistedTaskState.BLOCKED
            } else {
                val consumed = session.requestsConsumed + 1
                discussion.consumeBudget(
                    RequestBudgetEntryEntity(
                        id = ids(),
                        sessionId = session.id,
                        participantId = participant.id,
                        amount = 1,
                        reason = "Reserved one automated request for $kind.",
                        createdAtEpochMs = clock(),
                    ),
                    consumed = consumed,
                    updatedAt = clock(),
                )
                markAutomated = true
            }
        }
        val now = clock()
        val taskId = ids()
        council.insertTask(
            ResearchTaskEntity(
                id = taskId,
                sessionId = session.id,
                conversationId = session.conversationId,
                providerId = participant.providerId,
                roundNumber = round,
                state = state.name,
                prompt = if (participant.participationMode == ParticipationMode.ASSISTED.name) {
                    AssistedPrompt.frame(
                        providerName = ProviderRegistry.definition(ProviderId.valueOf(participant.providerId)).displayName,
                        role = role.name.replace('_', ' ').lowercase(),
                        task = englishTask(kind),
                        body = prompt,
                    )
                } else {
                    prompt
                },
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                role = role.name,
                kind = kind,
                participantId = participant.id,
                automated = markAutomated,
                targetId = targetId,
                objective = objective.ifBlank { prompt.lineSequence().firstOrNull().orEmpty() },
                parentTaskId = parentTaskId,
                requiredCapability = requiredCapability,
                expectedOutput = expectedOutput,
                priority = priority,
                deadlineEpochMs = deadlineEpochMs,
                taskBudget = taskBudget,
            ),
        )
        return taskId
    }

    private suspend fun createIndependentResearch(
        sessionId: String,
        grants: List<RoleGrant>,
        question: String,
        userContext: List<String>,
    ) {
        val researchPrompt = TaskContext.initial(question, userContext)
        grants.filter { it.role == ParticipantRole.RESEARCHER }.forEach { grant ->
            val participant = participantFor(sessionId, grant.providerId)
            createTask(
                participant = participant,
                role = ParticipantRole.RESEARCHER,
                kind = KIND_RESEARCH,
                prompt = researchPrompt,
                round = 1,
                targetId = null,
                objective = question,
                requiredCapability = RoleAssignment.RESEARCH,
                expectedOutput = "An answer to the assigned request without another participant's initial answer.",
            )
        }
    }

    private suspend fun createPlannedWork(
        sessionId: String,
        grants: List<RoleGrant>,
        work: List<DiscussionPlanner.Work>,
        userContext: List<String>,
        question: String,
    ) {
        val researchers = grants.filter { it.role == ParticipantRole.RESEARCHER }
        val analyst = grants.firstOrNull { it.role == ParticipantRole.ANALYST } ?: researchers.firstOrNull() ?: return
        val localIds = mutableMapOf<String, String>()
        work.forEach { item ->
            val grant = if (item.kind == KIND_ANALYSIS) analyst else researchers[localIds.size % researchers.size.coerceAtLeast(1)]
            val participant = participantFor(sessionId, grant.providerId)
            val prompt = if (item.dependsOn.isEmpty()) {
                TaskContext.initial(item.objective, userContext.filter { !question.contains(it) || it == item.objective })
            } else {
                TaskContext.targeted(item.objective, emptyList(), emptyList(), emptyList())
            }
            val created = createTask(
                participant = participant,
                role = if (item.kind == KIND_ANALYSIS) ParticipantRole.ANALYST else ParticipantRole.RESEARCHER,
                kind = item.kind,
                prompt = prompt,
                round = 1,
                targetId = null,
                objective = item.objective,
                requiredCapability = item.capability,
                expectedOutput = item.expectedOutput,
                priority = item.priority,
                taskBudget = item.budget,
                queued = true,
            )
            localIds[item.localId] = created
        }
        work.forEach { item ->
            val taskId = localIds[item.localId] ?: return@forEach
            val parentId = item.parentLocalId?.let { localIds[it] }
            if (parentId != null) council.updateTaskParent(taskId, parentId)
            item.dependsOn.forEach { dependency ->
                val dependsOn = localIds[dependency] ?: return@forEach
                val edges = discussion.dependenciesForSession(sessionId).map { it.taskId to it.dependsOnTaskId }
                if (!TaskGraph.hasCycle(edges + (taskId to dependsOn))) {
                    discussion.insertDependency(TaskDependencyEntity(taskId, dependsOn))
                }
            }
        }
    }

    private suspend fun applySchedule(sessionId: String): Boolean {
        val session = fresh(sessionId)
        val tasks = council.tasksForSession(sessionId)
        val dependencies = discussion.dependenciesForSession(sessionId)
        val nodes = tasks.map { task ->
            TaskGraph.Node(
                id = task.id,
                state = task.state,
                automated = task.automated,
                dependsOn = dependencies.filter { it.taskId == task.id }.map { it.dependsOnTaskId }.toSet(),
                deadlineEpochMs = task.deadlineEpochMs,
                priority = task.priority,
            )
        }
        val promotion = TaskGraph.promote(nodes, session.concurrencyLimit, clock())
        promotion.cancel.forEach { id ->
            council.updateTaskState(id, AssistedTaskState.CANCELLED.name, clock())
        }
        if (promotion.cancel.isNotEmpty()) {
            discussion.updateStoppingReason(
                sessionId,
                "A task was cancelled because its dependency failed or its deadline passed.",
                clock(),
            )
        }
        val activate = promotion.activateAutomated + promotion.activateAssisted
        activate.forEach { id ->
            val task = tasks.first { it.id == id }
            val upstream = dependencies.filter { it.taskId == id }.map { it.dependsOnTaskId }
            val claims = discussion.claims(sessionId).filter { claim ->
                upstream.isEmpty() || tasks.filter { it.id in upstream }.any { it.participantId == claim.authorParticipantId }
            }.map { it.statement }
            val assisted = !task.automated
            val prompt = if (upstream.isEmpty()) {
                task.prompt
            } else {
                val targeted = TaskContext.targeted(task.objective, claims, emptyList(), emptyList())
                if (assisted) {
                    AssistedPrompt.frame(
                        providerName = ProviderRegistry.definition(ProviderId.valueOf(task.providerId)).displayName,
                        role = task.role.replace('_', ' ').lowercase(),
                        task = englishTask(task.kind),
                        body = targeted,
                    )
                } else {
                    targeted
                }
            }
            council.updateTaskContent(
                id = id,
                prompt = prompt,
                state = if (task.automated) AssistedTaskState.READY.name else AssistedTaskState.AWAITING_USER_RESPONSE.name,
                updatedAt = clock(),
            )
        }
        return promotion.cancel.isNotEmpty() || activate.isNotEmpty()
    }

    suspend fun dispatchTask(sessionId: String, taskId: String, apiKey: String, candidate: OpenRouterCandidate): DispatchOutcome = mutex.withLock {
        val task = council.tasksForSession(sessionId).firstOrNull { it.id == taskId }
            ?: return DispatchOutcome(OpenRouterFailure.POLICY, "The task is not in this session.", completed = false)
        val session = discussion.session(sessionId)
            ?: return DispatchOutcome(OpenRouterFailure.POLICY, "The session is not open.", completed = false)
        val remaining = session.requestBudget - session.requestsConsumed
        val bounded = candidate.copy(sessionBudgetRemaining = minOf(candidate.sessionBudgetRemaining, remaining))
        val active = dispatcher
            ?: return DispatchOutcome(OpenRouterFailure.POLICY, "No provider dispatcher is connected. No request was sent.", completed = false)
        return active.dispatch(
            DispatchCommand(
                sessionId = sessionId,
                conversationId = task.conversationId,
                taskId = taskId,
                prompt = task.prompt,
                candidate = bounded,
                apiKey = apiKey,
                roundNumber = task.roundNumber,
            ),
        )
    }

    suspend fun dispatchPrepared(sessionId: String, taskId: String, call: PreparedCall): DispatchOutcome = mutex.withLock {
        val task = council.tasksForSession(sessionId).firstOrNull { it.id == taskId }
            ?: return DispatchOutcome(OpenRouterFailure.POLICY, "The task is not in this session.", completed = false)
        val session = discussion.session(sessionId)
            ?: return DispatchOutcome(OpenRouterFailure.POLICY, "The session is not open.", completed = false)
        val remaining = session.requestBudget - session.requestsConsumed
        if (remaining <= 0) {
            return DispatchOutcome(OpenRouterFailure.BUDGET_BLOCKED, "The session request budget is exhausted. No request was sent.", completed = false)
        }
        val active = prepared
            ?: return DispatchOutcome(OpenRouterFailure.POLICY, "No Cloudflare or Gemini dispatcher is connected. No request was sent.", completed = false)
        return active.dispatch(
            sessionId = sessionId,
            conversationId = task.conversationId,
            taskId = taskId,
            roundNumber = task.roundNumber,
            prompt = task.prompt,
            sessionBudgetRemaining = remaining,
            call = call,
        )
    }

    private fun select(raw: List<EligibleParticipant>): List<EligibleParticipant> =
        raw.mapNotNull { candidate ->
            val automated = !candidate.assisted &&
                ZeroCostPolicy.automatedInferenceAllowed(candidate.providerId, candidate.billing)
            when {
                candidate.assisted -> candidate
                automated -> candidate
                else -> null
            }
        }.distinctBy { it.participantKey }

    private suspend fun persistParticipants(
        sessionId: String,
        eligible: List<EligibleParticipant>,
        grants: List<RoleGrant>,
    ) {
        val now = clock()
        eligible.forEach { candidate ->
            val participantId = ids()
            val mode = if (candidate.assisted) ParticipationMode.ASSISTED else ParticipationMode.AUTOMATED
            discussion.insertParticipant(
                ParticipantEntity(
                    id = participantId,
                    sessionId = sessionId,
                    providerId = candidate.providerId.name,
                    participationMode = mode.name,
                    billingEligibility = candidate.billing.name,
                    createdAtEpochMs = now,
                ),
            )
            discussion.insertModelIdentity(
                ModelIdentityEntity(
                    id = ids(),
                    participantId = participantId,
                    providerId = candidate.providerId.name,
                    userDeclaredModelId = ModelIdentity.declared(candidate.userDeclaredModelId),
                    verifiedModelId = ModelIdentity.declared(candidate.verifiedModelId),
                    independentlyVerified = ModelIdentity.declared(candidate.verifiedModelId) != ModelIdentity.UNKNOWN,
                    createdAtEpochMs = now,
                    capabilityNote = candidate.capabilityNote,
                ),
            )
            grants.filter { it.participantKey == candidate.participantKey }.forEach { grant ->
                discussion.insertRole(
                    ParticipantRoleEntity(
                        id = ids(),
                        participantId = participantId,
                        role = grant.role.name,
                        reason = grant.reason,
                        createdAtEpochMs = now,
                    ),
                )
            }
        }
    }

    private suspend fun participantFor(sessionId: String, providerId: ProviderId): ParticipantEntity =
        discussion.participants(sessionId).first { it.providerId == providerId.name }

    private suspend fun roleParticipant(sessionId: String, role: ParticipantRole): ParticipantEntity? {
        val people = discussion.participants(sessionId)
        return people.firstOrNull { participant ->
            discussion.roles(participant.id).any { it.role == role.name }
        }
    }

    private suspend fun allRoles(sessionId: String): List<ParticipantRoleEntity> =
        discussion.participants(sessionId).flatMap { discussion.roles(it.id) }

    private suspend fun openRound(sessionId: String, roundNumber: Int) {
        if (discussion.rounds(sessionId).any { it.roundNumber == roundNumber }) return
        discussion.insertRound(
            DiscussionRoundEntity(
                id = ids(),
                sessionId = sessionId,
                roundNumber = roundNumber,
                state = "OPEN",
                openedAtEpochMs = clock(),
                closedAtEpochMs = null,
            ),
        )
    }

    private suspend fun guard(sessionId: String): ResearchSessionEntity? {
        val session = fresh(sessionId)
        val state = SessionState.valueOf(session.state)
        if (state == SessionState.PAUSED) {
            note(sessionId, "The session is paused.")
            return null
        }
        if (state in SessionTransitions.terminal || state == SessionState.BLOCKED) return null
        if (expired(session)) {
            fail(sessionId, "The research deadline passed. Open tasks were cancelled. No answer was invented.")
            return null
        }
        return fresh(sessionId)
    }

    private suspend fun fail(sessionId: String, message: String): SessionSnapshot {
        val state = SessionState.valueOf(fresh(sessionId).state)
        if (state !in SessionTransitions.terminal) {
            cancelOpenTasks(sessionId, AssistedTaskState.FAILED)
            move(sessionId, SessionState.FAILED, message)
        }
        return snapshot(sessionId)
    }

    private suspend fun cancelOpenTasks(sessionId: String, state: AssistedTaskState) {
        council.tasksForSession(sessionId)
            .filter { it.state in OPEN_TASKS || it.state == AssistedTaskState.QUEUED.name }
            .forEach { council.updateTaskState(it.id, state.name, clock()) }
    }

    private suspend fun move(
        sessionId: String,
        to: SessionState,
        message: String,
        await: String? = null,
        resume: String? = null,
    ) {
        val session = fresh(sessionId)
        val from = SessionState.valueOf(session.state)
        check(SessionTransitions.permits(from, to)) { "Cannot move from $from to $to." }
        discussion.updateSessionProgress(
            id = sessionId,
            state = to.name,
            resumeState = resume ?: session.resumeState,
            awaitState = await,
            requestsConsumed = session.requestsConsumed,
            reviewRound = session.reviewRound,
            statusNote = message,
            updatedAt = clock(),
        )
        checkpoint(sessionId, to, message)
    }

    private suspend fun note(sessionId: String, message: String) {
        val session = fresh(sessionId)
        discussion.updateSessionProgress(
            id = sessionId,
            state = session.state,
            resumeState = session.resumeState,
            awaitState = session.awaitState,
            requestsConsumed = session.requestsConsumed,
            reviewRound = session.reviewRound,
            statusNote = message,
            updatedAt = clock(),
        )
    }

    private suspend fun setReviewRound(sessionId: String, round: Int) {
        val session = fresh(sessionId)
        discussion.updateSessionProgress(
            id = sessionId,
            state = session.state,
            resumeState = session.resumeState,
            awaitState = session.awaitState,
            requestsConsumed = session.requestsConsumed,
            reviewRound = round,
            statusNote = session.statusNote,
            updatedAt = clock(),
        )
    }

    private suspend fun checkpoint(sessionId: String, state: SessionState, message: String) {
        discussion.insertCheckpoint(
            WorkflowCheckpointEntity(
                id = ids(),
                sessionId = sessionId,
                sessionState = state.name,
                note = message,
                createdAtEpochMs = clock(),
            ),
        )
    }

    private suspend fun fresh(sessionId: String): ResearchSessionEntity =
        discussion.session(sessionId) ?: throw IllegalArgumentException("Session not found.")

    private fun expired(session: ResearchSessionEntity): Boolean {
        val deadline = session.deadlineEpochMs ?: return false
        return clock() > deadline
    }

    suspend fun addUserInstruction(sessionId: String, text: String, invalidateOpenWork: Boolean): SessionSnapshot = mutex.withLock {
        val instruction = text.trim()
        require(instruction.isNotEmpty()) { "A user instruction is required." }
        val session = fresh(sessionId)
        val now = clock()
        council.insertMessage(
            MessageEntity(
                id = ids(),
                conversationId = session.conversationId,
                providerId = null,
                source = MessageSource.USER.name,
                body = instruction,
                roundNumber = session.reviewRound.coerceAtLeast(1),
                createdAtEpochMs = now,
            ),
        )
        if (invalidateOpenWork) {
            council.tasksForSession(sessionId)
                .filter { (it.state in OPEN_TASKS || it.state == AssistedTaskState.QUEUED.name) && it.kind in setOf(KIND_FOLLOW_UP, KIND_RESEARCH, KIND_ANALYSIS) }
                .forEach { council.updateTaskState(it.id, AssistedTaskState.CANCELLED.name, now) }
        }
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = session.conversationId,
                providerId = null,
                type = "USER_INSTRUCTION",
                summary = if (invalidateOpenWork) {
                    "The user corrected the discussion. Open research tasks were cancelled and cannot change the final answer."
                } else {
                    "The user added information to this discussion. Earlier completed work was kept."
                },
                createdAtEpochMs = now,
            ),
        )
        snapshot(sessionId)
    }

    private suspend fun recordStructuredNotes(sessionId: String, task: ResearchTaskEntity, text: String) {
        val claims = discussion.claims(sessionId).map { it.id to it.statement }
        val now = clock()
        val recorded = discussion.claims(sessionId).lastOrNull { it.authorParticipantId == task.participantId }
        val math = LocalMath.check(ClaimLedger.parse(recorded?.statement ?: text).body)
        val verifier = task.participantId
        if (recorded != null && verifier != null && (math.status == LocalMath.Status.VERIFIED || math.status == LocalMath.Status.CONTRADICTED)) {
            discussion.insertVerification(
                VerificationResultEntity(
                    id = ids(),
                    sessionId = sessionId,
                    targetClaimId = recorded.id,
                    objectionId = null,
                    verifierParticipantId = verifier,
                    outcome = if (math.status == LocalMath.Status.VERIFIED) {
                        com.airachabanda.app.core.model.VerificationOutcome.SUPPORTED.name
                    } else {
                        com.airachabanda.app.core.model.VerificationOutcome.CONTRADICTED.name
                    },
                    note = math.reason,
                    createdAtEpochMs = now,
                ),
            )
        }
        val locator = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')')
        if (recorded != null && locator != null) {
            discussion.insertEvidence(
                EvidenceReferenceEntity(
                    id = ids(),
                    claimId = recorded.id,
                    locator = locator,
                    note = EvidenceNotes.format(EvidenceNotes.citation(locator)),
                    createdAtEpochMs = now,
                ),
            )
        }
        val proposal = ResearchProposal.parse(text)
        if (proposal != null && !ResearchProposal.automaticUrlFetchPermitted()) {
            council.insertEvent(
                WorkflowEventEntity(
                    id = ids(),
                    conversationId = fresh(sessionId).conversationId,
                    providerId = null,
                    type = "RESEARCH_PROPOSAL",
                    summary = "A research proposal was recorded and was not sent. Consent is required before a connector is contacted.",
                    createdAtEpochMs = now,
                ),
            )
        }
        if (task.kind == KIND_CRITIQUE) {
            ObjectionExtractor.extract(text).forEach { found ->
                val claimId = if (found.claimId != null) {
                    ClaimAssociation.resolve(found.claimId, null, claims)
                } else {
                    ObjectionExtractor.matchingClaim(found.claimFragment, claims)
                } ?: return@forEach
                val author = task.participantId ?: return@forEach
                discussion.insertObjection(
                    ObjectionEntity(
                        id = ids(),
                        sessionId = sessionId,
                        targetClaimId = claimId,
                        authorParticipantId = author,
                        userDeclaredModelId = ModelIdentity.UNKNOWN,
                        verifiedModelId = ModelIdentity.UNKNOWN,
                        roundNumber = task.roundNumber,
                        category = found.category.name,
                        severity = com.airachabanda.app.core.model.ObjectionSeverity.MEDIUM.name,
                        explanation = found.explanation,
                        supportingEvidence = "",
                        requestedVerification = "Check this objection against the imported material.",
                        resolution = ResolutionStatus.OPEN.name,
                        createdAtEpochMs = now,
                    ),
                )
            }
        }
        if (task.kind == KIND_VERIFICATION) {
            val verifier = task.participantId ?: return
            VerificationExtractor.extract(text).forEach { found ->
                val claimId = ObjectionExtractor.matchingClaim(found.claimFragment, claims) ?: return@forEach
                discussion.insertVerification(
                    VerificationResultEntity(
                        id = ids(),
                        sessionId = sessionId,
                        targetClaimId = claimId,
                        objectionId = null,
                        verifierParticipantId = verifier,
                        outcome = found.outcome.name,
                        note = "Recorded from the imported verification text. This is not a live provider result.",
                        createdAtEpochMs = now,
                    ),
                )
            }
        }
        if (task.kind == KIND_SYNTHESIS) {
            discussion.insertConclusion(
                com.airachabanda.app.persistence.ConclusionEntity(
                    id = ids(),
                    sessionId = sessionId,
                    kind = com.airachabanda.app.core.model.ConclusionKind.FINAL.name,
                    body = "Imported synthesis. This text was supplied for this discussion and is not a live provider response.\n$text",
                    roundNumber = task.roundNumber,
                    createdAtEpochMs = now,
                ),
            )
        }
    }

    suspend fun correctClaim(sessionId: String, claimId: String, replacement: String): SessionSnapshot = mutex.withLock {
        val original = discussion.claims(sessionId).firstOrNull { it.id == claimId } ?: return@withLock snapshot(sessionId)
        val parsed = ClaimLedger.parse(original.statement)
        val now = clock()
        discussion.insertClaim(
            ClaimEntity(
                id = ids(),
                sessionId = sessionId,
                messageId = null,
                authorParticipantId = original.authorParticipantId,
                roundNumber = original.roundNumber,
                statement = ClaimLedger.format(
                    kind = ClaimLedger.kindFor(replacement),
                    taskId = parsed.taskId,
                    body = replacement,
                    corrects = original.id,
                ),
                createdAtEpochMs = now,
            ),
        )
        val dependents = CorrectionPropagation.dependentIds(
            discussion.claims(sessionId).map { it.id to it.statement },
            original.id,
        )
        discussion.insertConclusion(
            ConclusionEntity(
                id = ids(),
                sessionId = sessionId,
                kind = ConclusionKind.PRELIMINARY.name,
                body = "Reassessment required. Claim ${original.id} was corrected. The original statement was preserved. Dependent claims: ${dependents.joinToString().ifBlank { "none" }}. Earlier final answers recorded before this correction are not the current answer.",
                roundNumber = original.roundNumber,
                createdAtEpochMs = now,
            ),
        )
        council.tasksForSession(sessionId)
            .filter { it.targetId == claimId && (it.state in OPEN_TASKS || it.state == AssistedTaskState.QUEUED.name) }
            .forEach { task -> council.updateTaskState(task.id, AssistedTaskState.CANCELLED.name, now) }
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = fresh(sessionId).conversationId,
                providerId = null,
                type = "CLAIM_CORRECTED",
                summary = "A material claim was corrected. Dependent work was marked for reassessment.",
                createdAtEpochMs = now,
            ),
        )
        snapshot(sessionId)
    }

    suspend fun transitionObjection(
        sessionId: String,
        objectionId: String,
        to: ResolutionStatus,
        reason: String,
    ): SessionSnapshot = mutex.withLock {
        val objection = discussion.objections(sessionId).firstOrNull { it.id == objectionId } ?: return@withLock snapshot(sessionId)
        val from = runCatching { ResolutionStatus.valueOf(objection.resolution) }.getOrDefault(ResolutionStatus.OPEN)
        val decision = ObjectionLifecycle.transition(from, to, reason)
        if (!decision.accepted) return@withLock snapshot(sessionId)
        discussion.updateObjectionStatus(objectionId, to.name, decision.reason)
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = fresh(sessionId).conversationId,
                providerId = null,
                type = "OBJECTION_STATUS",
                summary = decision.reason,
                createdAtEpochMs = clock(),
            ),
        )
        snapshot(sessionId)
    }

    suspend fun recordContradictoryEvidence(sessionId: String, claimId: String, excerpt: String): SessionSnapshot = mutex.withLock {
        val claim = discussion.claims(sessionId).firstOrNull { it.id == claimId } ?: return@withLock snapshot(sessionId)
        val now = clock()
        discussion.insertEvidence(
            EvidenceReferenceEntity(
                id = ids(),
                claimId = claim.id,
                locator = "contradictory-record",
                note = EvidenceNotes.format(EvidenceNotes.contradictory(excerpt)),
                createdAtEpochMs = now,
            ),
        )
        discussion.objections(sessionId)
            .filter {
                it.targetClaimId == claimId && it.resolution in setOf(
                    ResolutionStatus.CORRECTED.name,
                    ResolutionStatus.REBUTTED_WITH_EVIDENCE.name,
                    ResolutionStatus.UNRESOLVED.name,
                    ResolutionStatus.INSUFFICIENT_EVIDENCE.name,
                )
            }
            .forEach { objection ->
                val from = runCatching { ResolutionStatus.valueOf(objection.resolution) }.getOrDefault(ResolutionStatus.OPEN)
                val decision = ObjectionLifecycle.transition(from, ResolutionStatus.OPEN, "New contradictory evidence was recorded.")
                if (decision.accepted) discussion.updateObjectionStatus(objection.id, ResolutionStatus.OPEN.name, decision.reason)
            }
        snapshot(sessionId)
    }

    suspend fun searchPublicSource(
        sessionId: String,
        query: String,
        connectorName: String,
        claimId: String?,
        consent: Boolean,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val connector = runCatching { ResearchConnector.valueOf(connectorName) }.getOrNull()
                ?: return@withLock "The research connector is not one of the approved public services."
            finishRetrieval(sessionId, claimId) { resolved, fingerprints, stored ->
                ResearchEngine.runConnector(
                    connector = connector,
                    query = query,
                    consent = consent,
                    now = clock(),
                    claimId = resolved,
                    existingFingerprints = fingerprints,
                    storedSources = stored,
                    transport = researchTransport,
                    resolver = nameResolver,
                )
            }
        }
    }

    suspend fun retrievePublicUrl(sessionId: String, url: String, claimId: String?, consent: Boolean): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            finishRetrieval(sessionId, claimId) { resolved, fingerprints, stored ->
                ResearchEngine.runUserUrl(
                    url = url,
                    consent = consent,
                    now = clock(),
                    claimId = resolved,
                    existingFingerprints = fingerprints,
                    storedSources = stored,
                    transport = researchTransport,
                    resolver = nameResolver,
                )
            }
        }
    }

    private suspend fun finishRetrieval(
        sessionId: String,
        claimId: String?,
        run: (String?, Set<String>, Int) -> ResearchOutcome,
    ): String {
        if (discussion.session(sessionId) == null) {
            return "No discussion is open."
        }
        val claims = discussion.claims(sessionId)
        val resolved = ClaimAssociation.resolve(claimId, null, claims.map { it.id to it.statement })
        if (!claimId.isNullOrBlank() && resolved == null) {
            return "The claim identifier is missing or ambiguous. Nothing was transmitted."
        }
        val evidence = if (claims.isEmpty()) emptyList() else discussion.evidenceFor(claims.map { it.id })
        val fingerprints = evidence.map { EvidenceNotes.parse(it.note).fingerprint }.filter { it.isNotBlank() }.toSet()
        val stored = evidence.count { EvidenceNotes.parse(it.note).verification == EvidenceAssessment.RETRIEVED_NOT_ASSESSED.name || EvidenceNotes.parse(it.note).kind == EvidenceKind.RETRIEVED_SOURCE }
        val outcome = run(resolved, fingerprints, stored)
        val session = fresh(sessionId)
        if (!outcome.stored || outcome.record == null) {
            council.insertEvent(
                WorkflowEventEntity(
                    id = ids(),
                    conversationId = session.conversationId,
                    providerId = null,
                    type = "RESEARCH_FAILED",
                    summary = outcome.message,
                    createdAtEpochMs = clock(),
                ),
            )
            return outcome.message
        }
        val now = clock()
        val anchor = resolved ?: ids().also { created ->
            discussion.insertClaim(
                ClaimEntity(
                    id = created,
                    sessionId = sessionId,
                    messageId = null,
                    authorParticipantId = discussion.participants(sessionId).firstOrNull()?.id ?: created,
                    roundNumber = 1,
                    statement = ClaimLedger.format(
                        ClaimLedger.Kind.SOURCE_DEPENDENT,
                        "",
                        "Research record. This is not a verified fact.",
                    ),
                    createdAtEpochMs = now,
                ),
            )
        }
        val record = outcome.record.copy(claim = anchor)
        discussion.insertEvidence(
            EvidenceReferenceEntity(
                id = ids(),
                claimId = anchor,
                locator = outcome.canonical ?: record.canonical,
                note = EvidenceNotes.format(record),
                createdAtEpochMs = now,
            ),
        )
        council.insertEvent(
            WorkflowEventEntity(
                id = ids(),
                conversationId = session.conversationId,
                providerId = null,
                type = "RESEARCH_RETRIEVED",
                summary = "Retrieved ${record.title.ifBlank { "a public source" }}. Published ${record.published.ifBlank { "date not in the source" }}. Retrieved $now. Verification ${record.verification}.",
                createdAtEpochMs = now,
            ),
        )
        return outcome.message
    }

    private suspend fun snapshot(sessionId: String): SessionSnapshot {
        val session = fresh(sessionId)
        return SessionSnapshot(
            sessionId = session.id,
            state = SessionState.valueOf(session.state),
            requestsConsumed = session.requestsConsumed,
            reviewRound = session.reviewRound,
            statusNote = session.statusNote,
        )
    }

    private companion object {
        const val KIND_RESEARCH = "RESEARCH"
        const val KIND_FOLLOW_UP = "FOLLOW_UP"
        const val KIND_ANALYSIS = "ANALYSIS"
        const val KIND_CRITIQUE = "CRITIQUE"
        const val KIND_REBUTTAL = "REBUTTAL"
        const val KIND_VERIFICATION = "VERIFICATION"
        const val KIND_SYNTHESIS = "SYNTHESIS"
        const val MAX_STEPS = 8
        val COLLECTION_KINDS = setOf(KIND_RESEARCH, KIND_FOLLOW_UP, KIND_ANALYSIS, "ASSISTED_RESEARCH")
        val OPEN_TASKS = setOf(
            AssistedTaskState.PENDING.name,
            AssistedTaskState.READY.name,
            AssistedTaskState.RUNNING.name,
            AssistedTaskState.AWAITING_USER_RESPONSE.name,
            AssistedTaskState.DEFERRED.name,
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

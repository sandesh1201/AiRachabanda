package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.SessionState

enum class CouncilAction {
    REQUEST_ADDITIONAL_RESEARCH,
    CHALLENGE_CLAIM,
    REQUEST_VERIFICATION,
    REQUEST_REBUTTAL,
    SUBMIT_CORRECTION,
    RECOMMEND_SYNTHESIS,
}

data class ProtocolRequest(
    val sessionId: String,
    val actorParticipantId: String,
    val action: CouncilAction,
    val body: String,
    val targetId: String? = null,
    val requestedRequests: Int = 0,
    val depth: Int = 0,
    val recipientParticipantId: String? = null,
    val purpose: String = "",
    val expectedOutput: String = "",
)

data class ProtocolResult(
    val accepted: Boolean,
    val reason: String,
    val snapshot: SessionSnapshot?,
)

object CouncilProtocol {
    private val permissions: Map<CouncilAction, Set<ParticipantRole>> = mapOf(
        CouncilAction.REQUEST_ADDITIONAL_RESEARCH to setOf(
            ParticipantRole.MODERATOR,
            ParticipantRole.RESEARCHER,
            ParticipantRole.ANALYST,
            ParticipantRole.DOMAIN_SPECIALIST,
        ),
        CouncilAction.CHALLENGE_CLAIM to setOf(ParticipantRole.DEVILS_ADVOCATE, ParticipantRole.ANALYST),
        CouncilAction.REQUEST_VERIFICATION to setOf(
            ParticipantRole.MODERATOR,
            ParticipantRole.EVIDENCE_VERIFIER,
            ParticipantRole.DOMAIN_SPECIALIST,
        ),
        CouncilAction.REQUEST_REBUTTAL to setOf(ParticipantRole.MODERATOR, ParticipantRole.DEVILS_ADVOCATE),
        CouncilAction.SUBMIT_CORRECTION to setOf(ParticipantRole.RESEARCHER, ParticipantRole.ANALYST),
        CouncilAction.RECOMMEND_SYNTHESIS to setOf(ParticipantRole.MODERATOR, ParticipantRole.FINAL_SYNTHESIZER),
    )

    fun validate(
        request: ProtocolRequest,
        sessionState: String,
        actorFound: Boolean,
        actorRoles: Set<String>,
        requestBudget: Int,
        requestsConsumed: Int,
        duplicate: Boolean = false,
        recipientFound: Boolean = true,
        maxDepth: Int = 2,
        fanOutExceeded: Boolean = false,
    ): ProtocolDecision {
        val state = runCatching { SessionState.valueOf(sessionState) }.getOrNull()
        if (state == null || state in SessionTransitions.terminal || state == SessionState.PAUSED) {
            return ProtocolDecision(false, "The session is not accepting actions.")
        }
        if (!actorFound) return ProtocolDecision(false, "The actor is not a participant in this session.")
        if (request.body.isBlank()) return ProtocolDecision(false, "The action needs text supplied by the caller.")
        if (!ExecutionGate.allow(ExecutionIntent.RAISE_BUDGET) && request.requestedRequests > requestBudget) {
            return ProtocolDecision(false, "The request tried to raise the budget. The budget was not increased.")
        }
        if (request.depth > maxDepth) {
            return ProtocolDecision(false, "Recursive delegation was rejected.")
        }
        if (duplicate) return ProtocolDecision(false, "This request repeats an open delegation.")
        if (fanOutExceeded) {
            return ProtocolDecision(false, "The delegation fan-out limit for this discussion mode has been reached.")
        }
        if (!request.recipientParticipantId.isNullOrBlank() && !recipientFound) {
            return ProtocolDecision(false, "The recipient is not a participant in this session.")
        }
        if (request.requestedRequests < 0) return ProtocolDecision(false, "A request cannot expand the budget.")
        val remaining = requestBudget - requestsConsumed
        if (request.requestedRequests > remaining) {
            return ProtocolDecision(false, "The request exceeds the remaining budget. The budget was not increased.")
        }
        val allowed = permissions.getValue(request.action).map { it.name }.toSet()
        if (actorRoles.none { it in allowed }) {
            return ProtocolDecision(false, "This participant does not have permission for ${request.action.name}.")
        }
        return ProtocolDecision(true, "Accepted as untrusted workflow input. It was not executed as code and did not change billing or credentials.")
    }
}

data class ProtocolDecision(
    val accepted: Boolean,
    val reason: String,
)

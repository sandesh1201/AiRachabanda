package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId

enum class ProviderFault {
    NOT_CONFIGURED,
    DISABLED,
    AUTHENTICATION,
    EXPIRED_AUTHORIZATION,
    QUOTA_EXHAUSTED,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    TIMEOUT,
    UNSUPPORTED,
    INCOMPLETE,
    INVALID,
    BILLING,
    ASSISTED_WAITING,
    SESSION_SKIP,
}

data class ContinuityCandidate(
    val participantId: String,
    val providerId: ProviderId,
    val assisted: Boolean,
    val billing: BillingEligibility,
    val verifiedModelId: String,
    val availability: String,
    val capabilities: Set<String>,
)

data class ReassignmentDecision(
    val participantId: String?,
    val reason: String,
) {
    val accepted: Boolean get() = participantId != null
}

object TaskReassignment {
    fun capabilityFor(role: ParticipantRole): String = when (role) {
        ParticipantRole.MODERATOR, ParticipantRole.RESEARCHER -> RoleAssignment.RESEARCH
        ParticipantRole.ANALYST -> RoleAssignment.ANALYSIS
        ParticipantRole.DOMAIN_SPECIALIST -> RoleAssignment.DOMAIN
        ParticipantRole.DEVILS_ADVOCATE -> RoleAssignment.CRITIQUE
        ParticipantRole.EVIDENCE_VERIFIER -> RoleAssignment.VERIFICATION
        ParticipantRole.FINAL_SYNTHESIZER -> RoleAssignment.SYNTHESIS
    }

    fun select(
        role: ParticipantRole,
        candidates: List<ContinuityCandidate>,
        excludedParticipantIds: Set<String>,
        claimAuthorParticipantIds: Set<String>,
        remainingBudget: Int,
        deadlineEpochMs: Long?,
        now: Long,
    ): ReassignmentDecision {
        if (deadlineEpochMs != null && now > deadlineEpochMs) {
            return ReassignmentDecision(null, "The task deadline has passed. It was not reassigned.")
        }
        val needed = capabilityFor(role)
        val eligible = candidates.filter { candidate ->
            candidate.participantId !in excludedParticipantIds &&
                candidate.availability == "ELIGIBLE" &&
                needed in candidate.capabilities &&
                (candidate.assisted ||
                    (ZeroCostPolicy.automatedInferenceAllowed(candidate.providerId, candidate.billing) && remainingBudget > 0))
        }
        if (eligible.isEmpty()) {
            return ReassignmentDecision(
                null,
                "No eligible participant can perform ${role.name}. Paid inference was not activated. The unfinished work was not reassigned.",
            )
        }
        val chosen = if (role == ParticipantRole.DEVILS_ADVOCATE || role == ParticipantRole.EVIDENCE_VERIFIER) {
            eligible.firstOrNull { it.participantId !in claimAuthorParticipantIds } ?: eligible.first()
        } else {
            eligible.first()
        }
        val independence = if (role == ParticipantRole.DEVILS_ADVOCATE && chosen.participantId in claimAuthorParticipantIds) {
            " Independent multi-model review is unavailable for this reassignment."
        } else {
            ""
        }
        return ReassignmentDecision(
            chosen.participantId,
            "Reassigned ${role.name} to ${chosen.providerId.name} because the previous participant is unavailable.$independence",
        )
    }
}

object DiscussionNotificationCopy {
    fun privateText(title: String, stage: String, completed: Int, pending: Int, active: Int, waiting: Boolean): String {
        val wait = if (waiting) " Waiting for your response." else ""
        return "$title. Stage $stage. Completed $completed. Pending $pending. Active participants $active.$wait"
    }

    fun publicText(): String = "A council discussion is running."
}

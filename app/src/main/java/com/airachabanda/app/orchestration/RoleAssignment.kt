package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.QuestionComplexity
import com.airachabanda.app.core.model.SessionState

data class EligibleParticipant(
    val providerId: ProviderId,
    val assisted: Boolean,
    val billing: BillingEligibility,
    val userDeclaredModelId: String = ModelIdentity.UNKNOWN,
    val verifiedModelId: String = ModelIdentity.UNKNOWN,
)

data class RoleGrant(
    val providerId: ProviderId,
    val role: ParticipantRole,
    val reason: String,
)

object RoleAssignment {
    const val RESEARCH = "research"
    const val ANALYSIS = "analysis"
    const val DOMAIN = "domain"
    const val CRITIQUE = "critique"
    const val VERIFICATION = "verification"
    const val SYNTHESIS = "synthesis"

    fun capabilitiesFor(complexity: QuestionComplexity): Set<String> = when (complexity) {
        QuestionComplexity.LOW -> setOf(RESEARCH, SYNTHESIS)
        QuestionComplexity.MODERATE -> setOf(RESEARCH, ANALYSIS, CRITIQUE, SYNTHESIS)
        QuestionComplexity.HIGH -> setOf(RESEARCH, ANALYSIS, DOMAIN, CRITIQUE, VERIFICATION, SYNTHESIS)
    }

    fun assign(
        participants: List<EligibleParticipant>,
        complexity: QuestionComplexity,
        requiredCapabilities: Set<String>,
        remainingBudget: Int = Int.MAX_VALUE,
        knownQuotas: Map<ProviderId, Int?> = emptyMap(),
        claimAuthors: Set<ProviderId> = emptySet(),
        excludedProviders: Set<ProviderId> = emptySet(),
    ): List<RoleGrant> {
        val available = participants
            .distinctBy { it.providerId }
            .filter { it.providerId !in excludedProviders }
            .filter { candidate ->
                val quota = knownQuotas[candidate.providerId]
                quota == null || candidate.assisted || quota > 0
            }
        if (available.isEmpty()) return emptyList()
        val ordered = available.sortedWith(
            compareBy<EligibleParticipant> { if (remainingBudget <= 0 && !it.assisted) 1 else 0 }
                .thenBy { it.providerId.name },
        )
        val needed = capabilitiesFor(complexity) + requiredCapabilities
        val grants = mutableListOf<RoleGrant>()
        grants += RoleGrant(
            providerId = ordered.first().providerId,
            role = ParticipantRole.MODERATOR,
            reason = "Assigned for this session because a moderator is required. Complexity is $complexity. This role is not reserved for one provider.",
        )
        if (RESEARCH in needed) {
            ordered.forEach { participant ->
                grants += RoleGrant(
                    providerId = participant.providerId,
                    role = ParticipantRole.RESEARCHER,
                    reason = "Assigned because complexity $complexity requires research.",
                )
            }
        }
        if (ANALYSIS in needed) {
            grants += RoleGrant(
                providerId = ordered.first().providerId,
                role = ParticipantRole.ANALYST,
                reason = "Assigned because complexity $complexity requires analysis.",
            )
        }
        if (DOMAIN in needed) {
            grants += RoleGrant(
                providerId = ordered.first().providerId,
                role = ParticipantRole.DOMAIN_SPECIALIST,
                reason = "Assigned because complexity $complexity requires domain review.",
            )
        }
        val synthesizer = if (SYNTHESIS in needed) {
            val providerId = ordered.lastOrNull { remainingBudget > 0 || it.assisted }?.providerId
                ?: ordered.last().providerId
            grants += RoleGrant(
                providerId = providerId,
                role = ParticipantRole.FINAL_SYNTHESIZER,
                reason = "Assigned for this session because synthesis is required. This role is not reserved for one provider.",
            )
            providerId
        } else {
            null
        }
        if (CRITIQUE in needed) {
            val authors = ordered.filter { it.providerId in claimAuthors }
            val nonAuthors = ordered.filter { it.providerId !in claimAuthors }
            val independent = nonAuthors.firstOrNull { candidate ->
                candidate.verifiedModelId != ModelIdentity.UNKNOWN &&
                    authors.none { it.verifiedModelId == candidate.verifiedModelId } &&
                    candidate.providerId != synthesizer
            } ?: nonAuthors.firstOrNull { candidate ->
                candidate.verifiedModelId != ModelIdentity.UNKNOWN &&
                    authors.none { it.verifiedModelId == candidate.verifiedModelId }
            }
            val chosen = independent
                ?: nonAuthors.firstOrNull { it.providerId != synthesizer }
                ?: nonAuthors.firstOrNull()
                ?: ordered.firstOrNull { it.providerId != synthesizer }
                ?: ordered.first()
            val reason = when {
                chosen.verifiedModelId == ModelIdentity.UNKNOWN ->
                    "Assigned for this session because critique is required. Model identity is unknown, so this is not an independent review. A different host of the same model would not count as independent either."
                independent == null ->
                    "Assigned for this session because critique is required. No participant has a different verified model, so this is not an independent review."
                else ->
                    "Assigned for this session because critique is required and the verified model differs from the claim authors."
            }
            grants += RoleGrant(providerId = chosen.providerId, role = ParticipantRole.DEVILS_ADVOCATE, reason = reason)
        }
        if (VERIFICATION in needed) {
            val critic = grants.firstOrNull { it.role == ParticipantRole.DEVILS_ADVOCATE }?.providerId
            val avoid = setOfNotNull(synthesizer, critic)
            val providerId = ordered.firstOrNull { it.providerId !in avoid }?.providerId
                ?: ordered.firstOrNull { it.providerId != synthesizer }?.providerId
                ?: ordered.first().providerId
            grants += RoleGrant(
                providerId = providerId,
                role = ParticipantRole.EVIDENCE_VERIFIER,
                reason = "Assigned because complexity $complexity requires evidence verification.",
            )
        }
        return grants
    }
}

object SessionTransitions {
    val terminal = setOf(SessionState.COMPLETED, SessionState.CANCELLED, SessionState.FAILED)

    private val active = setOf(
        SessionState.INDEPENDENT_RESEARCH,
        SessionState.COLLECTING_RESPONSES,
        SessionState.PRELIMINARY_ANALYSIS,
        SessionState.CRITICAL_REVIEW,
        SessionState.REBUTTAL,
        SessionState.EVIDENCE_VERIFICATION,
        SessionState.REASSESSMENT,
        SessionState.SYNTHESIS,
        SessionState.AWAITING_USER_RESPONSE,
        SessionState.PLANNING,
    )

    private val allowed: Map<SessionState, Set<SessionState>> = mapOf(
        SessionState.CREATED to setOf(SessionState.PLANNING, SessionState.CANCELLED, SessionState.FAILED),
        SessionState.PLANNING to setOf(
            SessionState.INDEPENDENT_RESEARCH,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.BLOCKED,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.INDEPENDENT_RESEARCH to setOf(
            SessionState.COLLECTING_RESPONSES,
            SessionState.PRELIMINARY_ANALYSIS,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.COLLECTING_RESPONSES to setOf(
            SessionState.INDEPENDENT_RESEARCH,
            SessionState.PRELIMINARY_ANALYSIS,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.PRELIMINARY_ANALYSIS to setOf(
            SessionState.CRITICAL_REVIEW,
            SessionState.SYNTHESIS,
            SessionState.COMPLETED,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.CRITICAL_REVIEW to setOf(
            SessionState.REBUTTAL,
            SessionState.EVIDENCE_VERIFICATION,
            SessionState.REASSESSMENT,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.REBUTTAL to setOf(
            SessionState.EVIDENCE_VERIFICATION,
            SessionState.REASSESSMENT,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.EVIDENCE_VERIFICATION to setOf(
            SessionState.REASSESSMENT,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.REASSESSMENT to setOf(
            SessionState.CRITICAL_REVIEW,
            SessionState.SYNTHESIS,
            SessionState.COMPLETED,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.SYNTHESIS to setOf(
            SessionState.COMPLETED,
            SessionState.AWAITING_USER_RESPONSE,
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
        ),
        SessionState.AWAITING_USER_RESPONSE to active + setOf(
            SessionState.PAUSED,
            SessionState.CANCELLED,
            SessionState.FAILED,
            SessionState.COMPLETED,
        ),
        SessionState.PAUSED to active + setOf(SessionState.AWAITING_USER_RESPONSE, SessionState.CANCELLED, SessionState.FAILED),
        SessionState.BLOCKED to setOf(SessionState.CANCELLED, SessionState.FAILED),
        SessionState.COMPLETED to emptySet(),
        SessionState.CANCELLED to emptySet(),
        SessionState.FAILED to emptySet(),
    )

    fun permits(from: SessionState, to: SessionState): Boolean = to in allowed.getValue(from)
}

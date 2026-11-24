package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.ResearchMode

/**
 * Separate discussion limits. A round is not an AI request, and a research
 * request is not a stored source. Values are clamped and are never raised here.
 */
data class DiscussionBudgets(
    val rounds: Int,
    val aiRequests: Int,
    val researchRequests: Int,
    val researchSources: Int,
    val concurrency: Int,
    val delegationDepth: Int,
    val deadlineMinutes: Int,
    val verificationRequired: Boolean,
) {
    fun clamped(): DiscussionBudgets = copy(
        rounds = rounds.coerceIn(1, MAX_ROUNDS),
        aiRequests = aiRequests.coerceIn(0, MAX_AI_REQUESTS),
        researchRequests = researchRequests.coerceIn(0, MAX_RESEARCH_REQUESTS),
        researchSources = researchSources.coerceIn(1, MAX_SOURCES),
        concurrency = concurrency.coerceIn(1, MAX_CONCURRENCY),
        delegationDepth = delegationDepth.coerceIn(1, MAX_DEPTH),
        deadlineMinutes = deadlineMinutes.coerceIn(1, MAX_DEADLINE_MINUTES),
    )

    companion object {
        const val MAX_ROUNDS = 8
        const val MAX_AI_REQUESTS = 12
        const val MAX_RESEARCH_REQUESTS = 8
        const val MAX_SOURCES = 8
        const val MAX_CONCURRENCY = 3
        const val MAX_DEPTH = 3
        const val MAX_DEADLINE_MINUTES = 60

        fun quick() = DiscussionBudgets(1, 1, 1, 1, 1, 1, 15, false)
        fun standard() = DiscussionBudgets(3, 4, 2, 3, 2, 2, 60, false)
        fun deep() = DiscussionBudgets(4, 8, 4, 6, 3, 3, 60, true)

        fun resolve(mode: ResearchMode, custom: DiscussionBudgets? = null): DiscussionBudgets = when (mode) {
            ResearchMode.QUICK -> quick()
            ResearchMode.STANDARD -> standard()
            ResearchMode.DEEP_RESEARCH -> deep()
            ResearchMode.CUSTOM -> (custom ?: standard()).clamped()
        }

        fun fromNote(mode: ResearchMode, note: String): DiscussionBudgets {
            val preset = resolve(mode)
            if (mode != ResearchMode.CUSTOM) return preset
            val sources = Regex("Research sources (\\d+)").find(note)?.groupValues?.get(1)?.toIntOrNull()
            val requests = Regex("Research requests (\\d+)").find(note)?.groupValues?.get(1)?.toIntOrNull()
            return preset.copy(
                researchSources = sources ?: preset.researchSources,
                researchRequests = requests ?: preset.researchRequests,
            ).clamped()
        }
    }
}

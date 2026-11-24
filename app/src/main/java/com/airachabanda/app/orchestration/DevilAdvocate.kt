package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode

data class ReviewDecision(
    val assistedReviewPermitted: Boolean,
    val automatedReviewPermitted: Boolean,
    val plannedRounds: Int,
    val independentReviewAvailable: Boolean,
    val disclosure: String,
)

object DevilAdvocatePolicy {
    const val PAID_INFERENCE_PERMITTED = false

    fun assess(
        mode: ResearchMode,
        messages: List<ChatMessage>,
        reviewer: ProviderId,
        customRounds: Int,
        automatedBudget: Int,
    ): ReviewDecision {
        val material = messages.filter { it.source != MessageSource.USER && it.body.isNotBlank() }
        val requestedRounds = when (mode) {
            ResearchMode.QUICK -> 1
            ResearchMode.STANDARD -> 1
            ResearchMode.DEEP_RESEARCH -> 3
            ResearchMode.CUSTOM -> customRounds.coerceIn(0, 8)
        }
        val automated = automatedBudget > 0 &&
            PAID_INFERENCE_PERMITTED &&
            ZeroCostPolicy.automatedInferenceAllowed(reviewer, BillingEligibility.VERIFIED_FREE)
        if (material.isEmpty()) {
            return ReviewDecision(
                assistedReviewPermitted = false,
                automatedReviewPermitted = false,
                plannedRounds = 0,
                independentReviewAvailable = false,
                disclosure = "A critique waits until a provider response is in this conversation. No critique was invented.",
            )
        }
        if (requestedRounds == 0) {
            return ReviewDecision(
                assistedReviewPermitted = false,
                automatedReviewPermitted = false,
                plannedRounds = 0,
                independentReviewAvailable = false,
                disclosure = "Custom review depth is zero. Verification is reduced, and no critique prompt was prepared.",
            )
        }
        return ReviewDecision(
            assistedReviewPermitted = true,
            automatedReviewPermitted = automated,
            plannedRounds = requestedRounds,
            independentReviewAvailable = false,
            disclosure = "This critique is assisted. No underlying model identity is on record, so it is not independent verification.",
        )
    }
}

object CritiquePrompt {
    fun prepare(
        reviewerName: String,
        modeLabel: String,
        roundNumber: Int,
        messages: List<ChatMessage>,
        disclosure: String,
    ): String {
        val material = messages.filter { it.source != MessageSource.USER && it.body.isNotBlank() }
        return buildString {
            appendLine("AI Rachabanda Devil's Advocate prompt")
            appendLine("Reviewer: $reviewerName")
            appendLine("Research mode: $modeLabel")
            appendLine("Discussion round: $roundNumber")
            appendLine(disclosure)
            appendLine()
            appendLine("Critique only the material below. Do not invent evidence, citations, or counterarguments.")
            appendLine("Identify claims, assumptions, calculations, citations, and proposed conclusions.")
            appendLine("Try to disprove those conclusions. Name unsupported assumptions, factual errors, mathematical errors, logical inconsistencies, missing context, counterexamples, and weak or contradictory citations.")
            appendLine("Propose alternative explanations only when the material supports them.")
            appendLine("State which objections remain unresolved. Do not assign a confidence percentage.")
            appendLine("If you find a real objection, write: OBJECTION category=CATEGORY claim=\"quoted claim\"")
            appendLine("On the next line write explanation: and the objection. Categories are FACTUAL_ERROR, MATHEMATICAL_ERROR, UNSUPPORTED_ASSUMPTION, LOGICAL_INCONSISTENCY, MISSING_CONTEXT, COUNTEREXAMPLE, WEAK_CITATION, and ALTERNATIVE_EXPLANATION.")
            appendLine("If no real objection is present, say that no objection was found. Do not invent one.")
            appendLine()
            material.forEach { message ->
                val author = message.providerId?.name ?: message.source.name
                appendLine("Material from $author, round ${message.roundNumber ?: 1}:")
                appendLine(message.body.trim())
                appendLine()
            }
        }.trim()
    }
}

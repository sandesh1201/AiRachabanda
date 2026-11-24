package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.ObjectionCategory
import com.airachabanda.app.core.model.VerificationOutcome

/**
 * Rules for one discussion. These types do not contact a provider and do not invent claims.
 */
object IndependentBrief {
    fun research(question: String, userContext: List<String>): String = buildString {
        appendLine("Answer only the user request below. Other participants' initial answers are withheld.")
        appendLine("User request:")
        appendLine(question.trim())
        appendLine("User-provided context:")
        val context = userContext.map { it.trim() }.filter { it.isNotEmpty() }
        if (context.isEmpty()) {
            appendLine("No additional user context was provided.")
        } else {
            context.forEach { appendLine(it) }
        }
    }.trim()
}

object DiscussionExchange {
    const val MAX_DEPTH = 2

    private val forbidden = Regex(
        """(?i)(enable billing|add a payment|ignore the budget|override (billing|security|privacy|permissions)|reveal (the )?(api key|token|credential)|grant (owner|admin))""",
    )

    data class Request(
        val senderFound: Boolean,
        val recipientFound: Boolean,
        val requiredCapability: String,
        val purpose: String,
        val claimFound: Boolean,
        val claimRequired: Boolean,
        val expectedOutput: String,
        val roundNumber: Int,
        val depth: Int,
        val body: String,
        val duplicate: Boolean,
        val sessionAccepting: Boolean,
        val automatedRequest: Boolean,
        val remainingBudget: Int,
    )

    data class Decision(val accepted: Boolean, val reason: String)

    fun forbidden(body: String): Boolean = forbidden.containsMatchIn(body)

    fun validate(request: Request): Decision {
        val reason = when {
            !request.sessionAccepting -> "The session is not accepting delegation."
            !request.senderFound -> "The sender is not a participant in this session."
            request.body.isBlank() -> "The delegation needs text supplied by the caller."
            forbidden(request.body) -> "The request tried to override billing, security, privacy, or the request budget. It was rejected."
            request.depth > MAX_DEPTH -> "Recursive delegation was rejected."
            request.duplicate -> "This request repeats an open delegation."
            request.purpose.isBlank() -> "The delegation needs a purpose."
            request.expectedOutput.isBlank() -> "The delegation needs an expected output."
            request.roundNumber < 1 -> "The delegation needs a discussion round."
            !request.recipientFound && request.requiredCapability.isBlank() -> "The delegation needs a recipient or a required capability."
            request.recipientFound.not() && request.requiredCapability.isNotBlank() && !request.senderFound -> "The sender is not a participant."
            request.claimRequired && !request.claimFound -> "The delegation must name a claim in this session."
            request.automatedRequest && request.remainingBudget <= 0 -> "The request exceeds the remaining budget. The budget was not increased."
            else -> "Accepted as untrusted workflow input. It was not executed as code and did not change billing or credentials."
        }
        return Decision(reason.startsWith("Accepted"), reason)
    }

    fun taskPrompt(
        sender: String,
        recipient: String,
        purpose: String,
        claim: String,
        expectedOutput: String,
        roundNumber: Int,
        depth: Int,
        instruction: String,
    ): String = buildString {
        appendLine("Participant request. This is not permission to spend money or change credentials.")
        appendLine("Sender: $sender")
        appendLine("Recipient: $recipient")
        appendLine("Purpose: $purpose")
        appendLine("Discussion round: $roundNumber")
        appendLine("Delegation depth: $depth")
        appendLine("Relevant claim: ${claim.ifBlank { "None named." }}")
        appendLine("Expected output: $expectedOutput")
        appendLine("Instruction:")
        appendLine(instruction.trim())
        appendLine("Do not invent citations, evidence, or a confidence percentage.")
    }.trim()
}

object ObjectionExtractor {
    data class Found(val category: ObjectionCategory, val claimFragment: String, val explanation: String)

    private val header = Regex("""^OBJECTION category=([A-Z_]+) claim="([^"]+)"""")

    fun extract(body: String): List<Found> {
        val found = mutableListOf<Found>()
        val lines = body.lines()
        var index = 0
        while (index < lines.size) {
            val match = header.find(lines[index].trim())
            if (match == null) {
                index += 1
                continue
            }
            val category = runCatching { ObjectionCategory.valueOf(match.groupValues[1]) }.getOrNull()
            val fragment = match.groupValues[2].trim()
            val explanation = StringBuilder()
            index += 1
            while (index < lines.size && header.find(lines[index].trim()) == null) {
                val line = lines[index].trim().removePrefix("explanation:").trim()
                if (line.isNotEmpty()) explanation.append(line).append(' ')
                index += 1
            }
            if (category != null && fragment.length >= 12 && explanation.isNotBlank()) {
                found += Found(category, fragment, explanation.toString().trim())
            }
        }
        return found
    }

    fun matchingClaim(fragment: String, claims: List<Pair<String, String>>): String? =
        claims.firstOrNull { (_, statement) -> statement.contains(fragment) }?.first
}

object VerificationExtractor {
    data class Found(val outcome: VerificationOutcome, val claimFragment: String, val note: String)

    private val header = Regex("""^VERIFICATION outcome=([A-Z_]+) claim="([^"]+)"""")

    fun extract(body: String): List<Found> {
        val found = mutableListOf<Found>()
        body.lines().forEach { raw ->
            val match = header.find(raw.trim()) ?: return@forEach
            val outcome = runCatching { VerificationOutcome.valueOf(match.groupValues[1]) }.getOrNull() ?: return@forEach
            val fragment = match.groupValues[2].trim()
            if (fragment.length >= 12) found += Found(outcome, fragment, raw.trim())
        }
        return found
    }
}

object DiscussionStop {
    enum class Reason {
        CONTINUE,
        REQUIREMENTS_ADDRESSED,
        NO_PROGRESS,
        REPEATED,
        BUDGET,
        DEADLINE,
        ROUND_LIMIT,
        CANCELLED,
    }

    data class Decision(val reason: Reason, val note: String)

    fun assess(
        cancelled: Boolean,
        deadlinePassed: Boolean,
        reviewRound: Int,
        roundLimit: Int,
        remainingAutomatedBudget: Int,
        automatedWorkStillOpen: Boolean,
        repeatedArgument: Boolean,
        researchComplete: Boolean,
        critiqueRequired: Boolean,
        critiqueComplete: Boolean,
        materialDisagreement: Boolean,
    ): Decision = when {
        cancelled -> Decision(Reason.CANCELLED, "The user stopped the discussion. Unresolved objections are preserved.")
        deadlinePassed -> Decision(Reason.DEADLINE, "The time limit ended the discussion. Unresolved objections are preserved.")
        remainingAutomatedBudget <= 0 && automatedWorkStillOpen ->
            Decision(Reason.BUDGET, "The automated request budget is exhausted. No further automated request is sent.")
        repeatedArgument -> Decision(Reason.REPEATED, "A repeated argument stopped another review round. Unresolved objections are preserved.")
        reviewRound >= roundLimit && materialDisagreement ->
            Decision(Reason.ROUND_LIMIT, "The round limit stopped another review. Unresolved objections are preserved.")
        researchComplete && critiqueRequired && critiqueComplete && !materialDisagreement ->
            Decision(Reason.REQUIREMENTS_ADDRESSED, "The recorded objections are addressed. This is not a confidence score.")
        researchComplete && !critiqueRequired && !materialDisagreement ->
            Decision(Reason.REQUIREMENTS_ADDRESSED, "A simple request does not open another review round.")
        researchComplete && critiqueComplete && materialDisagreement && reviewRound >= roundLimit ->
            Decision(Reason.NO_PROGRESS, "No further review round is available. Unresolved objections are preserved.")
        else -> Decision(Reason.CONTINUE, "The discussion can continue.")
    }
}

object DiscussionSynthesis {
    data class Input(
        val importedFinal: String?,
        val claims: List<String>,
        val evidence: List<String>,
        val assumptions: List<String>,
        val corrections: List<String>,
        val alternatives: List<String>,
        val unresolved: List<String>,
        val participants: List<String>,
        val verification: String,
        val independence: String,
    )

    fun compose(input: Input): String = buildString {
        appendLine("Recorded synthesis. This is assembled from stored discussion records. It is not a live model response.")
        appendLine("Direct answer: ${input.importedFinal?.trim().orEmpty().ifBlank { "No final answer has been imported." }}")
        appendLine("Key findings:")
        appendFindings(input.claims, "No participant claim is recorded.")
        appendLine("Supporting evidence:")
        appendFindings(input.evidence, "No source was recorded.")
        appendLine("Important assumptions:")
        appendFindings(input.assumptions, "No assumption was recorded.")
        appendLine("Corrections made during discussion:")
        appendFindings(input.corrections, "No correction is recorded.")
        appendLine("Alternative interpretations:")
        appendFindings(input.alternatives, "No alternative interpretation is recorded.")
        appendLine("Unresolved disagreements:")
        appendFindings(input.unresolved, "No unresolved disagreement is recorded.")
        appendLine("Limitations: Participant assertions are not verified evidence unless a verification result says so. Agreement between models is not proof.")
        appendLine("Participants: ${input.participants.ifEmpty { listOf("No participant is assigned.") }.joinToString("; ")}")
        appendLine("Verification status: ${input.verification.ifBlank { "Not recorded." }}")
        append(input.independence)
    }.trim()

    private fun StringBuilder.appendFindings(items: List<String>, empty: String) {
        if (items.isEmpty()) {
            appendLine(empty)
        } else {
            items.forEach { appendLine("- $it") }
        }
    }
}

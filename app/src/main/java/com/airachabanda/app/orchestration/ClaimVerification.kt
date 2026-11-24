package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.ResolutionStatus
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object ClaimLedger {
    enum class Kind {
        FACTUAL,
        MATHEMATICAL,
        LOGICAL_CONCLUSION,
        ASSUMPTION,
        PREDICTION,
        RECOMMENDATION,
        OPINION,
        SOURCE_DEPENDENT,
    }

    data class Parsed(
        val kind: Kind,
        val taskId: String,
        val dependsOn: String,
        val corrects: String,
        val body: String,
    )

    private val header = Regex("""^CLAIM kind=([A-Z_]+) task=(\S*) depends=(\S*) corrects=(\S*)$""")

    fun format(kind: Kind, taskId: String, body: String, dependsOn: String = "", corrects: String = ""): String =
        "CLAIM kind=${kind.name} task=${taskId.trim()} depends=${dependsOn.trim()} corrects=${corrects.trim()}\n${body.trim()}"

    fun parse(statement: String): Parsed {
        val lines = statement.lines()
        val match = header.find(lines.firstOrNull()?.trim().orEmpty())
        if (match == null) {
            return Parsed(Kind.SOURCE_DEPENDENT, "", "", "", statement.trim())
        }
        val kind = runCatching { Kind.valueOf(match.groupValues[1]) }.getOrDefault(Kind.SOURCE_DEPENDENT)
        return Parsed(
            kind = kind,
            taskId = match.groupValues[2],
            dependsOn = match.groupValues[3],
            corrects = match.groupValues[4],
            body = lines.drop(1).joinToString("\n").trim(),
        )
    }

    fun kindFor(body: String): Kind = if (LocalMath.check(body).status == LocalMath.Status.VERIFIED ||
        LocalMath.check(body).status == LocalMath.Status.CONTRADICTED
    ) {
        Kind.MATHEMATICAL
    } else {
        Kind.SOURCE_DEPENDENT
    }
}

object LocalMath {
    enum class Status { VERIFIED, CONTRADICTED, UNVERIFIED, REJECTED }

    data class Outcome(val status: Status, val value: BigDecimal?, val reason: String)

    private val math = MathContext(16, RoundingMode.HALF_UP)
    private const val MAX_CHARS = 160
    private const val MAX_DEPTH = 8
    private const val MAX_STEPS = 48
    private val units = mapOf(
        "km" to BigDecimal("1000"),
        "m" to BigDecimal.ONE,
        "cm" to BigDecimal("0.01"),
        "mm" to BigDecimal("0.001"),
        "kg" to BigDecimal("1000"),
        "g" to BigDecimal.ONE,
        "lb" to BigDecimal("453.59237"),
        "mi" to BigDecimal("1609.344"),
        "h" to BigDecimal("3600"),
        "min" to BigDecimal("60"),
        "s" to BigDecimal.ONE,
    )

    fun check(raw: String): Outcome {
        val text = raw.trim()
        if (text.isEmpty()) return rejected("The calculation is empty.")
        if (text.length > MAX_CHARS) return rejected("The expression is too long.")
        if (text.any { it == '{' || it == '}' || it == ';' || it == '`' || it == '\\' }) {
            return rejected("The expression is not a supported calculation.")
        }
        val sides = text.split("=").map { it.trim() }
        if (sides.size > 2) return rejected("The expression has too many equals signs.")
        val value = special(sides[0]) ?: evaluate(sides[0])
        if (value.value == null) return value.copy(status = if (value.status == Status.UNVERIFIED) Status.REJECTED else value.status)
        if (sides.size == 1) {
            return Outcome(Status.UNVERIFIED, value.value, "The expression was read, and no claimed result was supplied, so it is not verified.")
        }
        val expected = evaluate(sides[1])
        if (expected.value == null) return rejected(expected.reason)
        return if (same(value.value, expected.value)) {
            Outcome(Status.VERIFIED, value.value, "Local arithmetic supports the claimed result. This is not a provider result.")
        } else {
            Outcome(Status.CONTRADICTED, value.value, "Local arithmetic does not support the claimed result. The calculated value is ${value.value.stripTrailingZeros().toPlainString()}.")
        }
    }

    private fun special(text: String): Outcome? = unitConversion(text) ?: temperature(text) ?: rounding(text) ?: percentOf(text)

    private fun unitConversion(text: String): Outcome? {
        val match = Regex("""^([0-9]+(?:\.[0-9]+)?)\s+(km|m|cm|mm|kg|g|lb|mi|h|min|s)\s+to\s+(km|m|cm|mm|kg|g|lb|mi|h|min|s)$""").find(text) ?: return null
        val quantity = BigDecimal(match.groupValues[1])
        val from = units.getValue(match.groupValues[2])
        val to = units.getValue(match.groupValues[3])
        if (to.compareTo(BigDecimal.ZERO) == 0) return rejected("The target unit is not supported.")
        val value = quantity.multiply(from, math).divide(to, math)
        return Outcome(Status.UNVERIFIED, value, "The unit conversion was calculated. It is verified only when a claimed result matches it.")
    }

    private fun temperature(text: String): Outcome? {
        val match = Regex("""^([0-9]+(?:\.[0-9]+)?)\s+([CF])\s+to\s+([CF])$""").find(text) ?: return null
        val quantity = BigDecimal(match.groupValues[1])
        val from = match.groupValues[2]
        val to = match.groupValues[3]
        val value = when {
            from == to -> quantity
            from == "C" && to == "F" -> quantity.multiply(BigDecimal("1.8"), math).add(BigDecimal("32"))
            from == "F" && to == "C" -> quantity.subtract(BigDecimal("32")).divide(BigDecimal("1.8"), math)
            else -> return rejected("The temperature conversion is not supported.")
        }
        return Outcome(Status.UNVERIFIED, value, "The temperature conversion was calculated. It is verified only when a claimed result matches it.")
    }

    private fun rounding(text: String): Outcome? {
        val match = Regex("""^round\s+([0-9]+(?:\.[0-9]+)?)\s+to\s+([0-9]+)$""").find(text) ?: return null
        val places = match.groupValues[2].toInt()
        if (places > 8) return rejected("The rounding request asks for too many places.")
        val value = BigDecimal(match.groupValues[1]).setScale(places, RoundingMode.HALF_UP)
        return Outcome(Status.UNVERIFIED, value, "The value was rounded with half-up rounding. It is verified only when a claimed result matches it.")
    }

    private fun percentOf(text: String): Outcome? {
        val match = Regex("""^([0-9]+(?:\.[0-9]+)?)%\s+of\s+([0-9]+(?:\.[0-9]+)?)$""").find(text) ?: return null
        val value = BigDecimal(match.groupValues[1]).divide(BigDecimal("100"), math).multiply(BigDecimal(match.groupValues[2]), math)
        return Outcome(Status.UNVERIFIED, value, "The percentage was calculated. It is verified only when a claimed result matches it.")
    }

    private fun evaluate(expression: String): Outcome {
        if (expression.any { it.isLetter() }) return rejected("The expression contains an unsupported operation.")
        val parser = Parser(expression)
        val value = parser.parse()
        if (parser.failed != null) return rejected(parser.failed!!)
        if (!parser.finished()) return rejected("The expression has unsupported trailing text.")
        return Outcome(Status.UNVERIFIED, value, "The expression was calculated.")
    }

    private fun same(left: BigDecimal, right: BigDecimal): Boolean =
        left.subtract(right).abs().compareTo(BigDecimal("0.0000001")) <= 0

    private fun rejected(reason: String) = Outcome(Status.REJECTED, null, reason)

    private class Parser(private val input: String) {
        private var index = 0
        private var steps = 0
        var failed: String? = null

        fun finished(): Boolean {
            skip()
            return index >= input.length
        }

        fun parse(): BigDecimal? = parseExpression(0)

        private fun parseExpression(depth: Int): BigDecimal? {
            if (depth > MAX_DEPTH) {
                failed = "The expression is nested too deeply."
                return null
            }
            var value = parseTerm(depth) ?: return null
            while (true) {
                skip()
                val operator = peek()
                if (operator != '+' && operator != '-') break
                index += 1
                val right = parseTerm(depth) ?: return null
                value = if (operator == '+') value.add(right, math) else value.subtract(right, math)
                if (!countStep()) return null
            }
            return value
        }

        private fun parseTerm(depth: Int): BigDecimal? {
            var value = parseFactor(depth) ?: return null
            while (true) {
                skip()
                val operator = peek()
                if (operator != '*' && operator != '/' && operator != ':') break
                index += 1
                val right = parseFactor(depth) ?: return null
                if (operator == '*') {
                    value = value.multiply(right, math)
                } else {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        failed = "Division by zero was rejected."
                        return null
                    }
                    value = value.divide(right, math)
                }
                if (!countStep()) return null
            }
            return value
        }

        private fun parseFactor(depth: Int): BigDecimal? {
            skip()
            if (peek() == '(') {
                index += 1
                val value = parseExpression(depth + 1) ?: return null
                skip()
                if (peek() != ')') {
                    failed = "A parenthesis was not closed."
                    return null
                }
                index += 1
                return applyPercent(value)
            }
            val start = index
            if (peek() == '-' || peek() == '+') index += 1
            if (!peek().isDigit()) {
                failed = "The expression is malformed."
                return null
            }
            while (peek().isDigit() || peek() == '.') index += 1
            val number = runCatching { BigDecimal(input.substring(start, index)) }.getOrNull()
            if (number == null) {
                failed = "The number is malformed."
                return null
            }
            return applyPercent(number)
        }

        private fun applyPercent(value: BigDecimal): BigDecimal {
            skip()
            if (peek() != '%') return value
            index += 1
            return value.divide(BigDecimal("100"), math)
        }

        private fun countStep(): Boolean {
            steps += 1
            if (steps > MAX_STEPS) {
                failed = "The expression asks for too much work."
                return false
            }
            return true
        }

        private fun skip() {
            while (index < input.length && input[index].isWhitespace()) index += 1
        }

        private fun peek(): Char = if (index < input.length) input[index] else '\u0000'
    }
}

enum class EvidenceKind {
    USER_PROVIDED,
    PARTICIPANT_CITATION,
    RETRIEVED_SOURCE,
    VERIFIED_SUPPORT,
    CONTRADICTORY,
    UNSUPPORTED_ASSERTION,
    DOCUMENT_EXTRACT,
}

object EvidenceNotes {
    data class Record(
        val kind: EvidenceKind,
        val publisher: String,
        val published: String,
        val retrieved: String,
        val status: String,
        val excerpt: String,
        val sourceId: String = "",
        val canonical: String = "",
        val original: String = "",
        val title: String = "",
        val author: String = "",
        val fingerprint: String = "",
        val verification: String = "",
        val assessor: String = "",
        val claim: String = "",
        val metadata: String = "",
    )

    fun format(record: Record): String = buildString {
        appendLine("kind=${record.kind.name}")
        appendLine("publisher=${record.publisher}")
        appendLine("published=${record.published}")
        appendLine("retrieved=${record.retrieved}")
        appendLine("status=${record.status}")
        appendLine("sourceId=${record.sourceId}")
        appendLine("canonical=${record.canonical}")
        appendLine("original=${record.original}")
        appendLine("title=${record.title}")
        appendLine("author=${record.author}")
        appendLine("fingerprint=${record.fingerprint}")
        appendLine("verification=${record.verification}")
        appendLine("assessor=${record.assessor}")
        appendLine("claim=${record.claim}")
        appendLine("metadata=${record.metadata}")
        append("excerpt=${record.excerpt}")
    }.trim()

    fun parse(note: String): Record {
        val excerptAt = note.indexOf("\nexcerpt=")
        val head = if (excerptAt >= 0) note.substring(0, excerptAt) else note
        val excerpt = if (excerptAt >= 0) note.substring(excerptAt + "\nexcerpt=".length) else ""
        val fields = head.lines().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        val kind = runCatching { EvidenceKind.valueOf(fields["kind"].orEmpty()) }.getOrDefault(EvidenceKind.PARTICIPANT_CITATION)
        return Record(
            kind = kind,
            publisher = fields["publisher"].orEmpty(),
            published = fields["published"].orEmpty(),
            retrieved = fields["retrieved"].orEmpty(),
            status = fields["status"].orEmpty(),
            excerpt = excerpt,
            sourceId = fields["sourceId"].orEmpty(),
            canonical = fields["canonical"].orEmpty(),
            original = fields["original"].orEmpty(),
            title = fields["title"].orEmpty(),
            author = fields["author"].orEmpty(),
            fingerprint = fields["fingerprint"].orEmpty(),
            verification = fields["verification"].orEmpty(),
            assessor = fields["assessor"].orEmpty(),
            claim = fields["claim"].orEmpty(),
            metadata = fields["metadata"].orEmpty(),
        )
    }

    fun citation(locator: String): Record = Record(
        kind = EvidenceKind.PARTICIPANT_CITATION,
        publisher = "",
        published = "",
        retrieved = "",
        status = "UNVERIFIED",
        excerpt = "A locator by itself is not verified evidence. Locator: $locator",
    )

    fun userProvided(excerpt: String): Record = Record(
        kind = EvidenceKind.USER_PROVIDED,
        publisher = "user",
        published = "",
        retrieved = "",
        status = "USER_SUPPLIED",
        excerpt = excerpt,
    )

    fun contradictory(excerpt: String): Record = Record(
        kind = EvidenceKind.CONTRADICTORY,
        publisher = "",
        published = "",
        retrieved = "",
        status = "CONTRADICTS_CLAIM",
        excerpt = excerpt,
    )
}

interface SourceRetriever {
    fun retrieve(locator: String): Retrieval
}

data class Retrieval(val performed: Boolean, val supportsClaim: Boolean, val reason: String)

object DeferredSourceRetriever : SourceRetriever {
    override fun retrieve(locator: String): Retrieval = Retrieval(
        performed = false,
        supportsClaim = false,
        reason = "Live web retrieval is deferred. A locator was not fetched, and it does not support the claim.",
    )
}

object ObjectionLifecycle {
    private val allowed = mapOf(
        ResolutionStatus.OPEN to setOf(
            ResolutionStatus.UNDER_REVIEW,
            ResolutionStatus.REBUTTAL_REQUESTED,
            ResolutionStatus.CONFIRMED_ERROR,
            ResolutionStatus.UNRESOLVED,
            ResolutionStatus.INSUFFICIENT_EVIDENCE,
        ),
        ResolutionStatus.UNDER_REVIEW to setOf(
            ResolutionStatus.REBUTTAL_REQUESTED,
            ResolutionStatus.CONFIRMED_ERROR,
            ResolutionStatus.REBUTTED_WITH_EVIDENCE,
            ResolutionStatus.UNRESOLVED,
            ResolutionStatus.INSUFFICIENT_EVIDENCE,
        ),
        ResolutionStatus.REBUTTAL_REQUESTED to setOf(
            ResolutionStatus.REBUTTED_WITH_EVIDENCE,
            ResolutionStatus.CONFIRMED_ERROR,
            ResolutionStatus.UNRESOLVED,
            ResolutionStatus.INSUFFICIENT_EVIDENCE,
        ),
        ResolutionStatus.CONFIRMED_ERROR to setOf(ResolutionStatus.CORRECTED),
        ResolutionStatus.CORRECTED to setOf(ResolutionStatus.OPEN),
        ResolutionStatus.REBUTTED_WITH_EVIDENCE to setOf(ResolutionStatus.OPEN),
        ResolutionStatus.UNRESOLVED to setOf(ResolutionStatus.OPEN, ResolutionStatus.UNDER_REVIEW),
        ResolutionStatus.INSUFFICIENT_EVIDENCE to setOf(ResolutionStatus.OPEN, ResolutionStatus.UNDER_REVIEW),
    )

    data class Decision(val accepted: Boolean, val reason: String)

    fun transition(from: ResolutionStatus, to: ResolutionStatus, reason: String): Decision {
        if (reason.isBlank()) return Decision(false, "A status change needs a reason. The objection was not changed.")
        if (to !in allowed.getValue(from)) {
            return Decision(false, "The objection cannot move from ${from.name} to ${to.name}. Disagreement alone does not resolve it.")
        }
        return Decision(true, "Status ${from.name} became ${to.name}. Reason: ${reason.trim()}")
    }

    fun disagreementResolves(): Boolean = false
}

object CorrectionPropagation {
    fun dependentIds(statements: List<Pair<String, String>>, correctedId: String): List<String> =
        statements.mapNotNull { (id, statement) ->
            val parsed = ClaimLedger.parse(statement)
            if (parsed.dependsOn == correctedId && id != correctedId) id else null
        }

    fun finalIsStale(finalCreatedAt: Long, correctionCreatedAt: Long): Boolean = correctionCreatedAt > finalCreatedAt
}

object VerificationStop {
    enum class Reason {
        CONTINUE,
        VERIFICATION_COMPLETED,
        VERIFICATION_UNAVAILABLE,
        INSUFFICIENT_EVIDENCE,
        UNRESOLVED_DISAGREEMENT,
        BUDGET,
        DEADLINE,
        USER_STOPPED,
    }

    data class Decision(val reason: Reason, val note: String)

    fun assess(
        userStopped: Boolean,
        deadlineReached: Boolean,
        budgetExhausted: Boolean,
        requirementsAddressed: Boolean,
        materialVerificationStillJustified: Boolean,
        verifierAvailable: Boolean,
        insufficientEvidence: Boolean,
        unresolvedDisagreement: Boolean,
    ): Decision = when {
        userStopped -> Decision(Reason.USER_STOPPED, "The user stopped the discussion.")
        deadlineReached -> Decision(Reason.DEADLINE, "The deadline was reached.")
        budgetExhausted -> Decision(Reason.BUDGET, "The request budget is exhausted.")
        unresolvedDisagreement -> Decision(Reason.UNRESOLVED_DISAGREEMENT, "A material disagreement remains unresolved.")
        insufficientEvidence -> Decision(Reason.INSUFFICIENT_EVIDENCE, "The stored evidence is not sufficient.")
        materialVerificationStillJustified && !verifierAvailable ->
            Decision(Reason.VERIFICATION_UNAVAILABLE, "Verification was still justified, and no independent verifier was available.")
        requirementsAddressed && !materialVerificationStillJustified ->
            Decision(Reason.VERIFICATION_COMPLETED, "The recorded requirements are addressed, and no further material verification task is justified.")
        else -> Decision(Reason.CONTINUE, "A material verification task is still justified. This is not a confidence score.")
    }
}

object AccuracyBenchmark {
    enum class Arm {
        SINGLE_MODEL,
        INDEPENDENT_ANSWERS,
        DISCUSSION_WITHOUT_ADVOCATE,
        DISCUSSION_WITH_ADVOCATE,
    }

    data class HeldOutQuestion(val id: String, val prompt: String, val reference: String)

    data class Submission(
        val arm: Arm,
        val answer: String,
        val citationValid: Boolean?,
        val falseObjections: Int,
        val corrections: Int,
        val errorsIntroduced: Int,
        val unresolved: Int,
        val requests: Int,
        val latencyMs: Long,
    )

    fun report(question: HeldOutQuestion, submissions: List<Submission>): String = buildString {
        appendLine("Local score of supplied answers against a held-out reference. This is not a measured improvement in live AI accuracy.")
        appendLine("Question ${question.id}. Reference established outside the model response.")
        if (submissions.isEmpty()) {
            append("No arm was scored. Live benchmarking is deferred.")
            return@buildString
        }
        submissions.forEach { submission ->
            val math = LocalMath.check(submission.answer)
            val factual = submission.answer.trim() == question.reference.trim() ||
                (math.status == LocalMath.Status.VERIFIED && question.reference.trim() == math.value?.stripTrailingZeros()?.toPlainString())
            appendLine("${submission.arm.name}: factual ${if (factual) "matches the reference" else "does not match the reference"}.")
            appendLine("Mathematical status ${math.status.name}. Citation ${submission.citationValid ?: "not assessed"}.")
            appendLine("False objections ${submission.falseObjections}. Corrections ${submission.corrections}. Errors introduced ${submission.errorsIntroduced}. Unresolved ${submission.unresolved}. Requests ${submission.requests}. Latency ${submission.latencyMs} ms.")
        }
    }.trim()
}

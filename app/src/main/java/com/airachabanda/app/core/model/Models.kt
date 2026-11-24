package com.airachabanda.app.core.model

enum class ProviderId {
    CHATGPT,
    CLAUDE,
    GEMINI,
    GROK,
    PERPLEXITY,
    DEEPSEEK,
    COPILOT,
    QWEN,
    MISTRAL,
    KIMI,
    META_AI,
    GROQ,
    OPENROUTER,
    CLOUDFLARE,
    LOCAL,
}

enum class ProviderKind {
    CONSUMER_PRODUCT,
    INFERENCE_SERVICE,
}

enum class AccountChoice {
    NOT_CONFIGURED,
    FREE_ACCOUNT,
    PAID_SUBSCRIPTION,
}

enum class ParticipationMode {
    AUTOMATED,
    ASSISTED,
    UNAVAILABLE,
}

enum class BillingEligibility {
    VERIFIED_FREE,
    INCLUDED_SUBSCRIPTION_VERIFIED,
    USER_CONFIGURATION_REQUIRED,
    PAID,
    UNKNOWN,
    BLOCKED,
}

enum class IntegrationMethod {
    NONE,
    ASSISTED_OFFICIAL_INTERFACE,
    OAUTH_PLAN_USAGE_PREPARED,
    API_KEY_PENDING_VERIFICATION,
}

enum class ResearchMode {
    QUICK,
    STANDARD,
    DEEP_RESEARCH,
    CUSTOM,
}

enum class AssistedTaskState {
    PENDING,
    READY,
    RUNNING,
    AWAITING_USER_RESPONSE,
    COMPLETED,
    SKIPPED,
    DEFERRED,
    BLOCKED,
    CANCELLED,
    FAILED,
}

enum class MessageSource {
    USER,
    AUTOMATED_PROVIDER,
    USER_SUPPLIED_PROVIDER_ATTRIBUTED,
}

enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class ProviderDefinition(
    val id: ProviderId,
    val displayName: String,
    val kind: ProviderKind,
    val summary: String,
    val defaultParticipation: ParticipationMode,
    val defaultBilling: BillingEligibility,
    val defaultIntegration: IntegrationMethod,
    val officialHttpsUrl: String?,
    val prominent: Boolean,
)

data class DeclaredPlanOption(
    val id: String,
    val label: String,
    val sourceUrl: String,
)

data class Conversation(
    val id: String,
    val title: String,
    val researchMode: ResearchMode,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val providerId: ProviderId?,
    val source: MessageSource,
    val body: String,
    val roundNumber: Int?,
    val createdAtEpochMs: Long,
)

data class ProviderConfig(
    val providerId: ProviderId,
    val accountChoice: AccountChoice,
    val declaredPlanId: String?,
    val billingEligibility: BillingEligibility,
    val participationMode: ParticipationMode,
    val integrationMethod: IntegrationMethod,
    val automatedInferenceEnabled: Boolean,
    val updatedAtEpochMs: Long,
)

data class ResearchSession(
    val id: String,
    val conversationId: String,
    val researchMode: ResearchMode,
    val createdAtEpochMs: Long,
)

data class ResearchTask(
    val id: String,
    val sessionId: String,
    val conversationId: String,
    val providerId: ProviderId,
    val roundNumber: Int,
    val state: AssistedTaskState,
    val prompt: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val role: String = "RESEARCHER",
    val kind: String = "RESEARCH",
)

data class ImportedResponse(
    val id: String,
    val conversationId: String,
    val taskId: String?,
    val providerId: ProviderId,
    val roundNumber: Int,
    val body: String,
    val userSupplied: Boolean,
    val authenticated: Boolean,
    val createdAtEpochMs: Long,
    val userDeclaredModelId: String = ModelIdentity.UNKNOWN,
    val verifiedModelId: String = ModelIdentity.UNKNOWN,
)

data class WorkflowEvent(
    val id: String,
    val conversationId: String?,
    val providerId: ProviderId?,
    val type: String,
    val summary: String,
    val createdAtEpochMs: Long,
)

data class ImportDraft(
    val conversationId: String,
    val providerId: ProviderId,
    val roundNumber: Int,
    val body: String,
    val taskId: String?,
    val userDeclaredModelId: String? = null,
)

object ModelIdentity {
    const val UNKNOWN = "unknown"

    fun declared(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals(UNKNOWN, ignoreCase = true)) return UNKNOWN
        return value
    }
}

enum class SessionState {
    CREATED,
    PLANNING,
    INDEPENDENT_RESEARCH,
    COLLECTING_RESPONSES,
    PRELIMINARY_ANALYSIS,
    CRITICAL_REVIEW,
    REBUTTAL,
    EVIDENCE_VERIFICATION,
    REASSESSMENT,
    SYNTHESIS,
    COMPLETED,
    AWAITING_USER_RESPONSE,
    PAUSED,
    BLOCKED,
    CANCELLED,
    FAILED,
}

enum class ParticipantRole {
    MODERATOR,
    RESEARCHER,
    ANALYST,
    DOMAIN_SPECIALIST,
    DEVILS_ADVOCATE,
    EVIDENCE_VERIFIER,
    FINAL_SYNTHESIZER,
}

enum class QuestionComplexity {
    LOW,
    MODERATE,
    HIGH,
}

enum class ConclusionKind {
    PRELIMINARY,
    FINAL,
}

enum class VerificationOutcome {
    SUPPORTED,
    CONTRADICTED,
    INSUFFICIENT_EVIDENCE,
}

enum class ObjectionCategory {
    UNSUPPORTED_ASSUMPTION,
    FACTUAL_ERROR,
    MATHEMATICAL_ERROR,
    LOGICAL_INCONSISTENCY,
    MISSING_CONTEXT,
    COUNTEREXAMPLE,
    WEAK_CITATION,
    ALTERNATIVE_EXPLANATION,
}

enum class ObjectionSeverity {
    LOW,
    MEDIUM,
    HIGH,
}

enum class ResolutionStatus {
    OPEN,
    CONFIRMED_ERROR,
    CORRECTED,
    REBUTTED_WITH_EVIDENCE,
    UNRESOLVED,
    INSUFFICIENT_EVIDENCE,
}

data class Objection(
    val id: String,
    val targetClaimId: String,
    val authorParticipantId: ProviderId,
    val underlyingModelId: String?,
    val roundNumber: Int,
    val category: ObjectionCategory,
    val severity: ObjectionSeverity,
    val explanation: String,
    val supportingEvidence: String,
    val requestedVerification: String,
    val rebuttal: String?,
    val resolution: ResolutionStatus,
)

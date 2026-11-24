package com.airachabanda.app.provider.dispatch

import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.persistence.CouncilDao
import com.airachabanda.app.persistence.DiscussionDao
import com.airachabanda.app.persistence.MessageEntity
import com.airachabanda.app.persistence.ProviderCompletionEntity
import com.airachabanda.app.persistence.RequestBudgetEntryEntity
import com.airachabanda.app.provider.cloudflare.CloudflareAdapter
import com.airachabanda.app.provider.cloudflare.CloudflareOutcome
import com.airachabanda.app.provider.gemini.GeminiAdapter
import com.airachabanda.app.provider.gemini.GeminiOutcome
import com.airachabanda.app.provider.local.LocalInferencePolicy
import com.airachabanda.app.provider.local.ondevice.OnDeviceDispatch
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelCatalog
import com.airachabanda.app.provider.openrouter.DispatchOutcome
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import com.airachabanda.app.provider.openrouter.ResponseDisposition

data class PreparedCall(
    val providerId: ProviderId,
    val modelId: String,
    val secret: String,
    val accountId: String = "",
    val subscriptionsRead: Boolean = false,
    val paidWorkersPlanListed: Boolean = false,
    val textGeneration: Boolean = true,
    val positiveFreePlan: Boolean = false,
    val billingLinked: Boolean? = null,
    val freeTierKnown: Boolean = false,
    val paidModel: Boolean = false,
    val quotaExhausted: Boolean = false,
    val credentialBoundToProject: Boolean = false,
)

class PreparedProviderDispatch(
    private val cloudflare: CloudflareAdapter,
    private val gemini: GeminiAdapter,
    private val council: CouncilDao,
    private val discussion: DiscussionDao,
    private val clock: () -> Long,
    private val ids: () -> String,
) {
    suspend fun dispatch(
        sessionId: String,
        conversationId: String,
        taskId: String,
        roundNumber: Int,
        prompt: String,
        sessionBudgetRemaining: Int,
        call: PreparedCall,
    ): DispatchOutcome {
        val outcome = when (call.providerId) {
            ProviderId.CLOUDFLARE -> cloudflare.complete(
                accountId = call.accountId,
                apiToken = call.secret,
                modelId = call.modelId,
                prompt = prompt,
                sessionBudgetRemaining = sessionBudgetRemaining,
                quotaExhausted = call.quotaExhausted,
                subscriptionsRead = call.subscriptionsRead,
                paidWorkersPlanListed = call.paidWorkersPlanListed,
                textGeneration = call.textGeneration,
                positiveFreePlan = call.positiveFreePlan,
            ).toStored()
            ProviderId.LOCAL -> return DispatchOutcome(
                OpenRouterFailure.POLICY,
                if (OnDeviceModelCatalog.find(call.modelId) != null) {
                    OnDeviceDispatch.reason(call.modelId)
                } else {
                    LocalInferencePolicy.prepare(
                        LocalInferencePolicy.Selection(
                            modelId = call.modelId,
                            endpointHost = "127.0.0.1",
                            cloudDisabled = true,
                            contextTokens = LocalInferencePolicy.MAX_CONTEXT_TOKENS,
                            timeoutMs = 30_000,
                            budgetRemaining = sessionBudgetRemaining,
                        ),
                    ).reason
                },
                completed = false,
            )
            ProviderId.GEMINI -> gemini.complete(
                apiKey = call.secret,
                modelId = call.modelId,
                prompt = prompt,
                sessionBudgetRemaining = sessionBudgetRemaining,
                billingLinked = call.billingLinked,
                freeTierKnown = call.freeTierKnown,
                paidModel = call.paidModel,
                quotaExhausted = call.quotaExhausted,
                credentialBoundToProject = call.credentialBoundToProject,
            ).toStored()
            else -> return DispatchOutcome(OpenRouterFailure.POLICY, "This dispatcher only accepts Cloudflare and Gemini.", completed = false)
        }
        val keep = outcome.disposition == ResponseDisposition.COMPLETED ||
            outcome.disposition == ResponseDisposition.TRUNCATED ||
            outcome.disposition == ResponseDisposition.INTERRUPTED
        if (!keep) return DispatchOutcome(outcome.failure, outcome.reason, completed = false)
        val generationId = outcome.generationId ?: return DispatchOutcome(
            OpenRouterFailure.MALFORMED,
            "The response had no stable identity. It was not stored as a final answer.",
            completed = false,
        )
        if (discussion.completion(generationId) != null) {
            return DispatchOutcome(
                OpenRouterFailure.DUPLICATE,
                "This provider response was already stored. It was not inserted again and the request budget was not consumed again.",
                completed = outcome.disposition == ResponseDisposition.COMPLETED,
            )
        }
        val finished = outcome.disposition == ResponseDisposition.COMPLETED && outcome.completed
        discussion.insertCompletion(
            ProviderCompletionEntity(
                id = generationId,
                sessionId = sessionId,
                taskId = taskId,
                providerId = call.providerId.name,
                modelId = ModelIdentity.declared(outcome.modelId),
                body = outcome.text,
                complete = finished,
                createdAtEpochMs = clock(),
                disposition = outcome.disposition.name,
            ),
        )
        if (finished) {
            council.insertMessage(
                MessageEntity(
                    id = ids(),
                    conversationId = conversationId,
                    providerId = call.providerId.name,
                    source = MessageSource.AUTOMATED_PROVIDER.name,
                    body = outcome.text,
                    roundNumber = roundNumber,
                    createdAtEpochMs = clock(),
                ),
            )
        }
        val session = discussion.session(sessionId)
        if (session != null) {
            discussion.consumeBudget(
                RequestBudgetEntryEntity(
                    id = "budget-$generationId",
                    sessionId = sessionId,
                    participantId = null,
                    amount = 1,
                    reason = "${call.providerId.name} ${outcome.disposition.name}",
                    createdAtEpochMs = clock(),
                ),
                consumed = session.requestsConsumed + 1,
                updatedAt = clock(),
            )
        }
        return DispatchOutcome(outcome.failure, outcome.reason, completed = finished)
    }
}

private data class StoredOutcome(
    val failure: OpenRouterFailure,
    val disposition: ResponseDisposition,
    val reason: String,
    val modelId: String,
    val text: String,
    val generationId: String?,
    val completed: Boolean,
)

private fun CloudflareOutcome.toStored() = StoredOutcome(failure, disposition, reason, modelId, text, generationId, completed)

private fun GeminiOutcome.toStored() = StoredOutcome(failure, disposition, reason, modelId, text, generationId, completed)

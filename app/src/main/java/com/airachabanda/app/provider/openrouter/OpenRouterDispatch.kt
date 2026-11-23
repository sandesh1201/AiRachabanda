package com.airachabanda.app.provider.openrouter

import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.persistence.CouncilDao
import com.airachabanda.app.persistence.DiscussionDao
import com.airachabanda.app.persistence.MessageEntity
import com.airachabanda.app.persistence.ProviderCompletionEntity

data class DispatchCommand(
    val sessionId: String,
    val conversationId: String,
    val taskId: String,
    val prompt: String,
    val candidate: OpenRouterCandidate,
    val apiKey: String,
    val roundNumber: Int,
)

data class DispatchOutcome(
    val failure: OpenRouterFailure,
    val reason: String,
    val completed: Boolean,
)

class OpenRouterDispatch(
    private val adapter: OpenRouterAdapter,
    private val council: CouncilDao,
    private val discussion: DiscussionDao,
    private val clock: () -> Long,
    private val ids: () -> String,
) {
    suspend fun dispatch(command: DispatchCommand): DispatchOutcome {
        val outcome = adapter.complete(command.apiKey, command.candidate, command.prompt)
        if (!outcome.completed) {
            return DispatchOutcome(outcome.failure, outcome.reason, completed = false)
        }
        val generationId = outcome.generationId ?: return DispatchOutcome(
            OpenRouterFailure.MALFORMED,
            "The completed response did not include a generation id. It was not stored.",
            completed = false,
        )
        if (discussion.completion(generationId) != null) {
            return DispatchOutcome(
                OpenRouterFailure.DUPLICATE,
                "This provider response was already stored. It was not inserted again.",
                completed = true,
            )
        }
        val modelId = ModelIdentity.declared(outcome.modelId)
        discussion.insertCompletion(
            ProviderCompletionEntity(
                id = generationId,
                sessionId = command.sessionId,
                taskId = command.taskId,
                providerId = ProviderId.OPENROUTER.name,
                modelId = modelId,
                body = outcome.text,
                complete = true,
                createdAtEpochMs = clock(),
            ),
        )
        council.insertMessage(
            MessageEntity(
                id = ids(),
                conversationId = command.conversationId,
                providerId = ProviderId.OPENROUTER.name,
                source = MessageSource.AUTOMATED_PROVIDER.name,
                body = outcome.text,
                roundNumber = command.roundNumber,
                createdAtEpochMs = clock(),
            ),
        )
        return DispatchOutcome(OpenRouterFailure.NONE, outcome.reason, completed = true)
    }
}

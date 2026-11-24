package com.airachabanda.app.persistence

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.Conversation
import com.airachabanda.app.core.model.ImportDraft
import com.airachabanda.app.core.model.IntegrationMethod
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResearchTask
import com.airachabanda.app.core.model.WorkflowEvent
import com.airachabanda.app.orchestration.ImportRules
import com.airachabanda.app.provider.ProviderRegistry
import com.airachabanda.app.security.CredentialAccounts
import com.airachabanda.app.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class CouncilRepository(
    private val dao: CouncilDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val credentials: CredentialStore? = null,
) {
    fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { rows -> rows.map { it.toModel() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        dao.observeConversation(id).map { it?.toModel() }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { rows -> rows.map { it.toModel() } }

    fun observeProviderConfigs(): Flow<List<ProviderConfig>> =
        dao.observeProviderConfigs().map { rows ->
            ProviderRegistry.all.map { definition ->
                val row = rows.firstOrNull { it.providerId == definition.id.name }
                row?.toModel() ?: definition.toDefaultConfig(clock())
            }
        }

    fun observeTasks(conversationId: String): Flow<List<ResearchTask>> =
        dao.observeTasks(conversationId).map { rows -> rows.map { it.toModel() } }

    fun observeEvents(): Flow<List<WorkflowEvent>> =
        dao.observeEvents().map { rows -> rows.map { it.toModel() } }

    suspend fun ensureSeeded() {
        if (dao.providerConfigCount() > 0) return
        val now = clock()
        ProviderRegistry.all.forEach { definition ->
            dao.upsertProvider(definition.toDefaultConfig(now).toEntity())
        }
    }

    suspend fun createConversation(title: String, mode: ResearchMode): String {
        val now = clock()
        val id = ids()
        dao.insertConversation(
            ConversationEntity(
                id = id,
                title = title.ifBlank { "Untitled conversation" },
                researchMode = mode.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        dao.insertEvent(
            event(
                conversationId = id,
                providerId = null,
                type = "CONVERSATION_CREATED",
                summary = "Conversation created. No provider has been asked to respond.",
            ),
        )
        return id
    }

    suspend fun renameConversation(id: String, title: String) {
        dao.renameConversation(id, title.ifBlank { "Untitled conversation" }, clock())
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteConversation(id)
    }

    suspend fun updateResearchMode(id: String, mode: ResearchMode) {
        dao.updateResearchMode(id, mode.name, clock())
    }

    suspend fun addUserMessage(conversationId: String, body: String) {
        val text = body.trim()
        require(text.isNotEmpty())
        dao.insertMessage(
            MessageEntity(
                id = ids(),
                conversationId = conversationId,
                providerId = null,
                source = MessageSource.USER.name,
                body = text,
                roundNumber = null,
                createdAtEpochMs = clock(),
            ),
        )
        dao.insertEvent(
            event(
                conversationId = conversationId,
                providerId = null,
                type = "USER_MESSAGE_STORED",
                summary = "Your message was stored on this device. It was not sent to a provider.",
            ),
        )
    }

    suspend fun saveAccountChoice(providerId: ProviderId, choice: AccountChoice, planId: String?) {
        ensureSeeded()
        val current = dao.findProvider(providerId.name)?.toModel()
            ?: ProviderRegistry.definition(providerId).toDefaultConfig(clock())
        val plan = if (choice == AccountChoice.PAID_SUBSCRIPTION) planId else null
        dao.upsertProvider(
            current.copy(
                accountChoice = choice,
                declaredPlanId = plan,
                updatedAtEpochMs = clock(),
            ).toEntity(),
        )
        if (choice == AccountChoice.NOT_CONFIGURED) {
            credentials?.delete(providerId.name, CredentialAccounts.DEFAULT)
        }
        dao.insertEvent(
            event(
                conversationId = null,
                providerId = providerId,
                type = "ACCOUNT_PREFERENCE_SAVED",
                summary = "Saved a user-declared account preference for ${ProviderRegistry.definition(providerId).displayName}. This does not verify a subscription or enable automation.",
            ),
        )
    }

    suspend fun prepareAssistedTask(
        conversationId: String,
        providerId: ProviderId,
        prompt: String,
        roundNumber: Int,
    ): String {
        require(providerId in ProviderRegistry.assistedImportProviders)
        require(prompt.isNotBlank())
        val mode = dao.conversation(conversationId)?.researchMode ?: ResearchMode.STANDARD.name
        val session = dao.latestSession(conversationId) ?: ResearchSessionEntity(
            id = ids(),
            conversationId = conversationId,
            researchMode = mode,
            createdAtEpochMs = clock(),
        ).also { dao.insertSession(it) }
        val taskId = ids()
        val now = clock()
        dao.insertTask(
            ResearchTaskEntity(
                id = taskId,
                sessionId = session.id,
                conversationId = conversationId,
                providerId = providerId.name,
                roundNumber = roundNumber,
                state = AssistedTaskState.AWAITING_USER_RESPONSE.name,
                prompt = prompt.trim(),
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        val name = ProviderRegistry.definition(providerId).displayName
        dao.insertEvent(
            event(
                conversationId = conversationId,
                providerId = providerId,
                type = "AWAITING_USER_RESPONSE",
                summary = "Waiting for you to submit the prepared prompt to $name and import the response. $name is not researching inside AI Rachabanda.",
            ),
        )
        return taskId
    }

    suspend fun updateTaskState(taskId: String, state: AssistedTaskState) {
        val task = dao.task(taskId) ?: return
        if (state == AssistedTaskState.COMPLETED) return
        dao.updateTaskState(taskId, state.name, clock())
        val name = ProviderRegistry.definition(ProviderId.valueOf(task.providerId)).displayName
        val summary = when (state) {
            AssistedTaskState.SKIPPED -> "You skipped the assisted $name task. No response was invented."
            AssistedTaskState.DEFERRED -> "You deferred the assisted $name task. No response was invented."
            AssistedTaskState.AWAITING_USER_RESPONSE -> "The assisted $name task is waiting for your imported response."
            AssistedTaskState.COMPLETED -> return
            AssistedTaskState.PENDING,
            AssistedTaskState.READY,
            AssistedTaskState.RUNNING,
            AssistedTaskState.BLOCKED,
            AssistedTaskState.CANCELLED,
            AssistedTaskState.FAILED,
            -> "The $name task is ${state.name}. No response was invented."
        }
        dao.insertEvent(
            event(
                conversationId = task.conversationId,
                providerId = ProviderId.valueOf(task.providerId),
                type = state.name,
                summary = summary,
            ),
        )
    }

    suspend fun importResponse(draft: ImportDraft): String {
        val check = ImportRules.check(draft.providerId, draft.body, draft.roundNumber)
        require(check.accepted) { check.reason }
        val now = clock()
        val importId = ids()
        val messageId = ids()
        val name = ProviderRegistry.definition(draft.providerId).displayName
        dao.importAssistedResponse(
            imported = ImportedResponseEntity(
                id = importId,
                conversationId = draft.conversationId,
                taskId = draft.taskId,
                providerId = draft.providerId.name,
                roundNumber = draft.roundNumber,
                body = draft.body.trim(),
                userSupplied = true,
                authenticated = false,
                createdAtEpochMs = now,
                userDeclaredModelId = ModelIdentity.declared(draft.userDeclaredModelId),
                verifiedModelId = ModelIdentity.UNKNOWN,
            ),
            message = MessageEntity(
                id = messageId,
                conversationId = draft.conversationId,
                providerId = draft.providerId.name,
                source = MessageSource.USER_SUPPLIED_PROVIDER_ATTRIBUTED.name,
                body = draft.body.trim(),
                roundNumber = draft.roundNumber,
                createdAtEpochMs = now,
            ),
            taskId = draft.taskId,
            completedState = AssistedTaskState.COMPLETED.name,
            updatedAt = now,
            event = event(
                conversationId = draft.conversationId,
                providerId = draft.providerId,
                type = "RESPONSE_IMPORTED",
                summary = "Imported a user-supplied $name response for round ${draft.roundNumber}. It is not an authenticated provider response.",
            ),
        )
        return importId
    }

    suspend fun deleteAllLocalContent() {
        credentials?.deleteAll()
        dao.deleteConversations()
        dao.deleteEvents()
        dao.deleteProviderConfigs()
        ensureSeeded()
        dao.insertEvent(
            event(
                conversationId = null,
                providerId = null,
                type = "LOCAL_DATA_DELETED",
                summary = "Local conversations and provider preferences were deleted from this device.",
            ),
        )
    }

    private fun event(
        conversationId: String?,
        providerId: ProviderId?,
        type: String,
        summary: String,
    ) = WorkflowEventEntity(
        id = ids(),
        conversationId = conversationId,
        providerId = providerId?.name,
        type = type,
        summary = summary,
        createdAtEpochMs = clock(),
    )
}

private fun ConversationEntity.toModel() = Conversation(
    id = id,
    title = title,
    researchMode = ResearchMode.valueOf(researchMode),
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun MessageEntity.toModel() = ChatMessage(
    id = id,
    conversationId = conversationId,
    providerId = providerId?.let(ProviderId::valueOf),
    source = MessageSource.valueOf(source),
    body = body,
    roundNumber = roundNumber,
    createdAtEpochMs = createdAtEpochMs,
)

private fun ProviderConfigEntity.toModel() = ProviderConfig(
    providerId = ProviderId.valueOf(providerId),
    accountChoice = AccountChoice.valueOf(accountChoice),
    declaredPlanId = declaredPlanId,
    billingEligibility = BillingEligibility.valueOf(billingEligibility),
    participationMode = ParticipationMode.valueOf(participationMode),
    integrationMethod = IntegrationMethod.valueOf(integrationMethod),
    automatedInferenceEnabled = automatedInferenceEnabled,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun ProviderConfig.toEntity() = ProviderConfigEntity(
    providerId = providerId.name,
    accountChoice = accountChoice.name,
    declaredPlanId = declaredPlanId,
    billingEligibility = billingEligibility.name,
    participationMode = participationMode.name,
    integrationMethod = integrationMethod.name,
    automatedInferenceEnabled = automatedInferenceEnabled,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun com.airachabanda.app.core.model.ProviderDefinition.toDefaultConfig(now: Long) = ProviderConfig(
    providerId = id,
    accountChoice = AccountChoice.NOT_CONFIGURED,
    declaredPlanId = null,
    billingEligibility = defaultBilling,
    participationMode = defaultParticipation,
    integrationMethod = defaultIntegration,
    automatedInferenceEnabled = false,
    updatedAtEpochMs = now,
)

private fun ResearchTaskEntity.toModel() = ResearchTask(
    id = id,
    sessionId = sessionId,
    conversationId = conversationId,
    providerId = ProviderId.valueOf(providerId),
    roundNumber = roundNumber,
    state = AssistedTaskState.valueOf(state),
    prompt = prompt,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    role = role,
    kind = kind,
)

private fun WorkflowEventEntity.toModel() = WorkflowEvent(
    id = id,
    conversationId = conversationId,
    providerId = providerId?.let(ProviderId::valueOf),
    type = type,
    summary = summary,
    createdAtEpochMs = createdAtEpochMs,
)

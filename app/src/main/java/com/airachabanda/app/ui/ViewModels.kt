@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.airachabanda.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.airachabanda.app.documents.ConversationAttachment
import com.airachabanda.app.AppContainer
import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.Conversation
import com.airachabanda.app.core.model.ImportDraft
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.gemini.GeminiOwnerSession
import com.airachabanda.app.provider.openrouter.OpenRouterSnapshot
import com.airachabanda.app.provider.openrouter.UrlConnectionTransport
import com.airachabanda.app.provider.openrouter.openRouterSnapshot
import com.airachabanda.app.provider.openrouter.snapshotFromReport
import com.airachabanda.app.security.CredentialAccess
import com.airachabanda.app.security.CredentialAccounts
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResearchTask
import com.airachabanda.app.core.model.WorkflowEvent
import com.airachabanda.app.orchestration.CouncilStage
import com.airachabanda.app.orchestration.CouncilWorkflow
import com.airachabanda.app.orchestration.ImportRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    val providers: StateFlow<List<ProviderConfig>> = repository.observeProviderConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.ensureSeeded() }
    }
}

class HistoryViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(title: String, mode: ResearchMode, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.createConversation(title, mode))
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteConversation(id) }
    }
}

class ActivityViewModel(container: AppContainer) : ViewModel() {
    val events: StateFlow<List<WorkflowEvent>> = container.repository.observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class CouncilViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val workflow = CouncilWorkflow(
        engine = container.orchestration,
        council = container.councilDao,
        discussion = container.discussionDao,
    )
    private val selectedConversationId = MutableStateFlow<String?>(null)
    private val revision = MutableStateFlow(0)
    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages: StateFlow<List<ChatMessage>> = selectedConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val maxRounds: StateFlow<Int> = container.preferences.maxRounds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)
    val tasks: StateFlow<List<ResearchTask>> = selectedConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeTasks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val enabledParticipants: StateFlow<Set<ProviderId>> = container.preferences.enabledParticipants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.airachabanda.app.provider.ProviderAccess.defaultEnabled)
    val attachments: StateFlow<List<ConversationAttachment>> = selectedConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.councilDao.observeAttachments(id) }
        .map { rows -> rows.map { container.attachments.toItem(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val inspectedAttachment: MutableStateFlow<String> = MutableStateFlow("")
    val stage: StateFlow<CouncilStage?> = kotlinx.coroutines.flow.combine(selectedConversationId, revision) { id, _ -> id }
        .flatMapLatest { id ->
            if (id == null) flowOf<CouncilStage?>(null) else kotlinx.coroutines.flow.flow { emit(workflow.inspect(id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun refresh() {
        revision.value = revision.value + 1
    }

    fun select(conversationId: String?) {
        selectedConversationId.value = conversationId
    }

    fun rememberAssisted(conversationId: String, taskId: String, providerId: ProviderId) {
        viewModelScope.launch { container.preferences.rememberAssisted(conversationId, taskId, providerId) }
    }

    fun create(mode: ResearchMode, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.createConversation("New conversation", mode))
        }
    }

    fun setMode(conversationId: String, mode: ResearchMode) {
        viewModelScope.launch { repository.updateResearchMode(conversationId, mode) }
    }

    fun addMessage(conversationId: String, body: String) {
        viewModelScope.launch { repository.addUserMessage(conversationId, body) }
    }

    fun importAttachment(conversationId: String, uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = container.attachments.importUri(conversationId, uri, uri.lastPathSegment.orEmpty())
            withContext(Dispatchers.Main) { onResult(message) }
        }
    }

    fun renameAttachment(id: String, label: String) {
        viewModelScope.launch(Dispatchers.IO) { container.attachments.rename(id, label) }
    }

    fun removeAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) { container.attachments.remove(id) }
    }

    fun cancelAttachment(id: String) {
        container.attachments.cancel(id)
    }

    fun setAttachmentConsent(conversationId: String, consent: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { container.attachments.setPromptConsent(conversationId, consent) }
    }

    fun inspectAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            inspectedAttachment.value = container.attachments.inspect(id)
        }
    }

    fun addContext(conversationId: String, text: String, invalidateOpenWork: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.addContext(conversationId, text, invalidateOpenWork))
            refresh()
        }
    }

    fun requestFollowUp(conversationId: String, instruction: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.requestFollowUp(conversationId, instruction))
            refresh()
        }
    }

    fun requestAnotherReview(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.requestAnotherReview(conversationId))
            refresh()
        }
    }

    fun start(conversationId: String, question: String, mode: ResearchMode, selected: Set<ProviderId>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.start(conversationId, question, mode, maxRounds.value, selected))
            refresh()
        }
    }

    fun prepareTask(
        conversationId: String,
        providerId: ProviderId,
        question: String,
        mode: ResearchMode,
        selected: Set<ProviderId>,
        onPrepared: (String) -> Unit,
    ) {
        viewModelScope.launch {
            onPrepared(workflow.prepareResearchPrompt(conversationId, providerId, question, mode, maxRounds.value, selected))
            refresh()
        }
    }

    fun prepareCritique(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val prompt = workflow.prepareCritique(conversationId)
            onResult("Claude critique uses the discussion engine. Claude is not researching inside this app. $prompt")
            refresh()
        }
    }

    fun recordObjection(conversationId: String, explanation: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.recordObjection(conversationId, explanation))
            refresh()
        }
    }

    fun requestRebuttal(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.requestRebuttal(conversationId))
            refresh()
        }
    }

    fun requestVerification(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.requestVerification(conversationId))
            refresh()
        }
    }

    fun prepareSynthesis(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.prepareSynthesis(conversationId))
            refresh()
        }
    }

    fun pause(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.pause(conversationId))
            refresh()
        }
    }

    fun resume(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.resume(conversationId))
            refresh()
        }
    }

    fun stop(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.stop(conversationId))
            refresh()
        }
    }

    fun searchPublic(conversationId: String, query: String, connector: String, consent: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.searchPublic(conversationId, query, connector, consent))
            refresh()
        }
    }

    fun retrievePublicUrl(conversationId: String, url: String, consent: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.retrievePublicUrl(conversationId, url, consent))
            refresh()
        }
    }

    fun advance(conversationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(workflow.advance(conversationId))
            refresh()
        }
    }

    fun skip(conversationId: String, taskId: String) {
        viewModelScope.launch {
            workflow.skip(conversationId, taskId)
            refresh()
        }
    }

    fun defer(taskId: String) {
        viewModelScope.launch { repository.updateTaskState(taskId, AssistedTaskState.DEFERRED) }
    }
}

class AccountViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    val providers: StateFlow<List<ProviderConfig>> = repository.observeProviderConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(providerId: ProviderId, choice: AccountChoice, planId: String?) {
        viewModelScope.launch { repository.saveAccountChoice(providerId, choice, planId) }
    }
}

class ImportViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val workflow = CouncilWorkflow(
        engine = container.orchestration,
        council = container.councilDao,
        discussion = container.discussionDao,
    )
    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val recoveredState = MutableStateFlow<com.airachabanda.app.persistence.AssistedReturn?>(null)
    private val assistedReady = MutableStateFlow(false)
    val recovered: StateFlow<com.airachabanda.app.persistence.AssistedReturn?> = recoveredState
    val ready: StateFlow<Boolean> = assistedReady

    init {
        viewModelScope.launch {
            recoveredState.value = container.preferences.currentAssisted()
            assistedReady.value = true
        }
    }

    fun import(draft: ImportDraft, onResult: (Boolean, String) -> Unit) {
        val check = ImportRules.check(draft.providerId, draft.body, draft.roundNumber)
        if (!check.accepted) {
            onResult(false, check.reason)
            return
        }
        viewModelScope.launch {
            val taskId = draft.taskId
            if (taskId != null) {
                onResult(true, workflow.importTask(draft.conversationId, taskId, draft.body, draft.userDeclaredModelId))
            } else {
                repository.importResponse(draft)
                onResult(true, check.reason)
            }
        }
    }
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val preferences = container.preferences
    private val repository = container.repository
    private val credentials = container.credentialStore
    val appearance: StateFlow<AppearanceMode> = preferences.appearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceMode.SYSTEM)
    val researchMode: StateFlow<ResearchMode> = preferences.defaultResearchMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResearchMode.STANDARD)
    val maxRounds: StateFlow<Int> = preferences.maxRounds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)
    private val credentialRevision = MutableStateFlow(0)
    private val geminiSession = GeminiOwnerSession(UrlConnectionTransport())
    private var geminiRecord: GeminiOwnerSession.Record? = null
    private var geminiBudget = 1
    private val geminiStatus = MutableStateFlow("No Gemini metadata check has run.")
    val geminiVerification: StateFlow<String> = geminiStatus
    val openRouterStatus: StateFlow<OpenRouterSnapshot> = credentialRevision
        .flatMapLatest {
            kotlinx.coroutines.flow.flow {
                val access = credentials.get(ProviderId.OPENROUTER.name, CredentialAccounts.DEFAULT)
                val stored = access is CredentialAccess.Available
                if (access is CredentialAccess.Available) access.secret.fill(0)
                emit(openRouterSnapshot(stored, container.openRouterStatus.read()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), openRouterSnapshot(false, null))
    val directCredentials: StateFlow<Pair<Boolean, Boolean>> = credentialRevision
        .flatMapLatest {
            kotlinx.coroutines.flow.flow {
                val token = credentials.get(ProviderId.CLOUDFLARE.name, CLOUDFLARE_TOKEN)
                val account = credentials.get(ProviderId.CLOUDFLARE.name, CLOUDFLARE_ACCOUNT)
                val gemini = credentials.get(ProviderId.GEMINI.name, CredentialAccounts.DEFAULT)
                val cloudflareReady = token is CredentialAccess.Available && account is CredentialAccess.Available
                listOf(token, account, gemini).forEach { access ->
                    if (access is CredentialAccess.Available) access.secret.fill(0)
                }
                emit(cloudflareReady to (gemini is CredentialAccess.Available))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to false)
    val enabledParticipants: StateFlow<Set<ProviderId>> = preferences.enabledParticipants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.airachabanda.app.provider.ProviderAccess.defaultEnabled)

    fun setParticipantEnabled(providerId: ProviderId, enabled: Boolean) {
        viewModelScope.launch { preferences.setParticipantEnabled(providerId, enabled) }
    }

    fun setAppearance(mode: AppearanceMode) {
        viewModelScope.launch { preferences.setAppearance(mode) }
    }

    fun setResearchMode(mode: ResearchMode) {
        viewModelScope.launch { preferences.setDefaultResearchMode(mode) }
    }

    fun setMaxRounds(rounds: Int) {
        viewModelScope.launch { preferences.setMaxRounds(rounds) }
    }

    fun saveOpenRouterKey(secret: String, onResult: (String) -> Unit) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) {
            onResult("Enter the key in the OpenRouter field. It is not requested in chat.")
            return
        }
        viewModelScope.launch {
            credentials.put(ProviderId.OPENROUTER.name, CredentialAccounts.DEFAULT, trimmed.toByteArray(Charsets.UTF_8))
            container.openRouterStatus.clear()
            credentialRevision.value = credentialRevision.value + 1
            onResult("The OpenRouter key is stored in Android Keystore. It is not shown again. No metadata request was sent.")
        }
    }

    fun removeOpenRouterKey(onResult: (String) -> Unit) {
        viewModelScope.launch {
            credentials.delete(ProviderId.OPENROUTER.name, CredentialAccounts.DEFAULT)
            container.openRouterStatus.clear()
            credentialRevision.value = credentialRevision.value + 1
            onResult("The OpenRouter key was removed from this device.")
        }
    }

    fun confirmOpenRouterMetadata(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val access = credentials.get(ProviderId.OPENROUTER.name, CredentialAccounts.DEFAULT)
            if (access !is CredentialAccess.Available) {
                onResult("No OpenRouter credential is stored.")
                return@launch
            }
            val secret = access.secret.copyOf()
            access.secret.fill(0)
            try {
                val report = container.consentedMetadataAdapter().verifyMetadata(secret.toString(Charsets.UTF_8))
                val stamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                container.openRouterStatus.write(snapshotFromReport(report, stamp))
                credentialRevision.value = credentialRevision.value + 1
                onResult(report.summary)
            } finally {
                secret.fill(0)
            }
        }
    }

    fun saveCloudflare(accountId: String, token: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            if (!ACCOUNT_ID.matches(accountId) || token.isBlank()) {
                onResult("Enter a 32-character Cloudflare account ID and an API token. Nothing was stored.")
                return@launch
            }
            credentials.put(ProviderId.CLOUDFLARE.name, CLOUDFLARE_ACCOUNT, accountId.toByteArray(Charsets.UTF_8))
            credentials.put(ProviderId.CLOUDFLARE.name, CLOUDFLARE_TOKEN, token.toByteArray(Charsets.UTF_8))
            credentialRevision.value = credentialRevision.value + 1
            onResult("The Cloudflare account ID and token were stored on this device. No Cloudflare request was sent.")
        }
    }

    fun removeCloudflare(onResult: (String) -> Unit) {
        viewModelScope.launch {
            credentials.delete(ProviderId.CLOUDFLARE.name, CLOUDFLARE_ACCOUNT)
            credentials.delete(ProviderId.CLOUDFLARE.name, CLOUDFLARE_TOKEN)
            credentialRevision.value = credentialRevision.value + 1
            onResult("The Cloudflare credentials were removed from this device.")
        }
    }

    fun saveGemini(apiKey: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            if (apiKey.isBlank()) {
                onResult("Enter a Gemini API key. Nothing was stored.")
                return@launch
            }
            credentials.put(ProviderId.GEMINI.name, CredentialAccounts.DEFAULT, apiKey.toByteArray(Charsets.UTF_8))
            geminiRecord = null
            geminiStatus.value = "The API key changed. Billing eligibility was cleared. No Gemini request was sent."
            credentialRevision.value = credentialRevision.value + 1
            onResult("The Gemini API key was stored on this device. No Gemini request was sent.")
        }
    }

    fun removeGemini(onResult: (String) -> Unit) {
        viewModelScope.launch {
            credentials.delete(ProviderId.GEMINI.name, CredentialAccounts.DEFAULT)
            geminiRecord = null
            geminiStatus.value = "The API key was removed. Billing eligibility was cleared."
            credentialRevision.value = credentialRevision.value + 1
            onResult("The Gemini API key was removed from this device.")
        }
    }

    fun verifyGemini(accessToken: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val apiKey = readGeminiKey()
            if (apiKey == null) {
                onResult("No Gemini API key is stored. No lookup was sent.")
                return@launch
            }
            val report = geminiSession.verifyMetadata(apiKey, accessToken, metadataConsent = true)
            geminiRecord = report.record
            geminiStatus.value = report.summary
            onResult(report.summary)
        }
    }

    fun inferGemini(accessToken: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val apiKey = readGeminiKey()
            if (apiKey == null) {
                onResult("No Gemini API key is stored. No generation request was sent.")
                return@launch
            }
            val report = geminiSession.infer(apiKey, accessToken, geminiRecord, inferenceConsent = true, budgetRemaining = geminiBudget)
            if (report.sent) geminiBudget = report.budgetRemaining
            if (report.summary.contains("changed") || report.summary.contains("enabled") || report.summary.contains("different project")) {
                geminiRecord = null
                geminiStatus.value = report.summary
            }
            onResult(report.summary)
        }
    }

    private suspend fun readGeminiKey(): String? {
        val access = credentials.get(ProviderId.GEMINI.name, CredentialAccounts.DEFAULT)
        if (access !is CredentialAccess.Available) return null
        val key = access.secret.toString(Charsets.UTF_8)
        access.secret.fill(0)
        return key
    }

    fun deleteLocalData(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteAllLocalContent()
            credentials.deleteAll()
            onDone()
        }
    }
}

fun appViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(container)
            HistoryViewModel::class.java -> HistoryViewModel(container)
            ActivityViewModel::class.java -> ActivityViewModel(container)
            CouncilViewModel::class.java -> CouncilViewModel(container)
            AccountViewModel::class.java -> AccountViewModel(container)
            ImportViewModel::class.java -> ImportViewModel(container)
            SettingsViewModel::class.java -> SettingsViewModel(container)
            else -> error("Unknown view model ${modelClass.name}")
        } as T
    }

private const val CLOUDFLARE_TOKEN = "api-token"
private const val CLOUDFLARE_ACCOUNT = "account-id"
private val ACCOUNT_ID = Regex("^[a-fA-F0-9]{32}$")

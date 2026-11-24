package com.airachabanda.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import com.airachabanda.app.provider.gemini.GeminiAuthOutcome
import com.airachabanda.app.provider.gemini.GeminiFreeCatalog
import com.airachabanda.app.provider.gemini.InstallIdentity
import com.airachabanda.app.provider.gemini.PlayServicesGeminiAuthorizer
import kotlinx.coroutines.launch
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.core.model.Conversation
import com.airachabanda.app.core.model.ImportDraft
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderRegistry
import com.airachabanda.app.provider.claude.ClaudeAssistedLinks
import com.airachabanda.app.provider.openrouter.ConnectionState
import com.airachabanda.app.provider.openrouter.OpenRouterSnapshot

@Composable
fun SettingsRoot(
    onOpen: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val entries = listOf(
        "AI providers" to listOf(
            "providers" to "Provider participation",
            "accounts" to "Account declarations",
            "plans" to "Subscription declarations",
            "usage" to "Usage and limits",
            "openrouter" to "OpenRouter",
            "cloudflare" to "Cloudflare Workers AI",
            "gemini" to "Gemini API",
            "zerocost" to "Zero-cost policy",
        ),
        "Appearance" to listOf("appearance" to "Light, dark, and system"),
        "Discussion defaults" to listOf("research" to "Rounds, requests, sources, and deadline"),
        "Research preferences" to listOf("research" to "Public research stays consent-based"),
        "Privacy and security" to listOf("privacy" to "On-device storage and redaction"),
        "Notifications" to listOf("notifications" to "Discussion notification"),
        "Local storage" to listOf("storage" to "Delete local data"),
        "About" to listOf("about" to "Version and limits"),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            Text("Zero-cost mode is on. This version does not offer a subscription, an advertisement, or a purchase.")
        }
        entries.forEach { (group, links) ->
            item { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(links.size) { index ->
                val (route, label) = links[index]
                TextButton(onClick = { onOpen(route) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun SettingsDetail(
    section: String,
    providers: List<ProviderConfig>,
    appearance: AppearanceMode,
    researchMode: ResearchMode,
    maxRounds: Int,
    onAppearance: (AppearanceMode) -> Unit,
    onResearchMode: (ResearchMode) -> Unit,
    onMaxRounds: (Int) -> Unit,
    onOpenAccount: (ProviderId) -> Unit,
    onDelete: () -> Unit,
    openRouterStatus: OpenRouterSnapshot,
    onSaveOpenRouter: (String) -> Unit,
    onRemoveOpenRouter: () -> Unit,
    onConfirmOpenRouterMetadata: () -> Unit,
    cloudflareReady: Boolean,
    geminiReady: Boolean,
    onSaveCloudflare: (String, String) -> Unit,
    onRemoveCloudflare: () -> Unit,
    onSaveGemini: (String) -> Unit,
    onRemoveGemini: () -> Unit,
    geminiVerification: String,
    onVerifyGemini: (String, (String) -> Unit) -> Unit,
    onInferGemini: (String, (String) -> Unit) -> Unit,
    enabledProviders: Set<ProviderId>,
    onToggleParticipant: (ProviderId, Boolean) -> Unit,
    onConfigure: (ProviderId) -> Unit = {},
    storedProviders: Set<ProviderId> = emptySet(),
    contentPadding: PaddingValues,
    limits: com.airachabanda.app.orchestration.DiscussionBudgets = com.airachabanda.app.orchestration.DiscussionBudgets.standard(),
    onSaveLimits: (com.airachabanda.app.orchestration.DiscussionBudgets) -> Unit = {},
    storageNote: String = "",
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (section) {
            "accounts", "plans" -> {
                Text(
                    when (section) {
                        "plans" -> "Subscription Plans"
                        else -> "Manage Accounts"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text("Open a provider to declare an account. A declaration is not a verified entitlement.")
                providers.forEach { config ->
                    val definition = ProviderRegistry.definition(config.providerId)
                    TextButton(onClick = { onOpenAccount(config.providerId) }) {
                        Text("${definition.displayName} · ${config.accountChoice.name.replace('_', ' ').lowercase()}")
                    }
                }
            }
            "providers" -> ProviderAccessSettings(providers, enabledProviders, onToggleParticipant, onConfigure, storedProviders)
            "usage" -> {
                Text("Usage & Limits", style = MaterialTheme.typography.headlineSmall)
                Text("Quotas that have not been observed stay unknown. This screen does not invent remaining credits or reset times.")
                providers.forEach { config ->
                    val definition = ProviderRegistry.definition(config.providerId)
                    Text("${definition.displayName}: billing ${config.billingEligibility.name}. Observed quota: unknown.")
                }
            }
            "zerocost" -> {
                Text("Zero-Cost Mode", style = MaterialTheme.typography.headlineSmall)
                Text("Zero-Cost Mode is on and mandatory in this version. There is no control to enable paid inference, credit purchases, or an API-key fallback.")
            }
            "research" -> ResearchSettings(researchMode, limits, onResearchMode, onSaveLimits)
            "notifications" -> {
                Text("Notifications", style = MaterialTheme.typography.headlineSmall)
                Text("An active discussion can show a private notification with Pause, Resume, Stop, and Open. The lock screen uses a short public line and does not show the question. Android can still end the process after a force stop or reboot. The discussion does not start again by itself.")
            }
            "appearance" -> {
                Text("Appearance", style = MaterialTheme.typography.headlineSmall)
                AppearanceMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = appearance == mode, onClick = { onAppearance(mode) })
                        Text(
                            when (mode) {
                                AppearanceMode.SYSTEM -> "System"
                                AppearanceMode.LIGHT -> "Light"
                                AppearanceMode.DARK -> "Dark"
                            },
                        )
                    }
                }
            }
            "privacy" -> {
                Text("Privacy & Security", style = MaterialTheme.typography.headlineSmall)
                Text("Conversations stay on this device. The app does not collect provider passwords or session cookies. Logs redact tokens and API keys. External links use HTTPS. An OpenRouter key, if you save one, stays in Android Keystore and is not shown again.")
                Text(ClaudeAssistedLinks.STATUS_SUMMARY)
            }
            "storage" -> StorageSettings(onDelete, storageNote)
            "openrouter" -> OpenRouterSettings(openRouterStatus, onSaveOpenRouter, onRemoveOpenRouter, onConfirmOpenRouterMetadata)
            "cloudflare" -> DirectProviderSettings(
                title = "Cloudflare Workers AI",
                stored = cloudflareReady,
                credentialLabel = "API token",
                accountLabel = "Account ID",
                status = cloudflareStatus(cloudflareReady),
                consent = "A confirmed check would send GET https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/models/search and GET https://api.cloudflare.com/client/v4/accounts/{account_id}/subscriptions. Model search needs Workers AI Read. The subscriptions read needs Billing Read. That response can show WORKERS_PAID. A rate_plan id of free is a product plan, not Workers Free. Entitlements need Account Read and return feature allocations, not the Workers plan name. This build does not send those requests.",
                onSave = { account, secret -> onSaveCloudflare(account, secret) },
                onRemove = onRemoveCloudflare,
            )
            "gemini" -> GeminiGuidedSetup(
                stored = geminiReady,
                verification = geminiVerification,
                onSave = onSaveGemini,
                onRemove = onRemoveGemini,
                onVerify = onVerifyGemini,
                onInfer = onInferGemini,
            )
            else -> {
                Text("About", style = MaterialTheme.typography.headlineSmall)
                Text("AI Rachabanda")
                Text("Multiple Perspectives. Collective Intelligence.")
                Text("Version 0.15.5. Connect checks the account before automated use. Free chat is assisted. Window width uses compact, medium, and expanded classes. A reported fold keeps controls off a separating hinge. This build was not tested on a physical Galaxy Z Fold 6. Quick mode uses one AI request and is not an independent multi-model review. A missing provider does not stop the other participants. Pause, resume, stop, and finish stay on the discussion. Attachments and generated documents stay on this device. Conversation text is not encrypted. Credential files use the Android Keystore. Public research sends only a query you approve. Gemini stays behind in-app confirmation. On-device LiteRT-LM is prepared and no model file is installed. OCR is not installed: adding it needs a separately approved on-device library and its language models. Microsoft Office visual compatibility has not been tested. Physical foldable hardware has not been tested. Passing tests does not mean the application is completely secure.")
            }
        }
    }
}

@Composable
private fun OpenRouterSettings(
    status: OpenRouterSnapshot,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onConfirmMetadata: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val stored = status.state != ConnectionState.NOT_CONFIGURED
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OpenRouter", style = MaterialTheme.typography.headlineSmall)
        Text("Connection status: ${status.state.name}")
        Text(if (stored) "Credential stored: Yes. The key is not shown." else "Credential stored: No.")
        Text("Authentication status: ${status.authentication}")
        Text("Metadata verification status: ${status.metadata}")
        Text("Zero-cost eligibility: ${status.zeroCost}")
        Text("Last verification: ${status.lastVerification}")
        Text("Known usage limits: ${status.usageLimits}")
        Text("Remaining quota: ${status.remainingQuota}")
        Text("Eligible free models: ${status.models}")
        Text("Supported capabilities: Text chat is prepared. Tools, plugins, images, and provider fallbacks are not enabled.")
        Text("Unresolved billing restrictions: ${status.restrictions}")
        Text("Integration status: Metadata can be checked after confirmation. POST /api/v1/chat/completions is not enabled.")
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            onClick = {
                onSave(key)
                key = ""
            },
            enabled = key.isNotBlank(),
        ) { Text("Save credential") }
        Button(onClick = onRemove, enabled = stored) { Text("Remove credential") }
        Button(onClick = { confirm = true }, enabled = stored) { Text("Verify connection") }
        if (!stored) {
            Text("Verify connection is available after a key is saved in this field. It does not run when the key is saved.")
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Check OpenRouter metadata?") },
                text = {
                    Text(
                        "This contacts OpenRouter only. It calls GET https://openrouter.ai/api/v1/key and GET https://openrouter.ai/api/v1/models. The stored API key authenticates those requests and is not shown. No conversation or research prompt is transmitted. No inference is performed. Chat completions stay off.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        onConfirmMetadata()
                    }) { Text("Send metadata check") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ResearchSettings(
    researchMode: ResearchMode,
    limits: com.airachabanda.app.orchestration.DiscussionBudgets,
    onResearchMode: (ResearchMode) -> Unit,
    onSaveLimits: (com.airachabanda.app.orchestration.DiscussionBudgets) -> Unit,
) {
    var draft by remember(limits) { mutableStateOf(limits) }
    val active = com.airachabanda.app.orchestration.DiscussionBudgets.resolve(researchMode, draft)
    Text("Discussion defaults", style = MaterialTheme.typography.headlineSmall)
    Text("The selected mode is the default for a new conversation. Quick, Standard, and Deep Research use fixed limits. Custom uses the values you save, and those values are checked before a task or a source is opened. Saving does not contact a provider and does not raise a budget. Zero-cost rules stay on in every mode.")
    Text(ModeGuide.summary(researchMode), style = MaterialTheme.typography.bodySmall)
    Text(
        "This mode uses ${active.rounds} rounds, ${active.aiRequests} AI requests, ${active.researchRequests} research requests, ${active.researchSources} sources, concurrency ${active.concurrency}, delegation depth ${active.delegationDepth}, and a ${active.deadlineMinutes} minute deadline.",
        style = MaterialTheme.typography.bodySmall,
    )
    ResearchMode.entries.forEach { mode ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = researchMode == mode, onClick = { onResearchMode(mode) })
            Text(
                when (mode) {
                    ResearchMode.QUICK -> "Quick"
                    ResearchMode.STANDARD -> "Standard"
                    ResearchMode.DEEP_RESEARCH -> "Deep Research"
                    ResearchMode.CUSTOM -> "Custom"
                },
            )
        }
    }
    if (researchMode == ResearchMode.CUSTOM) {
        LimitRow("Maximum discussion rounds", draft.rounds, 1, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_ROUNDS) { draft = draft.copy(rounds = it) }
        LimitRow("Maximum AI requests", draft.aiRequests, 0, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_AI_REQUESTS) { draft = draft.copy(aiRequests = it) }
        LimitRow("Maximum research requests", draft.researchRequests, 0, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_RESEARCH_REQUESTS) { draft = draft.copy(researchRequests = it) }
        LimitRow("Maximum research sources", draft.researchSources, 1, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_SOURCES) { draft = draft.copy(researchSources = it) }
        LimitRow("Concurrent task limit", draft.concurrency, 1, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_CONCURRENCY) { draft = draft.copy(concurrency = it) }
        LimitRow("Delegation depth", draft.delegationDepth, 1, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_DEPTH) { draft = draft.copy(delegationDepth = it) }
        LimitRow("Discussion deadline, minutes", draft.deadlineMinutes, 1, com.airachabanda.app.orchestration.DiscussionBudgets.MAX_DEADLINE_MINUTES) { draft = draft.copy(deadlineMinutes = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.verificationRequired, onCheckedChange = { draft = draft.copy(verificationRequired = it) })
            Text("Require verification")
        }
        Button(onClick = { onSaveLimits(draft.clamped()) }) { Text("Save custom limits") }
    }
}

@Composable
private fun LimitRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Text("$label: $value")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("Fewer") }
        TextButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("More") }
    }
}

@Composable
private fun StorageSettings(onDelete: () -> Unit, storageNote: String) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Text("Storage", style = MaterialTheme.typography.headlineSmall)
    Text(storageNote.ifBlank { "Local file sizes have not been measured yet." })
    Text("Delete conversations, imported responses, workflow events, and declared account preferences stored on this device. Deleting one conversation also removes its imported files and generated files. It does not delete other conversations.")
    Button(onClick = { confirm = true }) { Text("Delete local data") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete local data?") },
            text = { Text("This removes conversations and account declarations from this device. It does not change any provider account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ImportScreen(
    conversations: List<Conversation>,
    initialBody: String,
    initialProvider: ProviderId,
    initialConversationId: String?,
    taskId: String?,
    recovered: com.airachabanda.app.persistence.AssistedReturn?,
    onImport: (ImportDraft, (Boolean, String) -> Unit) -> Unit,
    contentPadding: PaddingValues,
) {
    var conversationId by rememberSaveable {
        mutableStateOf(initialConversationId?.takeIf { it.isNotBlank() } ?: recovered?.conversationId ?: conversations.firstOrNull()?.id.orEmpty())
    }
    var provider by rememberSaveable {
        mutableStateOf(if (initialConversationId.isNullOrBlank() && recovered != null) recovered.providerId else initialProvider)
    }
    var body by rememberSaveable { mutableStateOf(initialBody) }
    var round by rememberSaveable { mutableStateOf("1") }
    var status by rememberSaveable { mutableStateOf("Imported text is marked user-supplied. It is not an authenticated response.") }
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Import response", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        if (conversations.isEmpty()) {
            Text("Create a conversation in History before importing a response.")
            return
        }
        Text("Conversation")
        conversations.forEach { conversation ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = conversationId == conversation.id,
                    onClick = { conversationId = conversation.id },
                )
                Text(conversation.title)
            }
        }
        Text("This import is user-supplied. It does not mean the provider responded inside this app.")
        if (taskId != null) Text("Associated task: $taskId")
        Text("Provider")
        ProviderRegistry.assistedImportProviders.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == id, onClick = { provider = id })
                Text(ProviderRegistry.definition(id).displayName)
            }
        }
        OutlinedTextField(
            value = round,
            onValueChange = { round = it.filter { ch -> ch.isDigit() }.take(2) },
            label = { Text("Discussion round") },
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pasted response") },
            minLines = 4,
        )
        Button(onClick = {
            onImport(
                ImportDraft(
                    conversationId = conversationId,
                    providerId = provider,
                    roundNumber = round.toIntOrNull() ?: 0,
                    body = body,
                    taskId = taskId,
                ),
            ) { ok, message ->
                status = message
                if (ok) body = ""
            }
        }) { Text("Add to AI Council") }
    }
}

private data class ProviderStatusLines(
    val credential: String,
    val authentication: String,
    val billing: String,
    val models: String,
    val capabilities: String,
    val usage: String,
    val verified: String,
    val blockers: String,
)

private fun cloudflareStatus(stored: Boolean) = ProviderStatusLines(
    credential = if (stored) "Account ID and API token are stored in Android Keystore. Neither is shown again." else "No Cloudflare credential is stored.",
    authentication = "Not checked. Saving a token does not call Cloudflare.",
    billing = "Unknown. Workers Free cannot be inferred from a Workers AI token.",
    models = "None discovered. Live model search is not authorized.",
    capabilities = "Text generation is the only prepared capability. Streaming is prepared and not enabled.",
    usage = "Documented Workers Free allocation is 10,000 Neurons per day. Remaining Neurons are unknown.",
    verified = "Never",
    blockers = "Plan verification needs Billing Read on the subscriptions endpoint. Paid-required models and AI Gateway prepaid credits are rejected. Inference stays off.",
)

private fun geminiStatus(stored: Boolean) = ProviderStatusLines(
    credential = if (stored) "An API key is stored in Android Keystore and is not shown again." else "No Gemini API key is stored.",
    authentication = "Not checked. Saving a key does not call Gemini.",
    billing = "Unknown. A successful key check is not proof that billing is unlinked.",
    models = "None discovered. Live model listing is not authorized.",
    capabilities = "Text generation is prepared. Grounding, search, caching, and tools stay off.",
    usage = "Free-tier limits are not copied from an old table. Remaining quota is unknown.",
    verified = "Never",
    blockers = "Lookup needs apikeys.keys.lookup. Billing needs resourcemanager.projects.get. The Android OAuth client must match this package and certificate. Free-tier content may be used to improve Google products.",
)

@Composable
private fun GeminiGuidedSetup(
    stored: Boolean,
    verification: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onVerify: (String, (String) -> Unit) -> Unit,
    onInfer: (String, (String) -> Unit) -> Unit,
) {
    var secret by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf("No Google request has been sent.") }
    var showMetadata by rememberSaveable { mutableStateOf(false) }
    var showInference by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val authorizer = remember(activity) { activity?.let(::PlayServicesGeminiAuthorizer) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        authorizer?.onAuthorizationResult(result)
    }
    val scope = rememberCoroutineScope()
    val billingReady = verification.contains("billingEnabled is false")
    fun begin(kind: String) {
        val current = authorizer
        if (current == null) {
            notice = "This screen cannot start Google authorization. No request was sent."
            return
        }
        busy = true
        scope.launch {
            val outcome = current.authorize { launcher.launch(it) }
            when (outcome) {
                GeminiAuthOutcome.Cancelled -> {
                    notice = "Authorization was cancelled. No lookup or generation request was sent."
                    busy = false
                }
                is GeminiAuthOutcome.Rejected -> {
                    notice = outcome.detail
                    busy = false
                }
                is GeminiAuthOutcome.Granted -> {
                    val deliver: (String) -> Unit = { message ->
                        notice = message
                        busy = false
                    }
                    if (kind == "metadata") onVerify(outcome.accessToken, deliver) else onInfer(outcome.accessToken, deliver)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gemini API", style = MaterialTheme.typography.headlineSmall)
        Text("The Gemini API key cannot prove which project it belongs to. A separate read-only Google sign-in is required because the key itself cannot call lookup or Cloud Billing. The only scope is cloud-platform.read-only. The account needs apikeys.keys.lookup and resourcemanager.projects.get. A typed project ID is not accepted.")
        Text("Sign-in uses Google Play services AuthorizationClient for this Android package. There is no client secret in the app. ${InstallIdentity.describe(context)}")
        Text("Lookup is GET https://apikeys.googleapis.com/v2/keys:lookupKey. Billing is GET https://cloudbilling.googleapis.com/v1/projects/{project}/billingInfo for that parent. If the billing project id differs from the lookup parent, the app also reads GET https://cloudresourcemanager.googleapis.com/v1/projects/{project}. billingEnabled must be false.")
        Text("Documented free-tier text models: ${GeminiFreeCatalog.TEXT_MODELS.joinToString(", ")}. ${GeminiFreeCatalog.PROTECTION} ${GeminiFreeCatalog.BILLING_LIMIT}")
        Text("Account setup is paused until you save a key in this screen and approve the Google authorization screen yourself. Do not paste the key, a password, or a one-time code into Cursor chat. No metadata or inference request is sent from this screen until you confirm it here.")
        Text(if (stored) "An API key is stored in Android Keystore and is not shown again." else "No Gemini API key is stored. Enter it here. Do not paste it into a chat.")
        Text(verification)
        Text(notice)
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(secret)
                secret = ""
                notice = "The key field was cleared. No Gemini request was sent."
            }, enabled = secret.isNotBlank() && !busy) { Text("Save") }
            TextButton(onClick = onRemove, enabled = stored && !busy) { Text("Remove credentials") }
        }
        Button(onClick = { showMetadata = true }, enabled = stored && !busy) { Text("Check project billing") }
        Button(onClick = { showInference = true }, enabled = stored && billingReady && !busy) { Text("Send one free-tier question") }
    }
    if (showMetadata) {
        AlertDialog(
            onDismissRequest = { showMetadata = false },
            title = { Text("Read Gemini project billing?") },
            text = { Text("Google's account screen will ask for read-only access. After you allow it, the app calls key lookup and billing for the project that owns the stored key. It does not send the test prompt. Cancel leaves the network unused.") },
            confirmButton = {
                TextButton(onClick = {
                    showMetadata = false
                    begin("metadata")
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showMetadata = false }) { Text("Cancel") } },
        )
    }
    if (showInference) {
        AlertDialog(
            onDismissRequest = { showInference = false },
            title = { Text("Send one free-tier question?") },
            text = { Text("This sends one request: what is 17 multiplied by 23, at most 128 output tokens, no tools, no grounding, no search, no cache, and no retry. Billing is read again first. If billing is enabled or unknown, the question is not sent.") },
            confirmButton = {
                TextButton(onClick = {
                    showInference = false
                    begin("inference")
                }) { Text("Send once") }
            },
            dismissButton = { TextButton(onClick = { showInference = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DirectProviderSettings(
    title: String,
    stored: Boolean,
    credentialLabel: String,
    accountLabel: String?,
    status: ProviderStatusLines,
    consent: String,
    onSave: (String, String) -> Unit,
    onRemove: () -> Unit,
) {
    var account by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(if (stored) "A credential is stored. The value is not shown." else "No credential is stored. This provider is not connected.")
    TextButton(onClick = { details = !details }) {
        Text(if (details) "Hide credential and billing details" else "Credential and billing details")
    }
    if (!details) return
    Text("Credential status: ${status.credential}")
    Text("Authentication status: ${status.authentication}")
    Text("Billing eligibility: ${status.billing}")
    Text("Eligible models: ${status.models}")
    Text("Capabilities: ${status.capabilities}")
    Text("Usage limits: ${status.usage}")
    Text("Last verification: ${status.verified}")
    Text("Integration blockers: ${status.blockers}")
    if (accountLabel != null) {
        OutlinedTextField(
            value = account,
            onValueChange = { account = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(accountLabel) },
            singleLine = true,
        )
    }
    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(credentialLabel) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            onSave(account, secret)
            account = ""
            secret = ""
        }, enabled = secret.isNotBlank() && (accountLabel == null || account.isNotBlank())) { Text("Save") }
        TextButton(onClick = onRemove, enabled = stored) { Text("Remove credentials") }
    }
    Button(onClick = { confirm = true }, enabled = stored) { Text("Verify connection") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Metadata stays off") },
            text = { Text(consent) },
            confirmButton = {
                TextButton(onClick = {}, enabled = false) { Text("Send metadata check") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Close") } },
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun ProviderAccessSettings(
    providers: List<ProviderConfig>,
    enabled: Set<ProviderId>,
    onToggle: (ProviderId, Boolean) -> Unit,
    onConfigure: (ProviderId) -> Unit,
    storedProviders: Set<ProviderId> = emptySet(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    Text("AI Providers", style = MaterialTheme.typography.headlineSmall)
    Text("Choose a provider, enter a key or use its official sign-in, then tap Connect. Ready appears only after free access is verified. Stored keys are not shown.")
    Text("Zero-cost mode is on. Paid inference is not offered.")
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Search providers") },
        singleLine = true,
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All", "Configured", "Not configured", "Enabled", "Disabled").forEach { name ->
            androidx.compose.material3.FilterChip(
                selected = filter == name,
                onClick = { filter = name },
                label = { Text(name) },
            )
        }
    }
    ProviderId.entries.filter { id ->
        ProviderRegistry.definition(id).displayName.contains(query.trim(), ignoreCase = true)
    }.forEach { id ->
        val profile = ProviderAccess.profile(id)
        val config = providers.firstOrNull { it.providerId == id }
        val canToggle = ProviderAccess.active(id) == com.airachabanda.app.core.model.ParticipationMode.ASSISTED
        val status = if (config == null) {
            ConnectionPresentation.NOT_CONNECTED
        } else {
            ConnectionPresentation.state(
                config,
                enabled = !canToggle || id in enabled,
                credentialStored = id in storedProviders,
            )
        }
        val configured = config != null && config.accountChoice != com.airachabanda.app.core.model.AccountChoice.NOT_CONFIGURED
        val shown = when (filter) {
            "Configured" -> configured
            "Not configured" -> !configured
            "Enabled" -> canToggle && id in enabled
            "Disabled" -> canToggle && id !in enabled
            else -> true
        }
        if (!shown) return@forEach
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(ProviderRegistry.definition(id).displayName, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.labelLarge)
                Text(ConnectionPresentation.explanation(status, id), style = MaterialTheme.typography.bodySmall)
                config?.let { item ->
                    val kind = ProviderPresentation.kind(item)
                    if (kind != status) Text(kind, style = MaterialTheme.typography.bodySmall)
                }
                Text(profile.note, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onConfigure(id) }) { Text("Set up") }
            }
            if (canToggle) {
                Switch(
                    checked = id in enabled,
                    onCheckedChange = { onToggle(id, it) },
                )
            }
        }
    }
}


package com.airachabanda.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.airachabanda.app.documents.ConversationAttachment
import com.airachabanda.app.documents.DocumentGenerationLimits
import com.airachabanda.app.documents.DocumentLimits
import com.airachabanda.app.documents.DocumentTemplates
import com.airachabanda.app.documents.GeneratedDocumentItem
import com.airachabanda.app.documents.OutputFormat
import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.orchestration.CouncilStage
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.Conversation
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResearchTask
import com.airachabanda.app.core.model.WorkflowEvent
import com.airachabanda.app.provider.PlanCatalog
import com.airachabanda.app.provider.AccessLabel
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderRegistry

@Composable
fun HomeScreen(
    providers: List<ProviderConfig>,
    conversations: List<Conversation>,
    defaultMode: ResearchMode,
    enabledProviders: Set<ProviderId>,
    onStart: (String, ResearchMode) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenCouncil: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenAccount: (ProviderId) -> Unit,
    contentPadding: PaddingValues,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable(defaultMode.name) { mutableStateOf(defaultMode) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "AI Rachabanda",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { contentDescription = "AI Rachabanda" },
            )
            Text(
                "Multiple Perspectives. Collective Intelligence.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text("New discussion", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Question") },
                minLines = 3,
            )
            Text(ModeGuide.summary(mode), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (mode == ResearchMode.CUSTOM) {
                Text(ModeGuide.customDetail(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResearchMode.entries.forEach { entry ->
                    FilterChip(
                        selected = mode == entry,
                        onClick = { mode = entry },
                        label = { Text(entry.label()) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = {
                        onStart(draft, mode)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Start discussion") }
                OutlinedButton(onClick = onOpenCouncil) { Text("Open Council") }
            }
            Text(
                "Start discussion creates a conversation and keeps the question on this device. Attachments are added inside the Council.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Text("Quick actions", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(selected = mode == ResearchMode.DEEP_RESEARCH, onClick = { mode = ResearchMode.DEEP_RESEARCH }, label = { Text("Research") })
                FilterChip(selected = false, onClick = onOpenCouncil, label = { Text("Document analysis") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(selected = false, onClick = onOpenCouncil, label = { Text("Document creation") })
                FilterChip(selected = mode == ResearchMode.STANDARD, onClick = { mode = ResearchMode.STANDARD }, label = { Text("AI discussion") })
            }
        }
        item {
            Text("Recent conversations", style = MaterialTheme.typography.titleMedium)
            if (conversations.isEmpty()) {
                EmptyPanel("No conversation is stored on this device yet.")
            }
        }
        items(conversations.take(4), key = { it.id }) { conversation ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleSmall)
                    Text(conversation.researchMode.label(), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpenConversation(conversation.id) }) { Text("Continue") }
                }
            }
        }
        item {
            Text("Providers", style = MaterialTheme.typography.titleMedium)
            Text(providerSummary(providers, enabledProviders), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Zero-cost mode is on. An unconfigured provider is not connected.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpenSettings) { Text("Provider settings") }
            TextButton(onClick = { showProviders = !showProviders }) {
                Text(if (showProviders) "Hide provider actions" else "Provider actions")
            }
        }
        if (showProviders) {
            items(providers, key = { "provider-${it.providerId.name}" }) { config ->
                ProviderCard(config, onOpenUrl, onOpenCouncil, onOpenAccount)
            }
        }
    }
}

private fun providerSummary(providers: List<ProviderConfig>, enabled: Set<ProviderId>): String {
    if (providers.isEmpty()) return "Provider status is still loading."
    return providers.groupBy { providerStatus(it, enabled) }.entries.joinToString("\n") { (status, rows) ->
        val names = rows.joinToString(", ") { ProviderRegistry.definition(it.providerId).displayName }
        "$status: $names."
    }
}

private fun providerStatus(config: ProviderConfig, enabled: Set<ProviderId>): String {
    val assisted = ProviderAccess.active(config.providerId) == ParticipationMode.ASSISTED
    return ProviderPresentation.status(config, enabled = !assisted || config.providerId in enabled)
}

@Composable
private fun ProviderCard(
    config: ProviderConfig,
    onOpenUrl: (String) -> Unit,
    onOpenCouncil: () -> Unit,
    onOpenAccount: (ProviderId) -> Unit,
) {
    val definition = ProviderRegistry.definition(config.providerId)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(definition.displayName, style = MaterialTheme.typography.titleMedium)
            Text(ProviderPresentation.status(config), style = MaterialTheme.typography.labelLarge)
            Text(ProviderPresentation.kind(config), style = MaterialTheme.typography.bodySmall)
            Text("Account declaration: ${config.accountChoice.label()}")
            Text(accessLine(config.providerId))
            Text(ProviderAccess.profile(config.providerId).note, style = MaterialTheme.typography.bodySmall)
            val chat = ProviderAccess.profile(config.providerId).chatUrl
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat != null) {
                    Button(onClick = { onOpenUrl(chat) }) {
                        Text("Open ${definition.displayName}")
                    }
                    OutlinedButton(onClick = onOpenCouncil) { Text("AI Council") }
                } else {
                    OutlinedButton(onClick = { onOpenAccount(config.providerId) }) { Text("Account") }
                }
            }
        }
    }
}

@Composable
fun CouncilScreen(
    conversation: Conversation?,
    messages: List<ChatMessage>,
    tasks: List<ResearchTask>,
    expanded: Boolean,
    onNewChat: (ResearchMode) -> Unit,
    onModeChange: (ResearchMode) -> Unit,
    onAddMessage: (String) -> Unit,
    stage: CouncilStage?,
    onStart: (String, Set<ProviderId>) -> Unit,
    onAdvance: () -> Unit,
    onPrepare: (ProviderId, String, ResearchMode, Set<ProviderId>) -> Unit,
    onPrepareCritique: () -> Unit,
    onRecordObjection: (String) -> Unit,
    onRequestRebuttal: () -> Unit,
    onRequestVerification: () -> Unit,
    onPrepareSynthesis: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onFinish: () -> Unit,
    onSkipParticipant: (String) -> Unit,
    notificationsAllowed: Boolean,
    onAddContext: (String, Boolean) -> Unit,
    onFollowUp: (String) -> Unit,
    onAnotherReview: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyAndOpen: (ResearchTask) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSkip: (String) -> Unit,
    onDefer: (String) -> Unit,
    onImport: (ProviderId, String?) -> Unit,
    onSearch: (String, String, Boolean) -> Unit,
    onRetrieveUrl: (String, Boolean) -> Unit,
    attachments: List<ConversationAttachment>,
    inspectedAttachment: String,
    onAttach: () -> Unit,
    onRenameAttachment: (String, String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelAttachment: (String) -> Unit,
    onInspectAttachment: (String) -> Unit,
    onAttachmentConsent: (Boolean) -> Unit,
    documents: List<GeneratedDocumentItem>,
    documentPreview: String,
    onGenerateDocument: (String, String, String, String, Boolean) -> Unit,
    onCancelDocument: (String) -> Unit,
    onInspectDocument: (String) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onShareDocument: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onSaveDocument: (String) -> Unit,
    onRegenerateDocument: (String) -> Unit,
    enabledProviders: Set<ProviderId>,
    contentPadding: PaddingValues,
) {
    var mode by rememberSaveable(conversation?.id) {
        mutableStateOf(conversation?.researchMode ?: ResearchMode.STANDARD)
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var objection by rememberSaveable { mutableStateOf("") }
    var researchQuery by rememberSaveable { mutableStateOf("") }
    var publicUrl by rememberSaveable { mutableStateOf("") }
    var researchConsent by rememberSaveable { mutableStateOf(false) }
    var connector by rememberSaveable { mutableStateOf("WIKIMEDIA") }
    var renaming by rememberSaveable { mutableStateOf("") }
    var renameLabel by rememberSaveable { mutableStateOf("") }
    var selectedRaw by rememberSaveable(enabledProviders.sortedBy { it.name }.joinToString(",")) {
        mutableStateOf(enabledProviders.joinToString(","))
    }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var technical by rememberSaveable { mutableStateOf(false) }
    val requestStop = { confirmStop = true }
    val selectedProviders = selectedRaw.split(',').mapNotNull { name ->
        runCatching { ProviderId.valueOf(name) }.getOrNull()
    }.toSet()
    Column(Modifier.fillMaxSize().imePadding().padding(contentPadding).padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onNewChat(mode) }) { Text("New Chat") }
            Text("Research mode", style = MaterialTheme.typography.labelLarge)
        }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            ResearchMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = {
                        mode = entry
                        conversation?.let { onModeChange(entry) }
                    },
                    label = { Text(entry.label()) },
                )
            }
        }
        Text(ModeGuide.summary(mode), style = MaterialTheme.typography.bodySmall)
        if (mode == ResearchMode.CUSTOM) {
            Text(ModeGuide.customDetail(), style = MaterialTheme.typography.bodySmall)
        }
        if (conversation == null) {
            EmptyPanel(
                "No conversation is open. Create a chat to prepare an assisted prompt. No provider is ready for automated discussion.",
            )
            return
        }
        val discussion = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "A message labeled You is yours. User-supplied, provider-attributed text was pasted by you. A local calculation is arithmetic on this device. A retrieved source has not been verified. A record-based synthesis uses stored findings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (messages.isEmpty()) {
                    EmptyPanel("No messages yet. A message you add stays on this device until you submit it yourself through the provider.")
                } else {
                    messages.forEach { message -> MessageCard(message) }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onAddMessage(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("Add message") }
                    OutlinedButton(onClick = onAttach) { Text("Attach") }
                }
                Text(
                    "Add message stores your text locally. It does not contact a provider. ${DocumentLimits.EXPLANATION}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (attachments.isEmpty()) {
                    Text("No file is attached. Choosing a file copies it into app storage. It is not uploaded.")
                } else {
                    val consent = attachments.any { it.includedInPrompt }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = onAttachmentConsent)
                        Text("Include extracted text in prompts I copy. Files stay on this device and are not uploaded.")
                    }
                    attachments.forEach { item ->
                        Text("${item.format} · ${item.label} · ${item.status} · ${item.sizeBytes} bytes")
                        if (item.limitation.isNotBlank()) Text(item.limitation, style = MaterialTheme.typography.bodySmall)
                        item.previewPath?.let { path ->
                            val bitmap = BitmapFactory.decodeFile(path)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = item.label,
                                    modifier = Modifier.width(160.dp).height(120.dp),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                renaming = item.id
                                renameLabel = item.label
                            }) { Text("Rename") }
                            TextButton(onClick = { onInspectAttachment(item.id) }) { Text("Inspect") }
                            TextButton(onClick = { onRemoveAttachment(item.id) }) { Text("Remove") }
                            if (item.status == "PROCESSING") {
                                TextButton(onClick = { onCancelAttachment(item.id) }) { Text("Cancel") }
                            }
                        }
                    }
                    if (inspectedAttachment.isNotBlank()) Text(inspectedAttachment)
                }
                LocalDocuments(
                    documents = documents,
                    preview = documentPreview,
                    onGenerate = onGenerateDocument,
                    onCancel = onCancelDocument,
                    onInspect = onInspectDocument,
                    onDelete = onDeleteDocument,
                    onShare = onShareDocument,
                    onOpen = onOpenDocument,
                    onSave = onSaveDocument,
                    onRegenerate = onRegenerateDocument,
                )
                if (renaming.isNotBlank()) {
                    AlertDialog(
                        onDismissRequest = { renaming = "" },
                        title = { Text("Display label") },
                        text = {
                            OutlinedTextField(value = renameLabel, onValueChange = { renameLabel = it }, label = { Text("Label") })
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                onRenameAttachment(renaming, renameLabel)
                                renaming = ""
                            }) { Text("Save") }
                        },
                        dismissButton = { TextButton(onClick = { renaming = "" }) { Text("Cancel") } },
                    )
                }
            }
        }
        val answer = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Discussion", style = MaterialTheme.typography.titleMedium)
                Text("Stage: ${stage?.stage ?: "No session"}")
                Text(stage?.statusNote ?: "Start a discussion to assign participants. No provider has been contacted.")
                if (!stage?.continuityNote.isNullOrBlank()) Text(stage?.continuityNote.orEmpty())
                if (stage?.assistedOnly != false) {
                    Text("Only assisted providers are available. You need to submit each prompt yourself and import the response. No provider is researching inside this app.")
                }
                Text("Participants")
                if (stage?.participantControls.isNullOrEmpty()) {
                    Text("No participants are assigned yet.")
                } else {
                    stage?.participantControls.orEmpty().forEach { person ->
                        Text("${person.label}. ${person.availability}.")
                        if (person.availability == "ELIGIBLE") {
                            TextButton(onClick = { onSkipParticipant(person.id) }) { Text("Skip ${person.label} for this discussion") }
                        }
                    }
                    Text("Skipping a participant applies to this discussion. It does not disable that provider in Accounts.")
                }
                Text("Roles")
                if (stage?.roles.isNullOrEmpty()) Text("No roles are assigned yet.") else stage?.roles.orEmpty().forEach { Text(it) }
                Text("Tasks: ${stage?.completedTasks ?: 0} completed, ${stage?.pendingTasks ?: 0} pending.")
                Text(if (stage?.waitingForUser == true) "Waiting for your response." else "Not waiting for a response.")
                Text(stage?.critiqueNote ?: "Critical review has not started.")
                Text(stage?.reviewDisclosure ?: "Reviewer independence has not been assessed.")
                Text("Objections: ${stage?.objectionCount ?: 0}. Unresolved: ${stage?.unresolvedObjections ?: 0}. Rebuttals: ${stage?.rebuttalCount ?: 0}.")
                Text("Verification: ${stage?.verification ?: "Not started"}.")
                Text("Automated requests used ${stage?.requestsUsed ?: 0} of ${stage?.requestBudget ?: 0}. Provider quotas are unknown.")
                TextButton(onClick = { technical = !technical }) {
                    Text(if (technical) "Hide technical details" else "Technical details")
                }
                if (technical) {
                Text("Plan", style = MaterialTheme.typography.titleMedium)
                Text(stage?.planNote?.ifBlank { "No discussion plan has been recorded." } ?: "No discussion plan has been recorded.")
                Text("Stopping reason", style = MaterialTheme.typography.titleMedium)
                Text(stage?.stoppingReason?.ifBlank { "The discussion has not stopped." } ?: "The discussion has not stopped.")
                Text("Task graph", style = MaterialTheme.typography.titleMedium)
                if (stage?.taskGraph.isNullOrEmpty()) {
                    Text("No task has been scheduled.")
                } else {
                    stage?.taskGraph.orEmpty().forEach { Text(it) }
                }
                Text("AI-to-AI requests", style = MaterialTheme.typography.titleMedium)
                if (stage?.exchanges.isNullOrEmpty()) {
                    Text("No participant request has been recorded.")
                } else {
                    stage?.exchanges.orEmpty().forEach { Text(it) }
                }
                }
                Text("Recorded synthesis", style = MaterialTheme.typography.titleMedium)
                Text("Claims", style = MaterialTheme.typography.titleMedium)
                val claims = stage?.claimLines.orEmpty()
                if (claims.isEmpty()) {
                    Text("No material claim has been recorded. A verified calculation is shown separately from an unverified assertion.")
                } else {
                    claims.forEach { line -> Text(line) }
                }
                Text(stage?.reviewDisclosure ?: "Reviewer independence has not been assessed.")
                Text("Research", style = MaterialTheme.typography.titleMedium)
                Text("A search sends only the query, or a URL you enter, to the selected public service. The rest of the conversation stays on this device.")
                val research = stage?.researchLines.orEmpty()
                if (research.isEmpty()) {
                    Text("No public source has been retrieved for this discussion.")
                } else {
                    research.forEach { line ->
                        Text(line)
                        val link = line.substringAfterLast(' ')
                        if (link.startsWith("https://")) {
                            TextButton(onClick = { onOpenUrl(link) }) { Text("Open source") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = researchConsent, onCheckedChange = { researchConsent = it })
                    Text("I consent to send this query or URL.")
                }
                androidx.compose.material3.OutlinedTextField(
                    value = researchQuery,
                    onValueChange = { researchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Public research query") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("WIKIMEDIA" to "Wikimedia", "ARXIV" to "arXiv", "FEDERAL_REGISTER" to "Federal Register").forEach { (id, label) ->
                        FilterChip(selected = connector == id, onClick = { connector = id }, label = { Text(label) })
                    }
                }
                Button(
                    onClick = { onSearch(researchQuery, connector, researchConsent) },
                    enabled = researchConsent && researchQuery.isNotBlank(),
                ) { Text("Search public sources") }
                androidx.compose.material3.OutlinedTextField(
                    value = publicUrl,
                    onValueChange = { publicUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Public HTTPS URL") },
                )
                Button(
                    onClick = { onRetrieveUrl(publicUrl, researchConsent) },
                    enabled = researchConsent && publicUrl.startsWith("https://"),
                ) { Text("Retrieve page") }
                Text(stage?.recordedSynthesis ?: "No discussion record is available.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    enabledProviders.sortedBy { it.name }.forEach { id ->
                        FilterChip(
                            selected = id in selectedProviders,
                            onClick = {
                                val next = if (id in selectedProviders) selectedProviders - id else selectedProviders + id
                                selectedRaw = next.joinToString(",")
                            },
                            label = { Text(ProviderRegistry.definition(id).displayName) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onStart(question, selectedProviders) }, enabled = question.isNotBlank()) { Text("Start discussion") }
                    OutlinedButton(onClick = onAdvance) { Text("Advance") }
                }
                var instruction by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Instruction") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onAddContext(instruction, false)
                            instruction = ""
                        },
                        enabled = instruction.isNotBlank(),
                    ) { Text("Add instruction") }
                    OutlinedButton(onClick = { onAddContext(draft, false) }, enabled = draft.isNotBlank()) { Text("Add context") }
                    OutlinedButton(onClick = { onAddContext(draft, true) }, enabled = draft.isNotBlank()) { Text("Correct assumption") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onFollowUp(question) }, enabled = question.isNotBlank()) { Text("Request follow-up") }
                    OutlinedButton(onClick = onAnotherReview) { Text("Request another review") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPause) { Text("Pause") }
                    OutlinedButton(onClick = onResume) { Text("Resume") }
                    OutlinedButton(onClick = requestStop, modifier = Modifier.semantics { contentDescription = "Stop the discussion" }) { Text("Stop") }
                }
                Text("Final Answer", style = MaterialTheme.typography.titleMedium)
                if (stage?.finalAnswer.isNullOrBlank()) {
                    EmptyPanel("No final answer has been produced.")
                } else {
                    Text(stage?.finalAnswer.orEmpty())
                }
                Text("Assisted tasks", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Question for an assisted provider") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selectedProviders.sortedBy { it.name }.forEach { id ->
                        Button(
                            onClick = { onPrepare(id, question, mode, selectedProviders) },
                            enabled = question.isNotBlank(),
                        ) { Text("Prepare ${ProviderRegistry.definition(id).displayName}") }
                    }
                }
                Button(
                    onClick = onPrepareCritique,
                    enabled = stage?.sessionId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Prepare critique") }
                androidx.compose.material3.OutlinedTextField(
                    value = objection,
                    onValueChange = { objection = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Objection") },
                    minLines = 2,
                )
                Button(
                    onClick = {
                        onRecordObjection(objection)
                        objection = ""
                    },
                    enabled = objection.isNotBlank(),
                ) { Text("Record objection") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestRebuttal) { Text("Request rebuttal") }
                    OutlinedButton(onClick = onRequestVerification) { Text("Request verification") }
                }
                OutlinedButton(onClick = onPrepareSynthesis) { Text("Prepare synthesis") }
                val next = tasks.firstOrNull {
                    it.state == AssistedTaskState.AWAITING_USER_RESPONSE || it.state == AssistedTaskState.DEFERRED
                }
                if (next != null) {
                    Text("Next pending task: ${ProviderRegistry.definition(next.providerId).displayName}, ${next.kind.lowercase()}.")
                }
                if (tasks.isEmpty()) {
                    EmptyPanel("No assisted task is waiting. Preparing a prompt does not mean the provider is researching.")
                } else {
                    tasks.forEach { task ->
                        TaskCard(task, onCopyAndOpen, onSkip, onDefer) { onImport(task.providerId, task.id) }
                    }
                }
            }
        }
        Column(
            Modifier.zIndex(1f).background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp),
        ) {
            DiscussionControls(
                stage = stage,
                notificationsAllowed = notificationsAllowed,
                onPause = onPause,
                onResume = onResume,
                onStop = requestStop,
                onFinish = onFinish,
            )
        }
        if (expanded) {
            Row(
                Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { discussion() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { answer() }
            }
        } else {
            Column(
                Modifier.weight(1f).clipToBounds().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                discussion()
                answer()
            }
        }
        if (confirmStop) {
            AlertDialog(
                onDismissRequest = { confirmStop = false },
                title = { Text("Stop this discussion?") },
                text = {
                    Text("Completed findings and generated files stay on this device. New requests stop. Remote provider processing is not confirmed as stopped.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStop = false
                        onStop()
                    }) { Text("Stop discussion") }
                },
                dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
            )
        }
    }
}

@Composable
private fun DiscussionControls(
    stage: CouncilStage?,
    notificationsAllowed: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onFinish: () -> Unit,
) {
    Text("Discussion controls. Active ${stage?.activeParticipantCount ?: 0}. Completed ${stage?.completedTasks ?: 0}. Pending ${stage?.pendingTasks ?: 0}.")
    if (!notificationsAllowed) {
        Text("Notification permission is off. This discussion continues while the app process is running. Android can still stop it after a force stop or reboot.")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPause, modifier = Modifier.semantics { contentDescription = "Pause the discussion" }) { Text("Pause") }
        OutlinedButton(onClick = onResume, modifier = Modifier.semantics { contentDescription = "Resume the discussion" }) { Text("Resume") }
        Button(onClick = onStop, modifier = Modifier.semantics { contentDescription = "Stop the discussion" }) { Text("Stop") }
        OutlinedButton(onClick = onFinish, modifier = Modifier.semantics { contentDescription = "Finish now with the current findings" }) { Text("Finish now") }
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.source.label(), style = MaterialTheme.typography.labelLarge)
            message.providerId?.let { Text(ProviderRegistry.definition(it).displayName) }
            Text(message.body)
        }
    }
}

@Composable
private fun TaskCard(
    task: ResearchTask,
    onCopyAndOpen: (ResearchTask) -> Unit,
    onSkip: (String) -> Unit,
    onDefer: (String) -> Unit,
    onImport: () -> Unit,
) {
    val name = ProviderRegistry.definition(task.providerId).displayName
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$name · ${task.role.replace('_', ' ').lowercase()} · ${task.kind.lowercase()}", style = MaterialTheme.typography.titleSmall)
            Text("State: ${task.state.label()}")
            Text("This prompt is prepared on this device. ${name} is not researching automatically.")
            Text(task.prompt, style = MaterialTheme.typography.bodySmall)
            if (task.state == AssistedTaskState.AWAITING_USER_RESPONSE || task.state == AssistedTaskState.DEFERRED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCopyAndOpen(task) }) { Text("Copy and open chat") }
                    TextButton(onClick = onImport) { Text("Import response") }
                    TextButton(onClick = { onSkip(task.id) }) { Text("Skip") }
                    TextButton(onClick = { onDefer(task.id) }) { Text("Defer") }
                }
            }
        }
    }
}

@Composable
private fun LocalDocuments(
    documents: List<GeneratedDocumentItem>,
    preview: String,
    onGenerate: (String, String, String, String, Boolean) -> Unit,
    onCancel: (String) -> Unit,
    onInspect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSave: (String) -> Unit,
    onRegenerate: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("Local report") }
    var body by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf("business-report") }
    var format by rememberSaveable { mutableStateOf(OutputFormat.PDF.name) }
    var includeRecords by rememberSaveable { mutableStateOf(false) }
    var templateMenu by rememberSaveable { mutableStateOf(false) }
    var formatMenu by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf("") }
    Text("Local document", style = MaterialTheme.typography.titleMedium)
    Text(DocumentGenerationLimits.EXPLANATION, style = MaterialTheme.typography.bodySmall)
    Text(
        "OCR is not installed, so a picture does not become text. Microsoft Office visual compatibility has not been tested.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Document name") })
    OutlinedTextField(
        value = body,
        onValueChange = { body = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Content") },
        minLines = 3,
    )
    Text(
        "Plain lines become paragraphs. ROW| cells, ITEM| bullets, AMOUNT|name|number, COMPARE|name|left|right, DATE, RATE, MATH, and LINK|label|https:// are recognized. No provider is contacted.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { templateMenu = true }) { Text(DocumentTemplates.label(template)) }
            DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                DocumentTemplates.all.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        template = option.id
                        templateMenu = false
                    })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { formatMenu = true }) { Text(OutputFormat.fromName(format)?.label ?: format) }
            DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                OutputFormat.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        format = option.name
                        formatMenu = false
                    })
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = includeRecords, onCheckedChange = { includeRecords = it })
        Text("Include stored messages, evidence, and objections. No provider is contacted.")
    }
    Button(
        onClick = { onGenerate(title, template, format, body, includeRecords) },
        enabled = title.isNotBlank(),
    ) { Text("Generate") }
    if (documents.isEmpty()) {
        Text("No document has been rendered in this conversation.")
    }
    documents.forEach { item ->
        Text("${item.format} · ${item.filename} · ${item.status} · version ${item.versionNumber} · ${item.sizeBytes} bytes")
        if (item.limitation.isNotBlank()) Text(item.limitation, style = MaterialTheme.typography.bodySmall)
        item.previewPath?.let { path ->
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = item.title, modifier = Modifier.width(180.dp).height(240.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onInspect(item.id) }) { Text("Inspect") }
            TextButton(onClick = { onSave(item.id) }) { Text("Save") }
            TextButton(onClick = { onShare(item.id) }) { Text("Share") }
            TextButton(onClick = { onOpen(item.id) }) { Text("Open") }
            TextButton(onClick = { onRegenerate(item.id) }) { Text("Regenerate") }
            TextButton(onClick = { deleting = item.id }) { Text("Delete") }
            if (item.status == "GENERATING") TextButton(onClick = { onCancel(item.id) }) { Text("Cancel") }
        }
    }
    if (preview.isNotBlank()) Text(preview)
    if (deleting.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { deleting = "" },
            title = { Text("Delete this document?") },
            text = { Text("The generated file is removed from this device. The conversation and its messages stay.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(deleting)
                    deleting = ""
                }) { Text("Delete document") }
            },
            dismissButton = { TextButton(onClick = { deleting = "" }) { Text("Keep") } },
        )
    }
}

@Composable
fun ActivityScreen(
    events: List<WorkflowEvent>,
    contentPadding: PaddingValues,
    onOpenUrl: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var participant by rememberSaveable { mutableStateOf("All") }
    val participants = listOf("All") + events.mapNotNull { it.providerId?.name }.distinct().sorted()
    val shown = events.filter { event ->
        ActivityFilter.matches(event.type, filter) &&
            (participant == "All" || event.providerId?.name == participant)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Text("Recorded on this device. A retrieved source is not a verified source.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityFilter.types.forEach { entry ->
                    FilterChip(selected = filter == entry, onClick = { filter = entry }, label = { Text(entry) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                participants.forEach { entry ->
                    FilterChip(
                        selected = participant == entry,
                        onClick = { participant = entry },
                        label = { Text(if (entry == "All") "All participants" else entry) },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item { EmptyPanel("No recorded activity matches this filter.") }
        }
        items(shown, key = { it.id }) { event ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.type.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                    event.providerId?.let { Text(ProviderRegistry.definition(it).displayName, style = MaterialTheme.typography.bodySmall) }
                    Text(event.summary)
                    val url = ActivityFilter.sourceUrl(event.summary)
                    if (url != null) {
                        TextButton(onClick = { onOpenUrl(url) }) { Text("Open source") }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    discussionStates: Map<String, String>,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = conversations.filter { it.title.contains(query, ignoreCase = true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("History", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Search conversations") },
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("New conversation title") },
            )
            Button(
                onClick = {
                    onCreate(title)
                    title = ""
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Create conversation") }
        }
        if (shown.isEmpty()) {
            item { EmptyPanel(if (conversations.isEmpty()) "No conversations yet." else "No conversation title matches that search.") }
        }
        items(shown, key = { it.id }) { conversation ->
            val status = ProviderPresentation.discussionStatus(discussionStates[conversation.id])
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                    Text("${conversation.researchMode.label()} · $status", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(conversation.id) }) {
                            Text(if (discussionStates[conversation.id] == null) "Open" else "Continue")
                        }
                        TextButton(onClick = {
                            renaming = conversation.id
                            renameText = conversation.title
                        }) { Text("Rename") }
                        TextButton(onClick = { deleting = conversation.id }) { Text("Delete") }
                    }
                    if (renaming == conversation.id) {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Title") },
                        )
                        Button(onClick = {
                            onRename(conversation.id, renameText)
                            renaming = null
                        }) { Text("Save title") }
                    }
                }
            }
        }
    }
    val pendingDelete = deleting
    if (pendingDelete != null) {
        val name = conversations.firstOrNull { it.id == pendingDelete }?.title ?: "This conversation"
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { Text("Messages, evidence, and generated files for this conversation are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pendingDelete)
                    deleting = null
                }) { Text("Delete conversation") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
fun EmptyPanel(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun accessLine(id: ProviderId): String {
    val labels = ProviderAccess.profile(id).labels.joinToString(", ") { label ->
        when (label) {
            AccessLabel.FREE_API -> "Free API available"
            AccessLabel.FREE_CHAT -> "Free chat available"
            AccessLabel.CONFIGURATION_REQUIRED -> "Configuration required"
            AccessLabel.BILLING_VERIFICATION_REQUIRED -> "Billing verification required"
            AccessLabel.LOCAL_INFERENCE -> "Local inference"
            AccessLabel.UNAVAILABLE -> "Unavailable"
        }
    }
    return "Access: $labels"
}

private fun AccountChoice.label(): String = when (this) {
    AccountChoice.NOT_CONFIGURED -> "Not configured"
    AccountChoice.FREE_ACCOUNT -> "Free account declared"
    AccountChoice.PAID_SUBSCRIPTION -> "Paid subscription declared"
}

private fun ParticipationMode.label(): String = when (this) {
    ParticipationMode.AUTOMATED -> "Automated"
    ParticipationMode.ASSISTED -> "Assisted"
    ParticipationMode.UNAVAILABLE -> "Unavailable"
}

private fun ResearchMode.label(): String = when (this) {
    ResearchMode.QUICK -> "Quick"
    ResearchMode.STANDARD -> "Standard"
    ResearchMode.DEEP_RESEARCH -> "Deep Research"
    ResearchMode.CUSTOM -> "Custom"
}

private fun MessageSource.label(): String = when (this) {
    MessageSource.USER -> "You"
    MessageSource.AUTOMATED_PROVIDER -> "Automated provider response"
    MessageSource.USER_SUPPLIED_PROVIDER_ATTRIBUTED -> "User-supplied, provider-attributed"
}

private fun AssistedTaskState.label(): String = when (this) {
    AssistedTaskState.PENDING -> "Pending"
    AssistedTaskState.READY -> "Ready"
    AssistedTaskState.RUNNING -> "Running"
    AssistedTaskState.AWAITING_USER_RESPONSE -> "Waiting for your response"
    AssistedTaskState.COMPLETED -> "Response imported"
    AssistedTaskState.SKIPPED -> "Skipped"
    AssistedTaskState.DEFERRED -> "Deferred"
    AssistedTaskState.BLOCKED -> "Blocked"
    AssistedTaskState.CANCELLED -> "Cancelled"
    AssistedTaskState.FAILED -> "Failed"
    AssistedTaskState.QUEUED -> "Waiting on another task"
}

@Composable
fun AccountEditor(
    config: ProviderConfig,
    onSave: (AccountChoice, String?) -> Unit,
    contentPadding: PaddingValues,
) {
    val definition = ProviderRegistry.definition(config.providerId)
    val plans = PlanCatalog.paidPlans(config.providerId)
    var choice by rememberSaveable(config.providerId.name) { mutableStateOf(config.accountChoice) }
    var planId by rememberSaveable(config.providerId.name) { mutableStateOf(config.declaredPlanId) }
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(definition.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("This choice is a declaration. It does not verify the account or enable a capability.")
        AccountChoice.entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == entry, onClick = { choice = entry })
                Text(entry.label())
            }
        }
        if (choice == AccountChoice.PAID_SUBSCRIPTION) {
            if (plans.isEmpty()) {
                Text("No verified plan catalog is stored for this provider. A plan name will not be invented.")
            } else {
                Text("Declared plan", style = MaterialTheme.typography.titleMedium)
                plans.forEach { plan ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = planId == plan.id, onClick = { planId = plan.id })
                        Text(plan.label)
                    }
                }
            }
        }
        Button(onClick = {
            onSave(choice, if (choice == AccountChoice.PAID_SUBSCRIPTION) planId else null)
        }) { Text("Save declaration") }
    }
}

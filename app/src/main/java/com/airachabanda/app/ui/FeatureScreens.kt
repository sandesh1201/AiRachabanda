package com.airachabanda.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ChatMessage
import com.airachabanda.app.core.model.Conversation
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ProviderKind
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.core.model.ResearchTask
import com.airachabanda.app.core.model.WorkflowEvent
import com.airachabanda.app.orchestration.ZeroCostPolicy
import com.airachabanda.app.provider.PlanCatalog
import com.airachabanda.app.provider.ProviderRegistry

@Composable
fun HomeScreen(
    providers: List<ProviderConfig>,
    onOpenUrl: (String) -> Unit,
    onOpenCouncil: () -> Unit,
    onOpenAccount: (ProviderId) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("AI Rachabanda", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Multiple Perspectives. Collective Intelligence.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Automated discussion is not available until a provider has a verified free allowance or a verified subscription that cannot spend extra credits.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { SectionTitle("Primary providers") }
        items(providers.filter { ProviderRegistry.definition(it.providerId).prominent }, key = { it.providerId.name }) { config ->
            ProviderCard(config, onOpenUrl, onOpenCouncil, onOpenAccount)
        }
        item { SectionTitle("Other providers") }
        items(providers.filter { !ProviderRegistry.definition(it.providerId).prominent }, key = { it.providerId.name }) { config ->
            ProviderCard(config, onOpenUrl, onOpenCouncil, onOpenAccount)
        }
    }
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
            Text(
                if (definition.kind == ProviderKind.CONSUMER_PRODUCT) "Consumer product" else "Inference service",
                style = MaterialTheme.typography.labelLarge,
            )
            Text("Account status: ${config.accountChoice.label()}")
            Text("Integration mode: ${config.participationMode.label()}")
            Text("Availability: ${availability(config)}")
            Text(definition.summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (definition.officialHttpsUrl != null && config.participationMode == ParticipationMode.ASSISTED) {
                    Button(onClick = { onOpenUrl(definition.officialHttpsUrl) }) {
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
    onPrepare: (ProviderId, String, ResearchMode) -> Unit,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSkip: (String) -> Unit,
    onDefer: (String) -> Unit,
    onImport: (ProviderId, String?) -> Unit,
    contentPadding: PaddingValues,
) {
    var mode by rememberSaveable(conversation?.id) {
        mutableStateOf(conversation?.researchMode ?: ResearchMode.STANDARD)
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onNewChat(mode) }) { Text("New Chat") }
            Text("Research mode", style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
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
        if (conversation == null) {
            EmptyPanel(
                "No conversation is open. Create a chat to prepare an assisted prompt. No provider is ready for automated discussion.",
            )
            return
        }
        val discussion = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleLarge)
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
                    OutlinedButton(onClick = {}, enabled = false) { Text("Attach") }
                }
                Text(
                    "Add message stores your text locally. It does not contact a provider. Attachments are not available in this version.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val answer = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Final Answer", style = MaterialTheme.typography.titleMedium)
                EmptyPanel("No final answer has been produced.")
                Text("Assisted tasks", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Question for an assisted provider") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPrepare(ProviderId.CLAUDE, question, mode) },
                        enabled = question.isNotBlank(),
                    ) { Text("Prepare Claude prompt") }
                    Button(
                        onClick = { onPrepare(ProviderId.CHATGPT, question, mode) },
                        enabled = question.isNotBlank(),
                    ) { Text("Prepare ChatGPT prompt") }
                }
                ProviderRegistry.definition(ProviderId.CLAUDE).officialHttpsUrl?.let { url ->
                    OutlinedButton(onClick = { onOpenUrl(url) }) { Text("Open Claude") }
                }
                ProviderRegistry.definition(ProviderId.CHATGPT).officialHttpsUrl?.let { url ->
                    OutlinedButton(onClick = { onOpenUrl(url) }) { Text("Open ChatGPT") }
                }
                if (tasks.isEmpty()) {
                    EmptyPanel("No assisted task is waiting. Preparing a prompt does not mean the provider is researching.")
                } else {
                    tasks.forEach { task ->
                        TaskCard(task, onCopy, onSkip, onDefer) { onImport(task.providerId, task.id) }
                    }
                }
            }
        }
        if (expanded) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { discussion() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { answer() }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { discussion() }
                item { answer() }
            }
        }
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
    onCopy: (String) -> Unit,
    onSkip: (String) -> Unit,
    onDefer: (String) -> Unit,
    onImport: () -> Unit,
) {
    val name = ProviderRegistry.definition(task.providerId).displayName
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$name · Round ${task.roundNumber}", style = MaterialTheme.typography.titleSmall)
            Text("State: ${task.state.label()}")
            Text(task.prompt, style = MaterialTheme.typography.bodySmall)
            if (task.state == AssistedTaskState.AWAITING_USER_RESPONSE || task.state == AssistedTaskState.DEFERRED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCopy(task.prompt) }) { Text("Copy prompt") }
                    TextButton(onClick = onImport) { Text("Import response") }
                    TextButton(onClick = { onSkip(task.id) }) { Text("Skip") }
                    TextButton(onClick = { onDefer(task.id) }) { Text("Defer") }
                }
            }
        }
    }
}

@Composable
fun ActivityScreen(events: List<WorkflowEvent>, contentPadding: PaddingValues) {
    if (events.isEmpty()) {
        EmptyPanel(
            "No workflow events yet. Activity shows actions recorded on this device, such as a saved conversation or an assisted task waiting for you.",
            Modifier.padding(contentPadding),
        )
        return
    }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.id }) { event ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(event.type.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                    Text(event.summary)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("History", style = MaterialTheme.typography.headlineSmall)
            androidx.compose.material3.OutlinedTextField(
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
        if (conversations.isEmpty()) {
            item { EmptyPanel("No conversations yet.") }
        }
        items(conversations, key = { it.id }) { conversation ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                    Text(conversation.researchMode.label(), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(conversation.id) }) { Text("Open") }
                        TextButton(onClick = {
                            renaming = conversation.id
                            renameText = conversation.title
                        }) { Text("Rename") }
                        TextButton(onClick = { onDelete(conversation.id) }) { Text("Delete") }
                    }
                    if (renaming == conversation.id) {
                        androidx.compose.material3.OutlinedTextField(
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

private fun availability(config: ProviderConfig): String {
    val automated = ZeroCostPolicy.automatedInferenceAllowed(config.providerId, config.billingEligibility)
    return when {
        automated && config.participationMode == ParticipationMode.AUTOMATED -> "Automated participation is allowed"
        config.participationMode == ParticipationMode.ASSISTED -> "Assisted participation is available. Automation is off."
        config.billingEligibility == BillingEligibility.UNKNOWN -> "Billing status is unknown, so automation is blocked."
        else -> "Automated participation is unavailable."
    }
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
    AssistedTaskState.AWAITING_USER_RESPONSE -> "Waiting for your response"
    AssistedTaskState.COMPLETED -> "Response imported"
    AssistedTaskState.SKIPPED -> "Skipped"
    AssistedTaskState.DEFERRED -> "Deferred"
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

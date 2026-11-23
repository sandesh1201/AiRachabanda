package com.airachabanda.app.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.core.model.Conversation
import com.airachabanda.app.core.model.ImportDraft
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.provider.ProviderRegistry
import com.airachabanda.app.provider.claude.ClaudeAssistedLinks

@Composable
fun SettingsRoot(
    onOpen: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val entries = listOf(
        "accounts" to "Manage Accounts",
        "plans" to "Subscription Plans",
        "providers" to "AI Providers",
        "usage" to "Usage & Limits",
        "zerocost" to "Zero-Cost Mode",
        "research" to "Research Preferences",
        "notifications" to "Notifications",
        "appearance" to "Appearance",
        "privacy" to "Privacy & Security",
        "storage" to "Storage",
        "about" to "About",
    )
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp)) }
        items(entries.size) { index ->
            val (route, label) = entries[index]
            TextButton(onClick = { onOpen(route) }, modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.fillMaxWidth())
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
    contentPadding: PaddingValues,
) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (section) {
            "accounts", "plans", "providers" -> {
                Text(
                    when (section) {
                        "plans" -> "Subscription Plans"
                        "providers" -> "AI Providers"
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
            "research" -> ResearchSettings(researchMode, maxRounds, onResearchMode, onMaxRounds)
            "notifications" -> {
                Text("Notifications", style = MaterialTheme.typography.headlineSmall)
                Text("Local notifications are not available in this version. Background provider calls are not running.")
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
                Text("Conversations stay on this device. The app does not collect provider passwords or session cookies. Logs redact tokens and API keys. External links use HTTPS. Provider credentials are not stored in this version.")
                Text(ClaudeAssistedLinks.STATUS_SUMMARY)
            }
            "storage" -> StorageSettings(onDelete)
            else -> {
                Text("About", style = MaterialTheme.typography.headlineSmall)
                Text("AI Rachabanda")
                Text("Multiple Perspectives. Collective Intelligence.")
                Text("Version 0.4.1. Council actions use the local discussion engine. This build does not send live AI requests.")
            }
        }
    }
}

@Composable
private fun ResearchSettings(
    researchMode: ResearchMode,
    maxRounds: Int,
    onResearchMode: (ResearchMode) -> Unit,
    onMaxRounds: (Int) -> Unit,
) {
    var rounds by rememberSaveable(maxRounds) { mutableIntStateOf(maxRounds) }
    Text("Research Preferences", style = MaterialTheme.typography.headlineSmall)
    Text("These preferences are stored for a later research engine. Changing them does not start a provider request.")
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
    Text("Maximum rounds: $rounds")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { rounds = (rounds - 1).coerceAtLeast(1) }) { Text("Fewer") }
        TextButton(onClick = { rounds = (rounds + 1).coerceAtMost(8) }) { Text("More") }
        Button(onClick = { onMaxRounds(rounds) }) { Text("Save rounds") }
    }
}

@Composable
private fun StorageSettings(onDelete: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Text("Storage", style = MaterialTheme.typography.headlineSmall)
    Text("Delete conversations, imported responses, workflow events, and declared account preferences stored on this device.")
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
    onImport: (ImportDraft, (Boolean, String) -> Unit) -> Unit,
    contentPadding: PaddingValues,
) {
    var provider by rememberSaveable { mutableStateOf(initialProvider) }
    var conversationId by rememberSaveable {
        mutableStateOf(initialConversationId?.takeIf { it.isNotBlank() } ?: conversations.firstOrNull()?.id.orEmpty())
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

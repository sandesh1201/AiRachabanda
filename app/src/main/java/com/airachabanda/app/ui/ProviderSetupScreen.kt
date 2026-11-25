package com.airachabanda.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.FreeModelSelection
import com.airachabanda.app.provider.ProviderRegistry
import com.airachabanda.app.provider.ProviderSetupCatalog
import com.airachabanda.app.provider.SetupMethod
import com.airachabanda.app.provider.connect.RuntimeEligibility
import com.airachabanda.app.provider.gemini.GeminiAuthOutcome
import com.airachabanda.app.provider.gemini.InstallIdentity
import com.airachabanda.app.provider.gemini.PlayServicesGeminiAuthorizer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderSetupScreen(
    config: ProviderConfig,
    enabled: Boolean,
    credentialStored: Boolean,
    billingVerified: Boolean,
    verificationNote: String,
    onDeclare: (AccountChoice) -> Unit,
    onSaveSecrets: (Map<String, String>) -> Unit,
    onRemoveSecrets: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRunCheck: (Map<String, String>, (String) -> Unit) -> Unit = { _, done -> done("No automated check was sent.") },
    onVerifyGemini: (String, (String) -> Unit) -> Unit = { _, done -> done("Gemini billing was not checked.") },
    eligibility: RuntimeEligibility = RuntimeEligibility(),
    contentPadding: PaddingValues,
) {
    val spec = ProviderSetupCatalog.spec(config.providerId)
    val assisted = SetupMethod.FREE_ASSISTED_CHAT in spec.methods
    val automated = SetupMethod.FREE_AUTOMATED_API in spec.methods
    val local = SetupMethod.LOCAL_INFERENCE in spec.methods
    var methodName by rememberSaveable(config.providerId.name) {
        mutableStateOf(
            when (config.accountChoice) {
                AccountChoice.PAID_SUBSCRIPTION -> SetupMethod.SUBSCRIPTION_ASSISTED.name
                else -> spec.methods.first().name
            },
        )
    }
    val method = runCatching { SetupMethod.valueOf(methodName) }.getOrDefault(spec.methods.first())
    var first by rememberSaveable(config.providerId.name) { mutableStateOf("") }
    var second by rememberSaveable(config.providerId.name) { mutableStateOf("") }
    var connecting by rememberSaveable(config.providerId.name) { mutableStateOf(false) }
    var result by rememberSaveable(config.providerId.name) { mutableStateOf("") }
    var confirmConnect by rememberSaveable(config.providerId.name) { mutableStateOf(false) }
    var advanced by rememberSaveable(config.providerId.name) { mutableStateOf(false) }
    var pendingGemini by rememberSaveable(config.providerId.name) { mutableStateOf(false) }
    var modelChoice by rememberSaveable(config.providerId.name) { mutableStateOf(FreeModelSelection.AUTO) }
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val authorizer = remember(activity) { activity?.let(::PlayServicesGeminiAuthorizer) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { authResult ->
        authorizer?.onAuthorizationResult(authResult)
    }
    LaunchedEffect(credentialStored, pendingGemini) {
        if (!pendingGemini || !credentialStored || spec.id != ProviderId.GEMINI) return@LaunchedEffect
        pendingGemini = false
        val current = authorizer
        if (current == null) {
            result = "Google authorization could not start. No lookup was sent."
            return@LaunchedEffect
        }
        connecting = true
        val outcome = current.authorize { launcher.launch(it) }
        when (outcome) {
            GeminiAuthOutcome.Cancelled -> {
                connecting = false
                result = "Authorization was cancelled. No lookup was sent."
            }
            is GeminiAuthOutcome.Rejected -> {
                connecting = false
                result = outcome.detail
            }
            is GeminiAuthOutcome.Granted -> onVerifyGemini(outcome.accessToken) { message ->
                connecting = false
                result = message
            }
        }
    }
    val connection = ConnectionPresentation.state(
        config,
        enabled = enabled,
        credentialStored = credentialStored,
        connecting = connecting,
    )
    val signIn = spec.links.firstOrNull { it.label.contains("sign-in", ignoreCase = true) }
        ?: spec.links.firstOrNull { it.label.contains("chat", ignoreCase = true) }
    val keyLink = spec.links.firstOrNull {
        it.label.contains("key", ignoreCase = true) || it.label.contains("token", ignoreCase = true)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(ProviderRegistry.definition(spec.id).displayName, style = MaterialTheme.typography.headlineSmall)
        Text(connection, style = MaterialTheme.typography.titleMedium)
        Text(ConnectionPresentation.explanation(connection, spec.id))
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium)
        if (assisted && signIn != null) {
            Button(onClick = { onOpenUrl(signIn.url) }, modifier = Modifier.fillMaxWidth()) { Text("Official sign-in") }
        }
        if (automated) {
            if (keyLink != null) {
                Button(onClick = { onOpenUrl(keyLink.url) }, modifier = Modifier.fillMaxWidth()) { Text("Get API key") }
            }
            spec.fields.forEachIndexed { index, field ->
                if (spec.id == ProviderId.CLOUDFLARE && field.key == "account-id") return@forEachIndexed
                val value = if (index == 0) first else second
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (index == 0) first = it else second = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(field.label) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                TextButton(onClick = {
                    val pasted = paste(context)
                    if (pasted.isNotBlank()) {
                        if (index == 0) first = pasted else second = pasted
                    }
                }) { Text("Paste") }
            }
            val ready = spec.fields.filter { spec.id != ProviderId.CLOUDFLARE || it.key != "account-id" }
                .all { field ->
                    val index = spec.fields.indexOf(field)
                    (if (index == 0) first else second).isNotBlank()
                }
            Button(onClick = { confirmConnect = true }, enabled = ready && !connecting, modifier = Modifier.fillMaxWidth()) {
                Text(if (credentialStored) "Replace and connect" else "Connect")
            }
        } else if (assisted) {
            Button(
                onClick = {
                    connecting = true
                    onDeclare(AccountChoice.FREE_ACCOUNT)
                    onToggle(true)
                    connecting = false
                    result = "Assisted chat is on. Sign in on the official page. No API request was sent."
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (enabled) "Connected for assisted chat" else "Connect") }
        }
        if (local) {
            LocalAiPanel()
        }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced details" else "Advanced details") }
        if (advanced) {
            eligibility.lines().forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            Text(ProviderPresentation.status(config, enabled = enabled, credentialStored = credentialStored), style = MaterialTheme.typography.labelLarge)
            Text(ProviderPresentation.blocker(ProviderPresentation.status(config, enabled = enabled, credentialStored = credentialStored)))
            Text("Access method", style = MaterialTheme.typography.titleSmall)
            spec.methods.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = method == option, onClick = { methodName = option.name })
                    Text(option.label)
                }
            }
            TextButton(onClick = {
                val choice = if (method == SetupMethod.SUBSCRIPTION_ASSISTED) AccountChoice.PAID_SUBSCRIPTION else AccountChoice.FREE_ACCOUNT
                onDeclare(choice)
            }) { Text("Save access method") }
            Text("A saved subscription does not authorize API usage.", style = MaterialTheme.typography.bodySmall)
            Text(spec.requirements, style = MaterialTheme.typography.bodySmall)
            Text("Model", style = MaterialTheme.typography.titleSmall)
            FreeModelSelection.options(spec.id).forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = modelChoice == option, onClick = { modelChoice = option })
                    Text(option)
                }
            }
            val selected = FreeModelSelection.choose(spec.id, emptyList(), modelChoice)
            Text(
                if (selected == null) "Auto Select has no eligible free model yet. Paid and unknown prices stay unselectable."
                else "Auto Select uses $selected when free access is verified.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (spec.id == ProviderId.GEMINI) {
                val fingerprint = InstallIdentity.signingSha1(context)
                Text("Package ${context.packageName}", style = MaterialTheme.typography.bodySmall)
                Text("Current signing certificate SHA-1 $fingerprint", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copy(context, "package", context.packageName) }) { Text("Copy package") }
                    TextButton(onClick = { copy(context, "certificate", fingerprint) }) { Text("Copy certificate") }
                }
            }
            if (spec.id == ProviderId.CLOUDFLARE) {
                val account = spec.fields.firstOrNull { it.key == "account-id" }
                if (account != null) {
                    OutlinedTextField(
                        value = first,
                        onValueChange = { first = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Account ID, if discovery lists more than one") },
                        singleLine = true,
                    )
                }
            }
            if (verificationNote.isNotBlank()) Text(verificationNote, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.links.forEach { link ->
                    TextButton(onClick = { onOpenUrl(link.url) }) { Text(link.label) }
                }
            }
            if (assisted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled for discussions", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
                TextButton(onClick = {
                    onToggle(false)
                    onDeclare(AccountChoice.NOT_CONFIGURED)
                    result = "Disconnected. Assisted chat is off."
                }) { Text("Disconnect") }
            }
            if (spec.fields.isNotEmpty()) {
                TextButton(onClick = {
                    onRemoveSecrets()
                    result = "The saved credential was deleted from this device."
                }, enabled = credentialStored) { Text("Delete credential") }
            }
        }
    }
    if (confirmConnect) {
        AlertDialog(
            onDismissRequest = { confirmConnect = false },
            title = { Text("Connect") },
            text = {
                Text(
                    when (spec.id) {
                        ProviderId.GEMINI -> "Connect saves the key, then opens Google's authorization. If you allow it, the app reads the project that owns the key and whether billing is enabled. The test question is not sent."
                        ProviderId.GROQ -> "Connect saves the key and reads the model list. That response does not show the Free plan, so automated use stays off. No completion is sent."
                        ProviderId.OPENROUTER -> "Connect saves the key and reads its limits and model prices. A free model name is not enough. No completion is sent."
                        ProviderId.CLOUDFLARE -> "Connect saves the token, asks which account it can see, and reads subscriptions. A missing paid plan is not Workers Free. No model is run."
                        else -> "Save the key on this device. No paid request is sent."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmConnect = false
                    val values = spec.fields.associate { field ->
                        val index = spec.fields.indexOf(field)
                        field.key to (if (index == 0) first else second).trim()
                    }
                    first = ""
                    second = ""
                    when (spec.id) {
                        ProviderId.GEMINI -> {
                            pendingGemini = true
                            onSaveSecrets(values)
                            result = "The key is being saved. Google authorization is next. No test question is sent."
                        }
                        ProviderId.GROQ, ProviderId.OPENROUTER, ProviderId.CLOUDFLARE -> {
                            connecting = true
                            onRunCheck(values) { message ->
                                connecting = false
                                result = message
                            }
                        }
                        else -> {
                            onSaveSecrets(values)
                            result = ConnectionPresentation.explanation(ConnectionPresentation.SETUP_REQUIRED, spec.id)
                        }
                    }
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { confirmConnect = false }) { Text("Cancel") } },
        )
    }
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun paste(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

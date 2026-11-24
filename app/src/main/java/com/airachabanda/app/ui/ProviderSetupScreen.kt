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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.airachabanda.app.provider.ProviderRegistry
import com.airachabanda.app.provider.ProviderSetupCatalog
import com.airachabanda.app.provider.SetupMethod
import com.airachabanda.app.provider.gemini.InstallIdentity

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
    contentPadding: PaddingValues,
) {
    val spec = ProviderSetupCatalog.spec(config.providerId)
    val assistedOnly = SetupMethod.FREE_AUTOMATED_API !in spec.methods && SetupMethod.LOCAL_INFERENCE !in spec.methods
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
    val context = LocalContext.current
    val steps = ProviderSetupCatalog.progress(
        spec,
        method,
        credentialStored,
        billingVerified,
        enabled = assistedOnly && enabled,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(ProviderRegistry.definition(spec.id).displayName, style = MaterialTheme.typography.headlineSmall)
        Text(ProviderPresentation.status(config, enabled = enabled, credentialStored = credentialStored), style = MaterialTheme.typography.labelLarge)
        Text(spec.automatedBlocker, style = MaterialTheme.typography.bodySmall)
        Text("Access method", style = MaterialTheme.typography.titleMedium)
        spec.methods.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = method == option, onClick = { methodName = option.name })
                Text(option.label)
            }
        }
        Button(onClick = {
            val choice = if (method == SetupMethod.SUBSCRIPTION_ASSISTED) AccountChoice.PAID_SUBSCRIPTION else AccountChoice.FREE_ACCOUNT
            onDeclare(choice)
        }) { Text("Save access method") }
        Text("A saved subscription is a declaration. It does not authorize API usage.", style = MaterialTheme.typography.bodySmall)
        steps.forEach { step ->
            Text("${step.name}. ${step.state}", style = MaterialTheme.typography.bodySmall)
        }
        if (spec.id == ProviderId.GEMINI) {
            val fingerprint = InstallIdentity.signingSha1(context)
            Text("Package ${context.packageName}")
            Text("Current signing certificate SHA-1 $fingerprint")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { copy(context, "package", context.packageName) }) { Text("Copy package") }
                TextButton(onClick = { copy(context, "certificate", fingerprint) }) { Text("Copy certificate") }
            }
        }
        if (verificationNote.isNotBlank()) Text(verificationNote, style = MaterialTheme.typography.bodySmall)
        spec.fields.forEachIndexed { index, field ->
            val value = if (index == 0) first else second
            OutlinedTextField(
                value = value,
                onValueChange = { if (index == 0) first = it else second = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.label) },
                visualTransformation = if (field.key == "account-id") VisualNone else PasswordVisualTransformation(),
                singleLine = true,
            )
            TextButton(onClick = {
                val pasted = paste(context)
                if (pasted.isNotBlank()) {
                    if (index == 0) first = pasted else second = pasted
                }
            }) { Text("Paste ${field.label}") }
        }
        if (spec.fields.isNotEmpty()) {
            val ready = spec.fields.indices.all { index -> (if (index == 0) first else second).isNotBlank() }
            Button(
                onClick = {
                    val values = spec.fields.mapIndexed { index, field ->
                        field.key to (if (index == 0) first else second).trim()
                    }.toMap()
                    onSaveSecrets(values)
                    first = ""
                    second = ""
                },
                enabled = ready,
            ) { Text(if (credentialStored) "Replace credential" else "Save securely") }
            TextButton(onClick = onRemoveSecrets, enabled = credentialStored) { Text("Delete credential") }
        }
        Text(if (credentialStored) "Authentication status: credential stored, not redisplayed." else "Authentication status: no credential stored.")
        Text(if (billingVerified) "Billing eligibility: verified for free use." else "Billing eligibility: not verified.")
        Text("Last verification: ${if (verificationNote.isBlank()) "Never" else "See the note above."}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            spec.links.forEach { link ->
                TextButton(onClick = { onOpenUrl(link.url) }) { Text(link.label) }
            }
        }
        if (assistedOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled for discussions", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            TextButton(onClick = {
                onToggle(false)
                onDeclare(AccountChoice.NOT_CONFIGURED)
            }) { Text("Disconnect") }
            Text("Disconnect turns the provider off and clears the app-side declaration. Disable keeps any stored credential.")
        } else if (credentialStored) {
            TextButton(onClick = onRemoveSecrets) { Text("Disconnect and delete credential") }
        }
        Text("Connection status: ${if (billingVerified) "Eligible" else "Not verified"}. No test is sent from this screen until you confirm one on the provider's existing verification control.")
    }
}

private val VisualNone = androidx.compose.ui.text.input.VisualTransformation.None

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun paste(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

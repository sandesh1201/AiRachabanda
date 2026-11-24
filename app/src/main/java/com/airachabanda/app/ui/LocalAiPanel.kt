package com.airachabanda.app.ui

import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelCatalog
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelManager
import com.airachabanda.app.provider.local.ondevice.OnDeviceRuntime

@Composable
fun LocalAiPanel() {
    val context = LocalContext.current
    val manager = remember { OnDeviceModelManager() }
    var selectedId by rememberSaveable { mutableStateOf(OnDeviceModelCatalog.GEMMA.id) }
    var confirmDownload by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("No model file is stored on this device.") }
    val selected = OnDeviceModelCatalog.find(selectedId) ?: OnDeviceModelCatalog.GEMMA
    val available = remember(selected.id) {
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Local AI", style = MaterialTheme.typography.titleMedium)
        Text(
            "${OnDeviceRuntime.NAME} is the prepared runtime. The native library is not in this APK. No account or API key is used.",
            style = MaterialTheme.typography.bodySmall,
        )
        OnDeviceModelCatalog.recommended.forEach { model ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected.id == model.id, onClick = { selectedId = model.id })
                Text(model.displayName)
            }
        }
        Text("${selected.sizeLabel}. ${selected.fileName}.", style = MaterialTheme.typography.bodyMedium)
        Text("License: ${selected.licenseName}.", style = MaterialTheme.typography.bodySmall)
        Text("Context limit: ${selected.contextTokens} tokens. Output limit: ${OnDeviceRuntime.MAX_OUTPUT_TOKENS} tokens.", style = MaterialTheme.typography.bodySmall)
        Text("Published memory: ${selected.publishedPeakRamLabel}.", style = MaterialTheme.typography.bodySmall)
        Text("Published speed: ${selected.publishedDecode}. Device: ${selected.publishedDevice}.", style = MaterialTheme.typography.bodySmall)
        Text(selected.qualityNote, style = MaterialTheme.typography.bodySmall)
        Text("Free space on this device: $available bytes.", style = MaterialTheme.typography.bodySmall)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { confirmDownload = true }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
        Button(
            onClick = { message = manager.enable(selected.id).detail },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable") }
    }
    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download") },
            text = {
                Text(
                    "${selected.displayName} is ${selected.sizeLabel} under ${selected.licenseName}. " +
                        "The file is ${selected.fileName}. Free space is $available bytes. " +
                        "This build does not transfer the file. Owner approval is required before any download.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = false
                    message = manager.download(selected.id, consent = true, availableBytes = available).detail
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } },
        )
    }
}

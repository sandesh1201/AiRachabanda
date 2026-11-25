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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.airachabanda.app.provider.local.ondevice.HttpsModelSource
import com.airachabanda.app.provider.local.ondevice.LiteRtLmRuntime
import com.airachabanda.app.provider.local.ondevice.OnDevicePreflight
import com.airachabanda.app.provider.local.ondevice.LocalTask
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
    var library by remember { mutableStateOf("Checking the CPU library.") }
    var busy by remember { mutableStateOf(false) }
    val cancelDownload = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val directory = remember { OnDevicePreflight.modelDirectory(context.filesDir).apply { mkdirs() } }
    LaunchedEffect(Unit) {
        library = withContext(Dispatchers.IO) { LiteRtLmRuntime.probe() }
    }
    val selected = OnDeviceModelCatalog.find(selectedId) ?: OnDeviceModelCatalog.GEMMA
    val available = remember(selected.id) {
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Local AI", style = MaterialTheme.typography.titleMedium)
        Text(
            "${OnDeviceRuntime.NAME} ${OnDeviceRuntime.PINNED_VERSION} is packaged for ${OnDeviceRuntime.BACKEND}. Model weights are not installed. No account or API key is used. $library",
            style = MaterialTheme.typography.bodySmall,
        )
        OnDeviceModelCatalog.recommended.forEach { model ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected.id == model.id,
                    onClick = {
                        selectedId = model.id
                        message = "No model file is stored on this device."
                    },
                )
                Text(model.displayName)
            }
        }
        Text("${selected.fileName}", style = MaterialTheme.typography.bodyMedium)
        Text("${selected.sizeLabel}. Revision ${selected.revision}.", style = MaterialTheme.typography.bodySmall)
        Text(
            if (selected.sha256 == null) "SHA-256 is not pinned. The Gemma license has to be accepted by you before the digest can be read."
            else "SHA-256 ${selected.sha256}.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("License: ${selected.licenseName}.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Context limit: ${selected.contextTokens} tokens. Arithmetic output is ${LocalTask.ARITHMETIC.maxOutputTokens} tokens. Council output is ${LocalTask.COUNCIL.maxOutputTokens} tokens and is shortened to stay inside that window.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Published memory: ${selected.publishedPeakRamLabel}", style = MaterialTheme.typography.bodySmall)
        Text("Published speed: ${selected.publishedDecode} Device: ${selected.publishedDevice}.", style = MaterialTheme.typography.bodySmall)
        Text(selected.qualityNote, style = MaterialTheme.typography.bodySmall)
        Text("Free space on this device: $available bytes.", style = MaterialTheme.typography.bodySmall)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { confirmDownload = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Download") }
        if (busy) {
            TextButton(onClick = { cancelDownload.set(true) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
        }
        Button(
            onClick = { message = manager.enable(selected.id, directory).detail },
            enabled = OnDevicePreflight.verifiedFile(directory, selected) != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable") }
    }
    if (confirmDownload) {
        val pinned = selected.sha256 != null
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download") },
            text = {
                Text(
                    if (pinned) {
                        "${selected.displayName} is ${selected.sizeLabel} under ${selected.licenseName}. " +
                            "The file is ${selected.fileName}. Free space is $available bytes. " +
                            "Confirming downloads it into private app storage and checks the SHA-256 before installation."
                    } else {
                        "${selected.displayName} is ${selected.sizeLabel} under ${selected.licenseName}. " +
                            "The file is ${selected.fileName}. No trusted SHA-256 is pinned. " +
                            "Confirming will not download the file."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = false
                    if (!pinned) {
                        message = manager.download(selected.id, consent = true, availableBytes = available).detail
                        return@TextButton
                    }
                    cancelDownload.set(false)
                    busy = true
                    scope.launch {
                        val ticket = withContext(Dispatchers.IO) {
                            manager.download(
                                selected.id,
                                consent = true,
                                availableBytes = available,
                                directory = directory,
                                opener = HttpsModelSource::open,
                                stopWhen = { cancelDownload.get() },
                                onProgress = { written ->
                                    scope.launch { message = "Downloaded $written of ${selected.sizeBytes} bytes." }
                                },
                            )
                        }
                        message = ticket.detail
                        busy = false
                    }
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } },
        )
    }
}

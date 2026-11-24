package com.airachabanda.app.provider.local.ondevice

import java.io.File
import java.security.MessageDigest

enum class DownloadState { NOT_INSTALLED, BLOCKED, CANCELLED, INTERRUPTED, REJECTED, INSTALLED }

data class DownloadTicket(
    val modelId: String,
    val state: DownloadState,
    val bytesTransferred: Long,
    val detail: String,
)

data class IntegrityResult(val matches: Boolean, val detail: String)

data class EnableDecision(val enabled: Boolean, val detail: String)

/**
 * Prepares a download and refuses to transfer bytes until the owner approves the runtime and the file.
 */
class OnDeviceModelManager {
    private val tickets = mutableMapOf<String, DownloadTicket>()
    var networkRequests: Int = 0
        private set

    fun status(modelId: String): DownloadTicket =
        tickets[modelId] ?: DownloadTicket(modelId, DownloadState.NOT_INSTALLED, 0, "No model file is stored on this device.")

    fun download(modelId: String, consent: Boolean, availableBytes: Long): DownloadTicket {
        val model = OnDeviceModelCatalog.find(modelId)
            ?: return remember(DownloadTicket(modelId, DownloadState.BLOCKED, 0, "That model is not in the recommended catalog."))
        if (!OnDeviceModelCatalog.compatible(model)) {
            return remember(DownloadTicket(model.id, DownloadState.BLOCKED, 0, "The model failed the compatibility check."))
        }
        if (!consent) {
            return remember(DownloadTicket(model.id, DownloadState.NOT_INSTALLED, 0, "Download was not confirmed. Nothing was transferred."))
        }
        val needed = model.sizeBytes + OnDeviceRuntime.STORAGE_MARGIN_BYTES
        if (availableBytes < needed) {
            return remember(
                DownloadTicket(
                    model.id,
                    DownloadState.BLOCKED,
                    0,
                    "Available storage is $availableBytes bytes. ${model.displayName} needs ${model.sizeLabel} plus 64 MB of free space.",
                ),
            )
        }
        if (model.sha256.isNullOrBlank()) {
            return remember(
                DownloadTicket(
                    model.id,
                    DownloadState.BLOCKED,
                    0,
                    "No trusted SHA-256 is pinned for ${model.fileName}. Nothing was downloaded.",
                ),
            )
        }
        if (!OnDeviceRuntime.OWNER_DOWNLOAD_APPROVED) {
            return remember(
                DownloadTicket(
                    model.id,
                    DownloadState.BLOCKED,
                    0,
                    "Owner approval is required before ${model.fileName} is transferred. Nothing was downloaded.",
                ),
            )
        }
        return remember(DownloadTicket(model.id, DownloadState.BLOCKED, 0, "The transfer path is closed in this build."))
    }

    fun cancel(modelId: String): DownloadTicket {
        val current = status(modelId)
        return remember(
            current.copy(
                state = DownloadState.CANCELLED,
                bytesTransferred = 0,
                detail = "The transfer had not started. Cancellation removed the pending download.",
            ),
        )
    }

    fun delete(modelId: String): DownloadTicket =
        remember(DownloadTicket(modelId, DownloadState.NOT_INSTALLED, 0, "No model file is stored on this device."))

    fun enable(modelId: String): EnableDecision =
        EnableDecision(false, "Enable stays off until a verified model file is installed. ${status(modelId).detail}")

    fun verifyIntegrity(bytes: ByteArray, expectedSha256: String): IntegrityResult {
        val expected = expectedSha256.trim().lowercase()
        if (expected.isEmpty()) {
            return IntegrityResult(false, "No published digest is pinned yet. The bytes were not installed.")
        }
        val actual = sha256(bytes)
        return if (actual == expected) {
            IntegrityResult(true, "The digest matches. This check did not install the file.")
        } else {
            IntegrityResult(false, "The digest does not match. The bytes were not installed.")
        }
    }

    private fun remember(ticket: DownloadTicket): DownloadTicket {
        tickets[ticket.modelId] = ticket
        return ticket
    }

    companion object {
        fun sha256(bytes: ByteArray): String = digest(bytes)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

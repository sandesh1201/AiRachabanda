package com.airachabanda.app.provider.local.ondevice

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs a model into private app storage after consent, a storage check, and a SHA-256 match.
 * The production caller keeps approval closed, so this class is not pointed at a live URL.
 */
class OnDeviceInstaller(
    private val root: File,
    private val open: (url: String, offset: Long) -> InputStream,
) {
    var networkOpens: Int = 0
        private set

    fun install(
        model: OnDeviceModel,
        consent: Boolean,
        availableBytes: Long,
        approved: Boolean,
        stopAfterBytes: Long = Long.MAX_VALUE,
    ): DownloadTicket {
        if (!model.fileName.endsWith(".litertlm") || model.fileName.contains("sm8650", ignoreCase = true)) {
            return DownloadTicket(model.id, DownloadState.BLOCKED, 0, "That file is not a CPU LiteRT-LM model.")
        }
        if (!consent) {
            return DownloadTicket(model.id, DownloadState.NOT_INSTALLED, 0, "Download was not confirmed. Nothing was transferred.")
        }
        val needed = model.sizeBytes + OnDeviceRuntime.STORAGE_MARGIN_BYTES
        if (availableBytes < needed) {
            return DownloadTicket(
                model.id,
                DownloadState.BLOCKED,
                0,
                "Available storage is $availableBytes bytes. ${model.displayName} needs ${model.sizeLabel} plus 64 MB of free space.",
            )
        }
        val expected = model.sha256?.trim()?.lowercase().orEmpty()
        if (!approved || expected.isEmpty()) {
            return DownloadTicket(
                model.id,
                DownloadState.BLOCKED,
                0,
                if (expected.isEmpty()) {
                    "No trusted SHA-256 is pinned for ${model.fileName}. Nothing was downloaded."
                } else {
                    "Owner approval is required before ${model.fileName} is transferred. Nothing was downloaded."
                },
            )
        }
        root.mkdirs()
        val finalFile = File(root, model.fileName)
        val partial = File(root, "${model.fileName}.partial")
        if (finalFile.isFile && OnDeviceModelManager.sha256(finalFile) == expected) {
            return DownloadTicket(model.id, DownloadState.INSTALLED, finalFile.length(), "The verified file is already installed.")
        }
        val offset = if (partial.isFile) partial.length() else 0L
        if (offset > model.sizeBytes) {
            partial.delete()
            return DownloadTicket(model.id, DownloadState.REJECTED, 0, "The partial file was larger than the model. It was deleted.")
        }
        networkOpens += 1
        open(model.repositoryUrl, offset).use { input ->
            java.io.FileOutputStream(partial, true).use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = offset
                while (written < model.sizeBytes && written < stopAfterBytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val room = minOf(read.toLong(), model.sizeBytes - written, stopAfterBytes - written).toInt()
                    if (room <= 0) break
                    output.write(buffer, 0, room)
                    written += room
                }
            }
        }
        if (partial.length() < model.sizeBytes) {
            return DownloadTicket(
                model.id,
                DownloadState.INTERRUPTED,
                partial.length(),
                "The transfer stopped at ${partial.length()} bytes. The partial file remains for recovery.",
            )
        }
        val actual = OnDeviceModelManager.sha256(partial)
        if (actual != expected) {
            partial.delete()
            finalFile.delete()
            return DownloadTicket(model.id, DownloadState.REJECTED, 0, "The digest does not match. The file was not installed.")
        }
        val target = finalFile.toPath()
        val source = partial.toPath()
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return DownloadTicket(model.id, DownloadState.INSTALLED, finalFile.length(), "The digest matches. The file is installed in private storage.")
    }

    fun delete(model: OnDeviceModel): DownloadTicket {
        File(root, model.fileName).delete()
        File(root, "${model.fileName}.partial").delete()
        return DownloadTicket(model.id, DownloadState.NOT_INSTALLED, 0, "The model file was deleted from private storage.")
    }
}

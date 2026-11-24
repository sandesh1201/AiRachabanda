package com.airachabanda.app.documents

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.airachabanda.app.persistence.CouncilDao
import com.airachabanda.app.persistence.DiscussionDao
import com.airachabanda.app.persistence.GeneratedDocumentEntity
import com.airachabanda.app.persistence.WorkflowEventEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GeneratedDocumentItem(
    val id: String,
    val title: String,
    val format: String,
    val filename: String,
    val status: String,
    val sizeBytes: Long,
    val versionNumber: Int,
    val limitation: String,
    val previewPath: String?,
)

class GeneratedDocumentService(
    private val context: Context,
    private val council: CouncilDao,
    private val discussion: DiscussionDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    var beforeRender: (String) -> Unit = {}
    var storageRoot: File? = null

    fun cancel(id: String) {
        cancelled += id
    }

    suspend fun cancelPending(conversationId: String) {
        council.generatedDocuments(conversationId).filter { it.status == "GENERATING" }.forEach { row ->
            cancel(row.id)
            council.updateGeneratedDocument(row.id, "CANCELLED", 0, "", "The user stopped the discussion. This generation was cancelled.")
        }
    }

    suspend fun generate(
        conversationId: String,
        title: String,
        templateId: String,
        formatName: String,
        body: String,
        includeRecords: Boolean,
    ): String {
        val format = OutputFormat.fromName(formatName)
            ?: return "The format is not available. Nothing was rendered."
        val id = ids()
        val existing = council.generatedDocuments(conversationId)
        val version = existing.count { it.title == title.trim() && it.format == format.name && it.templateId == templateId } + 1
        val filename = fileName(title, format, version)
        council.insertGeneratedDocument(
            GeneratedDocumentEntity(
                id = id,
                conversationId = conversationId,
                title = title.trim(),
                templateId = templateId,
                format = format.name,
                filename = filename,
                mimeType = format.mime,
                sizeBytes = 0,
                fingerprint = "",
                status = "GENERATING",
                limitation = "",
                versionNumber = version,
                sourceBody = body.take(DocumentGenerationLimits.MAX_CHARS),
                includeRecords = includeRecords,
                createdAtEpochMs = clock(),
            ),
        )
        val records = if (includeRecords) loadRecords(conversationId) else StoredRecords()
        beforeRender(id)
        val outcome = DocumentEngine.generate(
            DocumentRequest(title, templateId, body, format.name, records, createdLabel = clock().toString()),
            format,
        ) { id in cancelled }
        if (outcome.status == "CANCELLED" || outcome.status == "FAILED") {
            council.updateGeneratedDocument(id, outcome.status, 0, "", outcome.report)
            council.insertEvent(
                WorkflowEventEntity(ids(), conversationId, null, "DOCUMENT_FAILED", outcome.report.take(240), clock()),
            )
            return outcome.report
        }
        val directory = File(storageRoot ?: context.filesDir, "generated/$id")
        if (!directory.mkdirs() && !directory.isDirectory) {
            council.updateGeneratedDocument(id, "FAILED", 0, "", "The document could not be stored.")
            return "The document could not be stored."
        }
        if (id in cancelled) {
            council.updateGeneratedDocument(id, "CANCELLED", 0, "", "The user stopped the discussion. This generation was cancelled.")
            return "The user stopped the discussion. This generation was cancelled."
        }
        val file = File(directory, filename)
        file.writeBytes(outcome.bytes)
        if (format == OutputFormat.PDF) writePreview(file, File(directory, "preview.png"))
        val fingerprint = sha256(outcome.bytes)
        council.updateGeneratedDocument(id, outcome.status, outcome.bytes.size.toLong(), fingerprint, outcome.report)
        council.insertEvent(
            WorkflowEventEntity(
                ids(),
                conversationId,
                null,
                "DOCUMENT_GENERATED",
                "$filename version $version was rendered locally. It was not uploaded.",
                clock(),
            ),
        )
        return "${outcome.status}. $filename version $version is stored on this device. ${outcome.report}"
    }

    suspend fun regenerate(id: String): String {
        val current = council.generatedDocument(id) ?: return "That document is no longer stored."
        return generate(current.conversationId, current.title, current.templateId, current.format, current.sourceBody, current.includeRecords)
    }

    suspend fun discardConversation(conversationId: String) {
        council.generatedDocuments(conversationId).forEach { document ->
            File(storageRoot ?: context.filesDir, "generated/${document.id}").deleteRecursively()
        }
    }

    suspend fun remove(id: String): String {
        val current = council.generatedDocument(id) ?: return "That document is no longer stored."
        File(storageRoot ?: context.filesDir, "generated/$id").deleteRecursively()
        council.deleteGeneratedDocument(id)
        council.insertEvent(
            WorkflowEventEntity(ids(), current.conversationId, null, "DOCUMENT_DELETED", "${current.filename} was deleted from this device.", clock()),
        )
        return "${current.filename} was deleted. Other versions were kept."
    }

    suspend fun inspect(id: String): String {
        val current = council.generatedDocument(id) ?: return "That document is no longer stored."
        val file = File(storageRoot ?: context.filesDir, "generated/$id/${current.filename}")
        if (!file.exists()) return "${current.filename} is recorded, and the file is missing."
        val extracted = DocumentExtractor.extract(file.readBytes(), current.filename, current.mimeType)
        val body = extracted.chunks.take(12).joinToString("\n") { "${it.reference}: ${it.text}" }
        return "${current.filename}\n${current.status}. Locally rendered. This is not an AI provider response.\n${current.limitation}\n$body"
    }

    suspend fun shareIntent(id: String): Intent? = intent(id, Intent.ACTION_SEND, stream = true)

    suspend fun openIntent(id: String): Intent? = intent(id, Intent.ACTION_VIEW, stream = false)

    suspend fun export(id: String, destination: Uri): String {
        val current = council.generatedDocument(id) ?: return "That document is no longer stored."
        val file = File(storageRoot ?: context.filesDir, "generated/$id/${current.filename}")
        if (!file.exists()) return "The file is missing, so it was not copied."
        return try {
            context.contentResolver.openOutputStream(destination)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: return "The destination could not be opened. The local file was kept."
            "${current.filename} was copied to the location you chose. The copy in this app was kept."
        } catch (_: Exception) {
            "The copy did not finish. The local file was kept."
        }
    }

    fun toItem(entity: GeneratedDocumentEntity): GeneratedDocumentItem {
        val preview = File(storageRoot ?: context.filesDir, "generated/${entity.id}/preview.png")
        return GeneratedDocumentItem(
            id = entity.id,
            title = entity.title,
            format = entity.format,
            filename = entity.filename,
            status = entity.status,
            sizeBytes = entity.sizeBytes,
            versionNumber = entity.versionNumber,
            limitation = entity.limitation,
            previewPath = preview.takeIf { it.exists() }?.absolutePath,
        )
    }

    private suspend fun loadRecords(conversationId: String): StoredRecords {
        val messages = council.messages(conversationId).map { it.body.take(500) }
        val evidence = council.attachments(conversationId).flatMap { attachment ->
            council.chunks(attachment.id).filter { it.processed }.take(4).map { chunk ->
                "attachment:${attachment.id}#${chunk.reference.replace(' ', '-')}" to chunk.text.take(400)
            }
        }
        val objections = council.latestSession(conversationId)?.let { session ->
            discussion.objections(session.id).map { it.explanation.take(400) }
        }.orEmpty()
        return StoredRecords(messages, evidence, objections)
    }

    private suspend fun intent(id: String, action: String, stream: Boolean): Intent? {
        val current = council.generatedDocument(id) ?: return null
        val file = File(storageRoot ?: context.filesDir, "generated/$id/${current.filename}")
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(action).setType(current.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (stream) intent.putExtra(Intent.EXTRA_STREAM, uri) else intent.setDataAndType(uri, current.mimeType)
    }

    private fun writePreview(file: File, destination: File) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return
                    renderer.openPage(0).use { page ->
                        val width = 480
                        val height = (page.height * (width.toFloat() / page.width)).toInt().coerceAtLeast(1)
                        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val matrix = Matrix().apply { setScale(width.toFloat() / page.width, width.toFloat() / page.width) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(destination).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        bitmap.recycle()
                    }
                }
            }
        } catch (_: Throwable) {
            destination.delete()
        }
    }

    private fun fileName(title: String, format: OutputFormat, version: Int): String {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "document" }
        return "$slug-v$version.${format.extension}"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.airachabanda.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.documents.DocumentExtractor
import com.airachabanda.app.documents.OutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentDeviceTest {
    @Test
    fun labeledLocalDocumentIsGeneratedOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AIRachabandaApplication
        val id = app.container.repository.createConversation("Local report", ResearchMode.STANDARD)
        val message = app.container.documents.generate(
            conversationId = id,
            title = "TEST FIXTURE local report",
            templateId = "research-report",
            formatName = "PDF",
            body = "TEST FIXTURE local document. The total is 17 times 23.\nMATH 17*23=391\nROW|TEST FIXTURE row|17|23",
            includeRecords = false,
        )
        assertTrue(message.contains("stored on this device"))
        val stored = app.container.councilDao.generatedDocuments(id).single()
        val file = File(context.filesDir, "generated/${stored.id}/${stored.filename}")
        val bytes = file.readBytes()
        assertTrue(bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF"))
        val extracted = DocumentExtractor.extract(bytes, stored.filename, OutputFormat.PDF.mime)
        val text = extracted.chunks.joinToString("\n") { it.text }
        assertTrue(text.contains("TEST FIXTURE local document"))
        assertTrue(text.contains("not an AI provider response") || message.contains("not an AI provider response"))
    }
}

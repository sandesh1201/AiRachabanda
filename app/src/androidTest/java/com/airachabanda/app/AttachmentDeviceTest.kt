package com.airachabanda.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airachabanda.app.core.model.ResearchMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentDeviceTest {
    @Test
    fun labeledLocalDocumentIsExtractedOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AIRachabandaApplication
        val id = context.container.repository.createConversation("Document check", ResearchMode.STANDARD)
        val message = context.container.attachments.importBytes(
            id,
            "local-note.txt",
            "text/plain",
            "TEST FIXTURE local document. The total is 17 times 23.".toByteArray(),
        )
        assertTrue(message, message.contains("Extracted"))
        val stored = context.container.councilDao.attachments(id).single()
        assertTrue(stored.processingStatus == "EXTRACTED" || stored.processingStatus == "PARTIAL")
        val text = context.container.attachments.inspect(stored.id)
        assertTrue(text.contains("TEST FIXTURE local document"))
        assertTrue(text.contains("not an instruction"))
    }
}
package com.airachabanda.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.orchestration.CouncilWorkflow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuityDeviceTest {
    @Test
    fun labeledDiscussionCanPauseOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AIRachabandaApplication
        val workflow = CouncilWorkflow(app.container.orchestration, app.container.councilDao, app.container.discussionDao)
        val id = app.container.repository.createConversation("Continuity check", ResearchMode.STANDARD)
        val started = workflow.start(
            id,
            "TEST FIXTURE continuity on device.",
            ResearchMode.STANDARD,
            2,
            setOf(ProviderId.CLAUDE, ProviderId.CHATGPT),
        )
        assertTrue(started.contains("Discussion started") || started.contains("Stage"))
        val paused = workflow.pause(id)
        assertTrue(paused.contains("Paused"))
        val stage = workflow.inspect(id)
        assertEquals("Paused", stage.stage)
        assertTrue(stage.continuityNote.contains("Independent multi-model review is unavailable") || stage.activeParticipantCount >= 1)
    }
}

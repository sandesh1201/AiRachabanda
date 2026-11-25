package com.airachabanda.app

import com.airachabanda.app.ui.DiscussionPresence
import org.junit.Assert.assertEquals
import org.junit.Test

class Milestone016ATest {
    @Test
    fun oneStatusIsShownForEachDiscussionState() {
        assertEquals(DiscussionPresence.READY, DiscussionPresence.phase(null))
        assertEquals(DiscussionPresence.READY, DiscussionPresence.phase("No session"))
        assertEquals(DiscussionPresence.RUNNING, DiscussionPresence.phase("Synthesis"))
        assertEquals(DiscussionPresence.RUNNING, DiscussionPresence.phase("Collecting responses"))
        assertEquals(DiscussionPresence.PAUSED, DiscussionPresence.phase("Paused"))
        assertEquals(DiscussionPresence.WAITING, DiscussionPresence.phase("Waiting for your response"))
        assertEquals(DiscussionPresence.FINISHED, DiscussionPresence.phase("Completed"))
        assertEquals(DiscussionPresence.FINISHED, DiscussionPresence.phase("Stopped"))
        assertEquals("Synthesis · 2 still open", DiscussionPresence.summary("Synthesis", 2))
        assertEquals("Completed", DiscussionPresence.summary("Completed", 4))
        assertEquals("Paused", DiscussionPresence.summary("Paused", 3))
    }
}

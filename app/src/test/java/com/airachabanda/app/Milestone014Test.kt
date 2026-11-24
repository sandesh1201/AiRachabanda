package com.airachabanda.app

import com.airachabanda.app.ui.AdaptiveLayout
import com.airachabanda.app.ui.AdaptiveWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone014Test {
    @Test
    fun widthFollowsWindowDpNotADeviceName() {
        assertEquals(AdaptiveWidth.COMPACT, AdaptiveLayout.widthFromDp(411))
        assertEquals(AdaptiveWidth.MEDIUM, AdaptiveLayout.widthFromDp(700))
        assertEquals(AdaptiveWidth.EXPANDED, AdaptiveLayout.widthFromDp(923))
    }

    @Test
    fun separatingHingeReservesAGapAndAFlatFoldDoesNot() {
        val book = AdaptiveLayout.foldGuide(
            reported = true,
            separating = true,
            occluding = false,
            halfOpened = true,
            vertical = true,
            startPx = 840,
            endPx = 900,
            density = 2f,
        )
        assertTrue(book.book)
        assertEquals(30, book.gapDp)
        assertTrue(book.label.contains("Book"))
        val flat = AdaptiveLayout.foldGuide(
            reported = true,
            separating = false,
            occluding = false,
            halfOpened = false,
            vertical = true,
            startPx = 840,
            endPx = 900,
            density = 2f,
        )
        assertEquals(0, flat.gapDp)
        assertFalse(flat.book)
        assertEquals("Folding posture was not reported. The layout uses the window width.", AdaptiveLayout.foldGuide(
            reported = false,
            separating = true,
            occluding = true,
            halfOpened = true,
            vertical = true,
            startPx = 1,
            endPx = 2,
            density = 2f,
        ).label)
    }

    @Test
    fun controlsStayOffTheHingeGap() {
        assertTrue(AdaptiveLayout.controlsAvoidGap(0, 400, 420, 480))
        assertFalse(AdaptiveLayout.controlsAvoidGap(400, 500, 420, 480))
        val tabletop = AdaptiveLayout.foldGuide(
            reported = true,
            separating = true,
            occluding = true,
            halfOpened = true,
            vertical = false,
            startPx = 1000,
            endPx = 1100,
            density = 2.5f,
        )
        assertTrue(tabletop.tabletop)
        assertTrue(tabletop.label.contains("Tabletop"))
    }
}

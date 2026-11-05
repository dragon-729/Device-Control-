package com.droidlink.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveStreamControllerTest {
    @Test fun startsAtRequestedSettings() {
        val c = AdaptiveStreamController(15, 70)
        assertEquals(15, c.fps)
        assertEquals(70, c.quality)
        assertEquals(66L, c.frameIntervalMs())
    }

    @Test fun congestionLowersQualityAndFrameRateDownToFloors() {
        val c = AdaptiveStreamController(30, 90)
        repeat(50) { c.onBacklog(AdaptiveStreamController.HIGH_BACKLOG + 1) }
        assertEquals(AdaptiveStreamController.MIN_QUALITY, c.quality)
        assertEquals(AdaptiveStreamController.MIN_FPS, c.fps)
    }

    @Test fun recoversGraduallyButNeverAboveRequested() {
        val c = AdaptiveStreamController(12, 60)
        repeat(3) { c.onBacklog(AdaptiveStreamController.HIGH_BACKLOG + 1) }
        val degraded = c.quality
        assertTrue(degraded < 60)
        repeat(AdaptiveStreamController.CALM_FRAMES_TO_RAISE) { c.onBacklog(0) }
        assertTrue(c.quality > degraded)
        repeat(2000) { c.onBacklog(0) }
        assertEquals(60, c.quality)
        assertEquals(12, c.fps)
    }

    @Test fun mediumBacklogHoldsSteady() {
        val c = AdaptiveStreamController(12, 60)
        repeat(100) { c.onBacklog(100 * 1024L) }
        assertEquals(60, c.quality)
    }

    @Test fun framesAreDroppedWhenQueueIsDeep() {
        val c = AdaptiveStreamController()
        assertFalse(c.shouldDrop(10_000))
        assertTrue(c.shouldDrop(AdaptiveStreamController.DROP_BACKLOG + 1))
    }

    @Test fun reconfigureClampsToLimits() {
        val c = AdaptiveStreamController(12, 60)
        c.configure(500, 1)
        assertEquals(AdaptiveStreamController.MAX_FPS, c.requestedFps)
        assertEquals(AdaptiveStreamController.MIN_QUALITY, c.requestedQuality)
        assertEquals(AdaptiveStreamController.MIN_QUALITY, c.quality)
    }
}

class GeometryTest {
    @Test fun normalisedCoordinatesMapToPixelsAndAreClamped() {
        assertEquals(0f, Geometry.toPixel(0f, 1080), 0.001f)
        assertEquals(1079f, Geometry.toPixel(1f, 1080), 0.001f)
        assertEquals(539.5f, Geometry.toPixel(0.5f, 1080), 0.001f)
        assertEquals(1079f, Geometry.toPixel(7f, 1080), 0.001f)
        assertEquals(0f, Geometry.toPixel(-3f, 1080), 0.001f)
        assertEquals(0f, Geometry.toPixel(Float.NaN, 1080), 0.001f)
    }

    @Test fun scrollDownMovesFingerUp() {
        val p = Geometry.scrollPath("down", 1000, 2000)!!
        assertTrue(p[3] < p[1])
        assertEquals(p[0], p[2], 0.001f)
        val up = Geometry.scrollPath("up", 1000, 2000)!!
        assertTrue(up[3] > up[1])
        val right = Geometry.scrollPath("right", 1000, 2000)!!
        assertTrue(right[2] < right[0])
        assertNull(Geometry.scrollPath("sideways", 1000, 2000))
    }

    @Test fun gestureDurationsAreBounded() {
        assertEquals(350L, Geometry.clampDuration(null, 350))
        assertEquals(100L, Geometry.clampDuration(1, 350, 100, 3000))
        assertEquals(3000L, Geometry.clampDuration(999_999, 350, 100, 3000))
    }
}

class TextEditingTest {
    @Test fun insertsAtCursorAndAdvancesIt() {
        val e = TextEditing.insert("helo", 2, 2, "l")
        assertEquals("hello", e.text)
        assertEquals(3, e.cursor)
    }

    @Test fun insertReplacesSelection() {
        assertEquals("hi there", TextEditing.insert("hello there", 0, 5, "hi").text)
        assertEquals("hi there", TextEditing.insert("hello there", 5, 0, "hi").text) // reversed selection
    }

    @Test fun noSelectionAppendsAtEnd() {
        val e = TextEditing.insert("abc", -1, -1, "d")
        assertEquals("abcd", e.text)
        assertEquals(4, e.cursor)
    }

    @Test fun backspaceDeletesPreviousCharOrSelection() {
        assertEquals("ab", TextEditing.backspace("abc", 3, 3).text)
        assertEquals(2, TextEditing.backspace("abc", 3, 3).cursor)
        assertEquals("c", TextEditing.backspace("abc", 0, 2).text)
        assertEquals("abc", TextEditing.backspace("abc", 0, 0).text)
        assertEquals("", TextEditing.backspace("", -1, -1).text)
    }

    @Test fun outOfRangeSelectionIsTreatedAsEnd() {
        assertEquals("abcX", TextEditing.insert("abc", 99, 99, "X").text)
    }

    @Test fun unicodeIsPreserved() {
        assertEquals("héllo", TextEditing.insert("hllo", 1, 1, "é").text)
        assertArrayEquals(charArrayOf('a'), TextEditing.backspace("ab", 2, 2).text.toCharArray())
    }
}

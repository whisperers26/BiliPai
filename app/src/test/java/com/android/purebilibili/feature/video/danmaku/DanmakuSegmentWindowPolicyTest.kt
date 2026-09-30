package com.android.purebilibili.feature.video.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuSegmentWindowPolicyTest {
    @Test
    fun `position maps to one-based six-minute segments`() {
        assertEquals(1, segmentIndexForPosition(0L))
        assertEquals(1, segmentIndexForPosition(359_999L))
        assertEquals(2, segmentIndexForPosition(360_000L))
        assertEquals(10, segmentIndexForPosition(3_599_999L))
    }

    @Test
    fun `window keeps previous current and next segments within bounds`() {
        assertEquals(listOf(1, 2), segmentWindowForPosition(0L, totalSegments = 10))
        assertEquals(listOf(4, 5, 6), segmentWindowForPosition(4 * 360_000L, totalSegments = 10))
        assertEquals(listOf(9, 10), segmentWindowForPosition(9 * 360_000L, totalSegments = 10))
    }

    @Test
    fun `window replacement only happens after crossing a segment boundary`() {
        assertFalse(shouldReplaceDanmakuWindow(listOf(1, 2), 120_000L, 10))
        assertTrue(shouldReplaceDanmakuWindow(listOf(1, 2), 720_000L, 10))
    }

    @Test
    fun `playback progress requests the next segment window`() {
        assertTrue(
            shouldRequestDanmakuWindow(
                activeSegments = listOf(1, 2),
                pendingSegments = emptyList(),
                requestInFlight = false,
                positionMs = 360_000L,
                totalSegments = 10
            )
        )
    }

    @Test
    fun `repeated guard ticks do not restart the same in-flight window request`() {
        assertFalse(
            shouldRequestDanmakuWindow(
                activeSegments = listOf(1, 2),
                pendingSegments = listOf(1, 2, 3),
                requestInFlight = true,
                positionMs = 360_000L,
                totalSegments = 10
            )
        )
    }

    @Test
    fun `return to original window cancels a different pending load`() {
        assertTrue(shouldCancelPendingDanmakuWindow(listOf(4, 5, 6), 0L, 10))
        assertFalse(shouldRequestDanmakuWindow(listOf(1, 2), listOf(4, 5, 6), true, 0L, 10))
    }

    @Test
    fun `same pending window and absent requests are preserved`() {
        assertFalse(shouldCancelPendingDanmakuWindow(listOf(4, 5, 6), 4 * 360_000L, 10))
        assertFalse(shouldCancelPendingDanmakuWindow(emptyList(), 0L, 10))
    }

    @Test
    fun `scrubbing gates asynchronous data and playback callbacks`() {
        val source = java.io.File(
            "src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt"
        ).readText()
        val dataCommit = source.substringAfter("activeSegmentIndices = requestedSegments")
        assertTrue(dataCommit.indexOf("if (isSeekScrubbing)") < dataCommit.indexOf("rollWindowForward("))
        val playingCallback = source.substringAfter("override fun onIsPlayingChanged(isPlayerPlaying: Boolean) {")
        assertTrue(playingCallback.trimStart().startsWith("if (isSeekScrubbing) return"))
        val overlay = java.io.File(
            "src/main/java/com/android/purebilibili/feature/video/ui/overlay/PortraitProgressBar.kt"
        ).readText()
        assertFalse(overlay.contains("tryAwaitRelease()"))
        assertTrue(overlay.contains("if (dragInProgress) currentOnSeekDragCancel()"))
    }

    @Test
    fun `renderer supports seamless forward window roll instead of timeline reload`() {
        val managerSource = java.io.File(
            "src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt"
        ).readText()
        val engineSource = java.io.File(
            "../danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt"
        ).readText()

        assertTrue(managerSource.contains("rollWindowForward("))
        assertFalse(managerSource.contains("reason = \"${'$'}reason:complete\""))
        assertFalse(managerSource.contains("reason = \"${'$'}reason:anchor\""))
        assertTrue(managerSource.contains("reason != \"playback_progress\""))
        assertTrue(engineSource.contains("controller.appendData("))
        assertTrue(engineSource.contains("controller.discardDataBefore("))
    }
}

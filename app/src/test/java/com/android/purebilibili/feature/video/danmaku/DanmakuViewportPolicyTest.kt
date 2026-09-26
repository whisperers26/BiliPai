package com.android.purebilibili.feature.video.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuViewportPolicyTest {
    @Test
    fun `invalid geometry does not produce a placeholder viewport`() {
        assertNull(resolveDanmakuViewport(0, 608, 3f, 1080f))
        assertNull(resolveDanmakuViewport(1080, 608, 0f, 1080f))
        assertNull(resolveDanmakuViewport(1080, 608, 3f, Float.NaN))
    }

    @Test
    fun `scale follows only the shown video width`() {
        val fullscreen = requireNotNull(resolveDanmakuViewport(1920, 1080, 3f, 1920f))
        val inline = requireNotNull(resolveDanmakuViewport(1080, 608, 3f, 1920f))
        val tallSurface = requireNotNull(resolveDanmakuViewport(1080, 2392, 3f, 1920f))
        assertEquals(1f, fullscreen.scale, 0f)
        assertEquals(1080f / 1920f, inline.scale, 0.0001f)
        assertEquals(inline.scale, tallSurface.scale, 0f)
    }

    @Test
    fun `reference is the width of a fullscreen video frame`() {
        // Tall phone: a 16:9 frame is limited by the short side either way round.
        assertEquals(1920f, resolveDanmakuReferenceWidthPx(1080, 2392), 0f)
        assertEquals(1920f, resolveDanmakuReferenceWidthPx(2392, 1080), 0f)
        // Near-square foldable inner screen: the frame spans the long side.
        assertEquals(2504f, resolveDanmakuReferenceWidthPx(2256, 2504), 0f)
        assertEquals(0f, resolveDanmakuReferenceWidthPx(0, 2504), 0f)
    }

    @Test
    fun `foldable inline and in-window fullscreen show the same danmaku size`() {
        val reference = resolveDanmakuReferenceWidthPx(2256, 2504)
        val inline = requireNotNull(resolveDanmakuViewport(2256, 1269, 3f, reference))
        // In-window fullscreen keeps the portrait window, so only the surface height changes.
        val fullscreen = requireNotNull(resolveDanmakuViewport(2256, 2504, 3f, reference))
        assertEquals(inline.scale, fullscreen.scale, 0f)
        assertEquals(2256f / 2504f, inline.scale, 0.0001f)
    }

    @Test
    fun `proportional geometry retains line budget including scaled interline spacing`() {
        fun lines(scale: Float) = resolveDanmakuVisibleLineCount(
            visibleHeightPx = 500f * scale,
            areaRatioHint = 0.5f,
            fontSize = 20f * scale,
            strokeWidth = 1.5f * scale,
            strokeEnabled = true,
            lineHeight = 1.6f,
            massiveMode = true,
            viewportScale = scale
        )
        assertEquals(lines(1f), lines(0.5f))
        val scale = 0.5f
        val occupiedHeight = 32f * scale + (lines(scale) - 1) * (32f + 18f) * scale
        assertTrue(occupiedHeight <= 500f * scale)
    }
}

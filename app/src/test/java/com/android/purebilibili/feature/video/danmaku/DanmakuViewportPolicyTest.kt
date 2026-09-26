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
    fun `rotation preserves scale and a smaller window really shrinks below three quarters`() {
        val landscape = requireNotNull(resolveDanmakuViewport(2392, 1080, 3f, 1080f))
        val portrait = requireNotNull(resolveDanmakuViewport(1080, 2392, 3f, 1080f))
        val inline = requireNotNull(resolveDanmakuViewport(1080, 608, 3f, 1080f))
        assertEquals(landscape.scale, portrait.scale, 0f)
        assertEquals(608f / 1080f, inline.scale, 0.0001f)
        assertTrue(inline.scale < 0.75f)
    }

    @Test
    fun `reference is the short side of a fullscreen video frame`() {
        // Tall phone: a 16:9 frame fills the short side either way round.
        assertEquals(1080f, resolveDanmakuReferenceShortSidePx(1080, 2392), 0f)
        assertEquals(1080f, resolveDanmakuReferenceShortSidePx(2392, 1080), 0f)
        // Near-square foldable inner screen: the fullscreen frame is letterboxed.
        assertEquals(2504f * 9f / 16f, resolveDanmakuReferenceShortSidePx(2256, 2504), 0.001f)
        assertEquals(0f, resolveDanmakuReferenceShortSidePx(0, 2504), 0f)
    }

    @Test
    fun `foldable fullscreen video keeps full size and inline stays close to it`() {
        val reference = resolveDanmakuReferenceShortSidePx(2256, 2504)
        val fullscreen = requireNotNull(resolveDanmakuViewport(2504, 1409, 3f, reference))
        val inline = requireNotNull(resolveDanmakuViewport(1624, 913, 3f, reference))
        assertEquals(1f, fullscreen.scale, 0.001f)
        assertTrue(inline.scale > 0.6f)
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

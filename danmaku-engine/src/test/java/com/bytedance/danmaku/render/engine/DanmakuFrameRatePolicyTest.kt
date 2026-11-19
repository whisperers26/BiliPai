package com.bytedance.danmaku.render.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DanmakuFrameRatePolicyTest {
    private fun mode(width: Int, height: Int, rate: Float) = DanmakuDisplayMode(width, height, rate)

    @Test
    fun `asks for the fastest rate of the current resolution`() {
        // An invalidating View otherwise gets the NORMAL category (60Hz) and only touch boosts
        // reach 120Hz, so scrolling danmaku alternates between crisp and smeared.
        val modes = listOf(mode(2256, 2504, 60f), mode(2256, 2504, 120f), mode(1080, 2520, 144f))
        assertEquals(120f, resolveDanmakuFrameRate(modes, width = 2256, height = 2504))
    }

    @Test
    fun `ignores rates of other resolutions`() {
        val modes = listOf(mode(1080, 2520, 144f), mode(1440, 3120, 60f))
        assertEquals(60f, resolveDanmakuFrameRate(modes, width = 1440, height = 3120))
    }

    @Test
    fun `leaves the vote to the system without a usable mode`() {
        assertNull(resolveDanmakuFrameRate(emptyList(), width = 1080, height = 2400))
        assertNull(resolveDanmakuFrameRate(listOf(mode(1080, 2400, Float.NaN)), width = 1080, height = 2400))
        assertNull(resolveDanmakuFrameRate(listOf(mode(1080, 2400, 0f)), width = 1080, height = 2400))
    }
}

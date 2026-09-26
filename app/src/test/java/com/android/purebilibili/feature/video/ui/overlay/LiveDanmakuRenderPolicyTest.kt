package com.android.purebilibili.feature.video.ui.overlay

import android.graphics.Typeface
import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.feature.video.danmaku.DanmakuConfig
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuTextSizePx
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDanmakuRenderPolicyTest {

    @BeforeTest
    fun stubTypeface() {
        // The JVM android.jar stubs return null, which the non-null typeface resolver rejects.
        mockkStatic(Typeface::class)
        every { Typeface.create(any<String>(), any<Int>()) } returns mockk()
    }

    @AfterTest
    fun restoreTypeface() {
        unmockkStatic(Typeface::class)
    }

    @Test
    fun `live batch only appends and never restarts engine`() {
        val appended = mutableListOf<List<Int>>()

        appendLiveDanmakuBatch(listOf(1, 2, 3), appended::add)

        assertEquals(listOf(listOf(1, 2, 3)), appended)
    }

    @Test
    fun `plain text avoids bitmap while rich messages keep bitmap path`() {
        assertFalse(shouldRenderLiveDanmakuAsBitmap(isSuperChat = false, emoticonUrl = null))
        assertTrue(shouldRenderLiveDanmakuAsBitmap(isSuperChat = true, emoticonUrl = null))
        assertTrue(shouldRenderLiveDanmakuAsBitmap(isSuperChat = false, emoticonUrl = "https://example.test/e.png"))
    }

    @Test
    fun `live render config matches the video player for the same settings and viewport`() {
        val settings = DanmakuSettings(
            opacity = 0.4f,
            fontScale = 1.5f,
            speed = 1.5f,
            displayArea = 0.25f,
            fontWeight = 7,
            strokeWidth = 2f,
            lineHeight = 1.2f,
            scrollDurationSeconds = 9f,
            staticDurationSeconds = 6f
        )
        val viewport = requireNotNull(resolveDanmakuViewport(1968, 1107, 2.625f, 2184f))
        val video = DanmakuConfig().apply {
            opacity = settings.opacity
            fontScale = settings.fontScale
            fontWeight = settings.fontWeight
            speedFactor = settings.speed
            scrollDurationSeconds = settings.scrollDurationSeconds
            displayAreaRatio = settings.displayArea
            strokeWidth = settings.strokeWidth
            lineHeight = settings.lineHeight
            staticDurationSeconds = settings.staticDurationSeconds
        }.resolveRenderConfig(viewport)

        val live = resolveLiveDanmakuRenderConfig(settings, viewport)

        assertEquals(video, live)
        assertEquals(resolveDanmakuTextSizePx(viewport, 1.5f), live.textSizePx)
        assertEquals(viewport.scale, live.viewportScale)
        assertEquals(102, live.alpha)
        assertEquals(13_500L, live.scrollDurationMs)
    }

    @Test
    fun `live bitmap danmaku are drawn unscaled so the engine can apply the viewport scale`() {
        val viewport = requireNotNull(resolveDanmakuViewport(1968, 1107, 2.625f, 2184f))

        assertEquals(
            resolveDanmakuTextSizePx(viewport, 1.5f) / viewport.scale,
            resolveLiveDanmakuBitmapTextSizePx(viewport, 1.5f),
            0.001f
        )
    }
}

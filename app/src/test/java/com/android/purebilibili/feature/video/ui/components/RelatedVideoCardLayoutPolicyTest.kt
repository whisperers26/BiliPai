package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.store.RelatedVideoCardHeight
import com.android.purebilibili.feature.home.components.cards.HorizontalVideoCoverSizing
import com.android.purebilibili.feature.home.components.cards.resolveHorizontalVideoCoverWidthPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelatedVideoCardLayoutPolicyTest {

    @Test
    fun `narrow half opened pane stacks cover above metadata`() {
        assertEquals(
            RelatedVideoCardLayout.STACKED,
            resolveRelatedVideoCardLayout(319),
        )
    }

    @Test
    fun `boundary width keeps horizontal card`() {
        assertEquals(
            RelatedVideoCardLayout.HORIZONTAL,
            resolveRelatedVideoCardLayout(320),
        )
        assertEquals(
            RelatedVideoCardLayout.HORIZONTAL,
            resolveRelatedVideoCardLayout(480),
        )
    }

    @Test
    fun `pane width excludes list and card padding before choosing layout`() {
        assertEquals(
            RelatedVideoCardLayout.STACKED,
            resolveRelatedVideoListCardLayout(
                viewportWidthPx = 351,
                horizontalContentPaddingPx = 16,
                cardHorizontalPaddingPx = 8,
                density = 1f,
            ),
        )
        assertEquals(
            RelatedVideoCardLayout.HORIZONTAL,
            resolveRelatedVideoListCardLayout(
                viewportWidthPx = 352,
                horizontalContentPaddingPx = 16,
                cardHorizontalPaddingPx = 8,
                density = 1f,
            ),
        )
    }

    @Test
    fun `fractional density keeps independently rounded padding at the boundary`() {
        // At density 2.625, 8dp rounds to 21px and 10dp rounds to 26px per side.
        assertEquals(
            RelatedVideoCardLayout.HORIZONTAL,
            resolveRelatedVideoListCardLayout(934, 42, 26, 2.625f),
        )
        assertEquals(
            RelatedVideoCardLayout.STACKED,
            resolveRelatedVideoListCardLayout(933, 42, 26, 2.625f),
        )
    }

    @Test
    fun `pane narrower than its padding stays stacked`() {
        assertEquals(
            RelatedVideoCardLayout.STACKED,
            resolveRelatedVideoListCardLayout(24, 16, 10, 1f),
        )
    }

    @Test
    fun `card heights use a fixed cover height that does not follow the card width`() {
        val high = resolveRelatedVideoCoverSizing(RelatedVideoCardHeight.HIGH)
        val medium = resolveRelatedVideoCoverSizing(RelatedVideoCardHeight.MEDIUM)
        assertTrue(high is HorizontalVideoCoverSizing.FixedHeight)
        assertTrue(medium is HorizontalVideoCoverSizing.FixedHeight)
        assertTrue(medium.heightDp < high.heightDp)
        assertEquals(HorizontalVideoCoverSizing.FitInfo, resolveRelatedVideoCoverSizing(RelatedVideoCardHeight.LOW))

        // Portrait and landscape card widths give the same cover height.
        listOf(600, 1400).forEach { maxCoverWidthPx ->
            val width = resolveHorizontalVideoCoverWidthPx(
                sizing = high,
                maxCoverWidthPx = maxCoverWidthPx,
                aspectRatio = 1.6f,
                density = 2f,
                infoHeightForWidth = { 0 },
                infoWidthForCover = { 0 },
            )
            assertEquals(384, width)
        }
    }

    @Test
    fun `low card height makes the cover as tall as the info column`() {
        val width = resolveHorizontalVideoCoverWidthPx(
            sizing = HorizontalVideoCoverSizing.FitInfo,
            maxCoverWidthPx = 600,
            aspectRatio = 1.6f,
            density = 2f,
            infoHeightForWidth = { 200 },
            infoWidthForCover = { cover -> 1000 - cover },
        )
        assertEquals(320, width)
    }

    @Test
    fun `cover never grows past the width fraction on narrow cards`() {
        val width = resolveHorizontalVideoCoverWidthPx(
            sizing = HorizontalVideoCoverSizing.FixedHeight(120f),
            maxCoverWidthPx = 200,
            aspectRatio = 1.6f,
            density = 2f,
            infoHeightForWidth = { 0 },
            infoWidthForCover = { 0 },
        )
        assertEquals(200, width)
    }

    @Test
    fun `low card height reserves the full title height`() {
        assertEquals(2, resolveRelatedVideoTitleMinLines(RelatedVideoCardHeight.LOW, 2))
        assertEquals(2, resolveRelatedVideoTitleMinLines(RelatedVideoCardHeight.LOW, Int.MAX_VALUE))
        assertEquals(1, resolveRelatedVideoTitleMinLines(RelatedVideoCardHeight.HIGH, 2))
    }

    @Test
    fun `only the high card height pins the author row to the bottom`() {
        assertTrue(shouldPinRelatedVideoMetaToBottom(RelatedVideoCardHeight.HIGH))
        assertFalse(shouldPinRelatedVideoMetaToBottom(RelatedVideoCardHeight.MEDIUM))
        assertFalse(shouldPinRelatedVideoMetaToBottom(RelatedVideoCardHeight.LOW))
    }

    @Test
    fun `unknown stored card height falls back to high`() {
        assertEquals(RelatedVideoCardHeight.HIGH, RelatedVideoCardHeight.fromValue(99))
        RelatedVideoCardHeight.entries.forEach {
            assertEquals(it, RelatedVideoCardHeight.fromValue(it.value))
        }
    }
}

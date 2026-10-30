package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.store.RelatedVideoCardHeight
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `high card height keeps the original cover size`() {
        assertEquals(1f, resolveRelatedVideoCoverWidthScale(RelatedVideoCardHeight.HIGH))
    }

    @Test
    fun `lower card heights shrink the cover step by step`() {
        val high = resolveRelatedVideoCoverWidthScale(RelatedVideoCardHeight.HIGH)
        val medium = resolveRelatedVideoCoverWidthScale(RelatedVideoCardHeight.MEDIUM)
        val low = resolveRelatedVideoCoverWidthScale(RelatedVideoCardHeight.LOW)
        assertTrue(low < medium && medium < high)
    }

    @Test
    fun `unknown stored card height falls back to high`() {
        assertEquals(RelatedVideoCardHeight.HIGH, RelatedVideoCardHeight.fromValue(99))
        RelatedVideoCardHeight.entries.forEach {
            assertEquals(it, RelatedVideoCardHeight.fromValue(it.value))
        }
    }
}

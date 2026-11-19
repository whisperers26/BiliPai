package com.android.purebilibili.core.util

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabletopLayoutFoldingInfoTest {

    private val windowSizeClass = WindowSizeClass(
        widthSizeClass = WindowWidthSizeClass.Expanded,
        heightSizeClass = WindowHeightSizeClass.Expanded,
        widthDp = 840.dp,
        heightDp = 840.dp,
    )

    private val halfOpenedHorizontal = AppHingeFeature(
        orientation = AppHingeOrientation.Horizontal,
        bounds = IntRect(0, 900, 1800, 900),
        isSeparating = true,
        isOccluding = false,
        isFlat = false,
    )

    @Test
    fun `half folded tabletop is laid out exactly like the unfolded screen`() {
        val tabletop = resolveLayoutFoldingFeatureInfo(
            isTabletop = true,
            hinges = listOf(halfOpenedHorizontal),
        )
        val unfolded = resolveLayoutFoldingFeatureInfo(
            isTabletop = false,
            hinges = listOf(halfOpenedHorizontal.copy(isSeparating = false, isFlat = true)),
        )

        assertEquals(AppFoldPosture.Flat, tabletop.posture)
        assertEquals(unfolded, tabletop)
        assertFalse(tabletop.hasObstructingHinge)
        assertFalse(AppWindowAdaptiveInfo(windowSizeClass, tabletop).shouldAvoidHinge)
        assertEquals(
            listOf(IntRect(0, 0, 1800, 1800)),
            resolveHingeSafeContentRegions(1800, 1800, tabletop.layoutHinges()),
        )
    }

    @Test
    fun `occluding horizontal hinge still blocks content in tabletop`() {
        val info = resolveLayoutFoldingFeatureInfo(
            isTabletop = true,
            hinges = listOf(halfOpenedHorizontal.copy(isOccluding = true)),
        )

        assertEquals(AppFoldPosture.Flat, info.posture)
        assertTrue(info.hasObstructingHinge)
    }

    @Test
    fun `book posture is unchanged`() {
        val vertical = AppHingeFeature(
            orientation = AppHingeOrientation.Vertical,
            bounds = IntRect(900, 0, 900, 1800),
            isSeparating = true,
            isOccluding = false,
            isFlat = false,
        )
        val info = resolveLayoutFoldingFeatureInfo(isTabletop = false, hinges = listOf(vertical))

        assertEquals(AppFoldPosture.Book, info.posture)
        assertEquals(listOf(vertical), info.hinges)
    }
}

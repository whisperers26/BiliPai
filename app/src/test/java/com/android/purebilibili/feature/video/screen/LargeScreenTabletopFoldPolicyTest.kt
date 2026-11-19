package com.android.purebilibili.feature.video.screen

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.util.AppFoldPosture
import com.android.purebilibili.core.util.AppFoldingFeatureInfo
import com.android.purebilibili.core.util.AppHingeOrientation
import com.android.purebilibili.core.util.AppWindowAdaptiveInfo
import com.android.purebilibili.core.util.WindowHeightSizeClass
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.core.util.WindowWidthSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LargeScreenTabletopFoldPolicyTest {

    private val windowSizeClass = WindowSizeClass(
        widthSizeClass = WindowWidthSizeClass.Expanded,
        heightSizeClass = WindowHeightSizeClass.Medium,
        widthDp = 840.dp,
        heightDp = 700.dp,
    )

    private fun info(posture: AppFoldPosture, orientation: AppHingeOrientation, bounds: IntRect?) =
        AppWindowAdaptiveInfo(
            windowSizeClass = windowSizeClass,
            foldingFeature = AppFoldingFeatureInfo(
                posture = posture,
                hingeOrientation = orientation,
                hingeBounds = bounds,
                isSeparating = true,
            ),
        )

    @Test
    fun `tabletop fold splits the window at the hinge`() {
        assertEquals(
            LargeScreenTabletopFold(topPx = 900, bottomPx = 920),
            resolveLargeScreenTabletopFold(
                info(AppFoldPosture.Tabletop, AppHingeOrientation.Horizontal, IntRect(0, 900, 1800, 920)),
            ),
        )
    }

    @Test
    fun `book and flat postures keep the unfolded layout untouched`() {
        assertNull(
            resolveLargeScreenTabletopFold(
                info(AppFoldPosture.Book, AppHingeOrientation.Vertical, IntRect(900, 0, 900, 1800)),
            ),
        )
        assertNull(
            resolveLargeScreenTabletopFold(
                info(AppFoldPosture.Flat, AppHingeOrientation.Horizontal, IntRect(0, 900, 1800, 900)),
            ),
        )
        assertNull(resolveLargeScreenTabletopFold(info(AppFoldPosture.Tabletop, AppHingeOrientation.Horizontal, null)))
    }

    @Test
    fun `player fills the area above the fold at 16 by 9`() {
        assertEquals(560f, resolveLargeScreenTabletopPlayerWidthDp(areaWidthDp = 840f, areaHeightDp = 315f))
        assertEquals(840f, resolveLargeScreenTabletopPlayerWidthDp(areaWidthDp = 840f, areaHeightDp = 600f))
    }

    @Test
    fun `fullscreen while half folded stays above the fold and follows fold changes`() {
        val fold = LargeScreenTabletopFold(topPx = 900, bottomPx = 920)
        assertTrue(shouldUseTabletopHalfFullscreen(true, fold, isInPipMode = false, isPortraitFullscreen = false))
        // Unfolding a half-fold fullscreen (no fold) returns to whole-window fullscreen.
        assertFalse(shouldUseTabletopHalfFullscreen(true, null, isInPipMode = false, isPortraitFullscreen = false))
        assertFalse(shouldUseTabletopHalfFullscreen(false, fold, isInPipMode = false, isPortraitFullscreen = false))
        assertFalse(shouldUseTabletopHalfFullscreen(true, fold, isInPipMode = true, isPortraitFullscreen = false))
        assertFalse(shouldUseTabletopHalfFullscreen(true, fold, isInPipMode = false, isPortraitFullscreen = true))
    }
}

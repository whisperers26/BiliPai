package com.android.purebilibili.feature.video.screen

import com.android.purebilibili.core.store.TabletSecondaryDefaultTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LargeScreenVideoLayoutPolicyTest {

    @Test
    fun landscapeLayoutHidesIntroRelatedAndHonorsConfiguredDefaultTab() {
        assertTrue(resolveIncludeRelatedTabInSecondary(LargeScreenVideoLayoutMode.Landscape))
        assertFalse(resolveIncludeRelatedTabInSecondary(LargeScreenVideoLayoutMode.AlmostSquare))
        assertTrue(
            resolveRelatedTabFirstInSecondary(
                LargeScreenVideoLayoutMode.Landscape,
                TabletSecondaryDefaultTab.RELATED,
            ),
        )
        assertFalse(
            resolveRelatedTabFirstInSecondary(
                LargeScreenVideoLayoutMode.Landscape,
                TabletSecondaryDefaultTab.COMMENTS,
            ),
        )
        assertFalse(
            resolveRelatedTabFirstInSecondary(
                LargeScreenVideoLayoutMode.AlmostSquare,
                TabletSecondaryDefaultTab.RELATED,
            ),
        )
        val source = java.io.File(
            "app/src/main/java/com/android/purebilibili/feature/video/screen/LargeScreenVideoLayout.kt"
        ).takeIf { it.exists() } ?: java.io.File(
            "src/main/java/com/android/purebilibili/feature/video/screen/LargeScreenVideoLayout.kt"
        )
        val text = source.readText()
        assertTrue(text.contains("includeRelatedTab = resolveIncludeRelatedTabInSecondary(metrics.mode)"))
        assertTrue(text.contains("defaultTab = secondaryDefaultTab"))
        assertTrue(text.contains("includeOwnerUploadsTab = true"))
        assertTrue(text.contains("showRelatedVideos = false"))
        assertTrue(text.contains("LargeScreenVideoLayoutMode.AlmostSquare"))
        assertFalse(text.contains("fixedTab = TabletSecondaryTab.COLLECTION"))
    }

    @Test
    fun collectionGetsOwnColumnOnlyWhenEachPaneIsAtLeast280dp() {
        assertFalse(
            shouldUseDedicatedCollectionColumn(
                availableWidthDp = 800f,
                hasCollection = true,
            )
        )
        assertTrue(
            shouldUseDedicatedCollectionColumn(
                availableWidthDp = 960f,
                hasCollection = true,
            )
        )
        assertFalse(
            shouldUseDedicatedCollectionColumn(
                availableWidthDp = 1280f,
                hasCollection = false,
            )
        )
    }

    @Test
    fun landscapeTabletUsesLeftPlayerAndClampedSidePane() {
        val metrics = resolveLargeScreenVideoMetrics(
            windowWidthDp = 1280f,
            windowHeightDp = 800f,
            isVerticalVideo = false,
        )
        assertEquals(LargeScreenVideoLayoutMode.Landscape, metrics.mode)
        assertTrue(metrics.introBelowPlayer)
        assertTrue(metrics.sidePaneWidthDp in 280f..425f)
        assertEquals(1280f - metrics.sidePaneWidthDp, metrics.playerWidthDp, 0.5f)
        assertEquals(metrics.playerWidthDp / (16f / 9f), metrics.playerHeightDp, 1f)
    }

    @Test
    fun verticalVideoInLandscapeUsesThreePanes() {
        val metrics = resolveLargeScreenVideoMetrics(
            windowWidthDp = 1280f,
            windowHeightDp = 800f,
            isVerticalVideo = true,
            enableVerticalExpand = true,
        )
        assertEquals(LargeScreenVideoLayoutMode.VerticalThreePane, metrics.mode)
        assertFalse(metrics.introBelowPlayer)
        assertEquals(800f / (16f / 9f), metrics.playerWidthDp, 1f)
        assertEquals((1280f - metrics.playerWidthDp) / 2f, metrics.sidePaneWidthDp, 1f)
    }

    @Test
    fun compactPortraitStaysOnPhoneLayout() {
        assertFalse(
            shouldUseLargeScreenVideoLayout(
                windowWidthDp = 393f,
                windowHeightDp = 851f,
                horizontalAdaptationEnabled = true,
            )
        )
        val metrics = resolveLargeScreenVideoMetrics(
            windowWidthDp = 393f,
            windowHeightDp = 851f,
            isVerticalVideo = false,
        )
        assertEquals(LargeScreenVideoLayoutMode.Phone, metrics.mode)
    }

    @Test
    fun disabledHorizontalAdaptationNeverEntersLargeScreenLayout() {
        assertFalse(
            shouldUseLargeScreenVideoLayout(
                windowWidthDp = 1280f,
                windowHeightDp = 800f,
                horizontalAdaptationEnabled = false,
            )
        )
    }

    @Test
    fun verticalVideoDefaultsToLandscapeWithoutExpand() {
        val metrics = resolveLargeScreenVideoMetrics(
            windowWidthDp = 1280f,
            windowHeightDp = 800f,
            isVerticalVideo = true,
        )
        assertEquals(LargeScreenVideoLayoutMode.Landscape, metrics.mode)
    }

    @Test
    fun landscapeSidePaneStaysBetween280And425() {
        val width = resolveLargeScreenLandscapePlayerWidthDp(1280f, 800f)
        val side = 1280f - width
        assertTrue(side in 280f..425f)
    }

    @Test
    fun foldableCoverLandscape_staysOnPhoneLayout() {
        assertFalse(
            shouldUseLargeScreenVideoLayout(
                windowWidthDp = 672f,
                windowHeightDp = 460f,
                horizontalAdaptationEnabled = true,
                isFoldableCoverWindow = true,
            )
        )
    }

    @Test
    fun puraXMaxCoverPortrait_reservesScrollableDetailViewport() {
        // Pura X Max cover display: 1848 x 1264 px. Its landscape-natural cover window is about
        // 672 x 460dp even when the device is held in its normal portrait posture.
        assertEquals(
            230f,
            resolvePhoneInlineVideoViewportHeightDp(
                windowWidthDp = 672f,
                windowHeightDp = 460f,
                isFoldableCoverWindow = true,
            ),
            0.1f,
        )
    }

    @Test
    fun regularPhone_keepsFullWidthSixteenByNinePlayer() {
        assertEquals(
            393f * 9f / 16f,
            resolvePhoneInlineVideoViewportHeightDp(
                windowWidthDp = 393f,
                windowHeightDp = 851f,
                isFoldableCoverWindow = false,
            ),
            0.1f,
        )
    }

    @Test
    fun puraXMaxInnerPortrait_entersAlmostSquareLayout() {
        // Pura X Max inner display: 1828 x 2584 px, aspect ratio ~ 0.7074
        // With density ~ 2.75, width ~ 665dp, height ~ 940dp
        assertTrue(
            shouldUseLargeScreenVideoLayout(
                windowWidthDp = 665f,
                windowHeightDp = 940f,
                horizontalAdaptationEnabled = true,
                isFoldableCoverWindow = false,
            )
        )
        val metrics = resolveLargeScreenVideoMetrics(
            windowWidthDp = 665f,
            windowHeightDp = 940f,
            isVerticalVideo = false,
        )
        assertEquals(LargeScreenVideoLayoutMode.AlmostSquare, metrics.mode)
        assertEquals(665f * 9f / 16f, metrics.playerHeightDp, 1f)
    }

    @Test
    fun almostSquarePlayerSitsBelowStatusBarWhileOtherModesPadTheSidePane() {
        assertTrue(shouldReserveStatusBarAbovePlayer(LargeScreenVideoLayoutMode.AlmostSquare))
        assertFalse(shouldReserveStatusBarAbovePlayer(LargeScreenVideoLayoutMode.Landscape))
        assertFalse(shouldReserveStatusBarAbovePlayer(LargeScreenVideoLayoutMode.Split))
        val source = java.io.File(
            "app/src/main/java/com/android/purebilibili/feature/video/screen/LargeScreenVideoLayout.kt"
        ).takeIf { it.exists() } ?: java.io.File(
            "src/main/java/com/android/purebilibili/feature/video/screen/LargeScreenVideoLayout.kt"
        )
        assertTrue(source.readText().contains("windowInsetsTopHeight(WindowInsets.statusBars)"))
    }

    @Test
    fun onlyAlmostSquareGivesTheDetailAreaARailThatOpensOnInfo() {
        assertTrue(shouldUseLargeScreenDetailRail(LargeScreenVideoLayoutMode.AlmostSquare))
        listOf(
            LargeScreenVideoLayoutMode.Phone,
            LargeScreenVideoLayoutMode.Landscape,
            LargeScreenVideoLayoutMode.Split,
            LargeScreenVideoLayoutMode.VerticalThreePane,
        ).forEach { mode -> assertFalse(shouldUseLargeScreenDetailRail(mode)) }
        assertEquals(LargeScreenDetailRailPage.INFO, DEFAULT_LARGE_SCREEN_DETAIL_RAIL_PAGE)
        assertEquals(
            listOf(LargeScreenDetailRailPage.INFO, LargeScreenDetailRailPage.COMMENTS),
            LargeScreenDetailRailPage.entries.toList(),
        )
        // Each rail row of action buttons keeps 48dp touch targets inside the rail's 4dp padding.
        assertTrue(
            LARGE_SCREEN_DETAIL_RAIL_WIDTH_DP - 8f >= 48f * LARGE_SCREEN_DETAIL_RAIL_ACTION_COLUMNS,
        )
    }

    @Test
    fun galaxyFoldInnerRailInfoPageFitsTitleOwnerRowOverHorizontalRelatedCards() {
        // 752dp window minus the rail and its 1dp divider.
        val infoPageWidthDp = 752f - LARGE_SCREEN_DETAIL_RAIL_WIDTH_DP - 1f
        // The title keeps more of the header row than the owner block beside it.
        assertTrue(infoPageWidthDp - VIDEO_INFO_HEADER_OWNER_WIDTH_DP > VIDEO_INFO_HEADER_OWNER_WIDTH_DP)
        // Recommendations span the page, minus the list's 8dp and the card row's 6dp side padding.
        assertTrue(
            infoPageWidthDp - 16f - 12f >=
                com.android.purebilibili.feature.video.ui.components.RELATED_VIDEO_HORIZONTAL_MIN_WIDTH_DP,
        )
        assertTrue(LARGE_SCREEN_DETAIL_RAIL_HEADER_MAX_HEIGHT_FRACTION in 0.3f..0.6f)
    }

    @Test
    fun galaxyFoldInnerPortrait_playerFillsFullWidthBeforeDetailPanes() {
        // Galaxy Z Fold inner display: 2256 x 2504 px at density 3.0 -> 752 x 835dp.
        val metrics = resolveLargeScreenVideoMetrics(
            windowWidthDp = 752f,
            windowHeightDp = 835f,
            isVerticalVideo = false,
        )
        assertEquals(LargeScreenVideoLayoutMode.AlmostSquare, metrics.mode)
        assertEquals(752f, metrics.playerWidthDp, 0f)
        assertEquals(752f * 9f / 16f, metrics.playerHeightDp, 1f)
    }
}

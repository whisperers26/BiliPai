package com.android.purebilibili.core.theme

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppFontWeightPolicyTest {

    private val allSlots = listOf(
        Md3Typography::displayLarge,
        Md3Typography::displayMedium,
        Md3Typography::displaySmall,
        Md3Typography::headlineLarge,
        Md3Typography::headlineMedium,
        Md3Typography::headlineSmall,
        Md3Typography::titleLarge,
        Md3Typography::titleMedium,
        Md3Typography::titleSmall,
        Md3Typography::bodyLarge,
        Md3Typography::bodyMedium,
        Md3Typography::bodySmall,
        Md3Typography::labelLarge,
        Md3Typography::labelMedium,
        Md3Typography::labelSmall,
    )

    @Test
    fun followTheme_keepsBaselineWeights() {
        val typography = Md3Typography.withFontWeight(AppFontWeightPreset.FOLLOW_THEME.fontWeight)

        assertEquals(Md3Typography.bodyLarge.fontWeight, typography.bodyLarge.fontWeight)
        assertEquals(Md3Typography.titleMedium.fontWeight, typography.titleMedium.fontWeight)
        assertEquals(Md3Typography.labelSmall.fontWeight, typography.labelSmall.fontWeight)
    }

    @Test
    fun weightPreset_overridesEveryRole() {
        val typography = Md3Typography.withFontWeight(AppFontWeightPreset.SEMI_BOLD.fontWeight)

        allSlots.forEach { slot ->
            assertEquals(FontWeight.SemiBold, slot.get(typography).fontWeight)
        }
    }

    @Test
    fun nullWeight_returnsUnchangedStyles() {
        allSlots.forEach { slot ->
            assertEquals(slot.get(Md3Typography), slot.get(Md3Typography.withFontWeight(null)))
        }
    }

    @Test
    fun presets_exposeMediumAndAboveOnly() {
        // 中文系统字体对细字重支持不可靠,档位必须都在 Medium 及以上。
        AppFontWeightPreset.entries.forEach { preset ->
            val weight = preset.fontWeight
            if (preset == AppFontWeightPreset.FOLLOW_THEME) {
                assertNull(weight)
            } else {
                assertTrue(weight.weight >= FontWeight.Medium.weight, preset.name)
            }
        }
    }

    @Test
    fun fromValue_fallsBackToFollowTheme() {
        assertSame(AppFontWeightPreset.FOLLOW_THEME, AppFontWeightPreset.fromValue(-1))
        assertSame(AppFontWeightPreset.MEDIUM, AppFontWeightPreset.fromValue(0))
        assertSame(AppFontWeightPreset.SEMI_BOLD, AppFontWeightPreset.fromValue(1))
        assertSame(AppFontWeightPreset.BOLD, AppFontWeightPreset.fromValue(2))
        assertSame(AppFontWeightPreset.FOLLOW_THEME, AppFontWeightPreset.fromValue(99))
    }
}

package com.android.purebilibili.core.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MD3 中文适配字阶的策略约束（依据 Material Design 3 typography 规范）：
 * - CJK 方块字不加字距：全部槽位 letterSpacing == 0；
 * - 行高统一走 Center/None，避免固定高度组件内中文偏移或裁切；
 * - 正文与标签行高不低于 1.4 倍（labelSmall 为徽章紧凑场景的显式例外）；
 * - 密集小字下限：bodySmall >= 13sp、labelSmall >= 12sp。
 */
class Md3TypographyPolicyTest {

    private val allRoles = listOf(
        Md3Typography.displayLarge,
        Md3Typography.displayMedium,
        Md3Typography.displaySmall,
        Md3Typography.headlineLarge,
        Md3Typography.headlineMedium,
        Md3Typography.headlineSmall,
        Md3Typography.titleLarge,
        Md3Typography.titleMedium,
        Md3Typography.titleSmall,
        Md3Typography.bodyLarge,
        Md3Typography.bodyMedium,
        Md3Typography.bodySmall,
        Md3Typography.labelLarge,
        Md3Typography.labelMedium,
        Md3Typography.labelSmall,
    )

    @Test
    fun allRoles_useZeroLetterSpacingForCjk() {
        allRoles.forEach { style ->
            assertEquals(0.sp, style.letterSpacing, "letterSpacing must be 0 for CJK")
        }
    }

    @Test
    fun allRoles_useCenterAlignmentWithoutTrim() {
        allRoles.forEach { style ->
            val lineHeightStyle = assertNotNull(style.lineHeightStyle)
            assertEquals(LineHeightStyle.Alignment.Center, lineHeightStyle.alignment)
            assertEquals(LineHeightStyle.Trim.None, lineHeightStyle.trim)
        }
    }

    @Test
    fun readingRoles_meetMinimumCjkSizes() {
        assertTrue(Md3Typography.bodySmall.fontSize >= 13.sp)
        assertTrue(Md3Typography.labelSmall.fontSize >= 12.sp)
    }

    @Test
    fun bodyAndLabelRoles_meetReadingLineHeightRatio() {
        val readingRoles = listOf(
            Md3Typography.bodyLarge,
            Md3Typography.bodyMedium,
            Md3Typography.bodySmall,
            Md3Typography.labelLarge,
            Md3Typography.labelMedium,
            Md3Typography.titleMedium,
            Md3Typography.titleSmall,
        )
        readingRoles.forEach { style ->
            val ratio = style.lineHeight.value / style.fontSize.value
            assertTrue(ratio >= 1.4f, "lineHeight ratio ${ratio} too tight for CJK reading")
        }
    }

    @Test
    fun labelSmall_keepsCompactBadgeLineHeight() {
        // 徽章/固定槽位场景显式保留 1.33 倍紧凑行高。
        assertEquals(16.sp, Md3Typography.labelSmall.lineHeight)
    }

    @Test
    fun tunedRoles_keepBaselineSizesAndWeights() {
        assertEquals(16.sp, Md3Typography.bodyLarge.fontSize)
        assertEquals(14.sp, Md3Typography.bodyMedium.fontSize)
        assertEquals(21.sp, Md3Typography.bodyMedium.lineHeight)
        assertEquals(13.sp, Md3Typography.bodySmall.fontSize)
        assertEquals(19.sp, Md3Typography.bodySmall.lineHeight)
        assertEquals(12.sp, Md3Typography.labelSmall.fontSize)
        assertEquals(17.sp, Md3Typography.labelMedium.lineHeight)
        assertEquals(FontWeight.Normal, Md3Typography.bodyLarge.fontWeight)
        assertEquals(FontWeight.Medium, Md3Typography.titleMedium.fontWeight)
        assertEquals(FontWeight.Medium, Md3Typography.labelLarge.fontWeight)
    }

    @Test
    fun miuixTypography_staysUntouched() {
        // MIUIX 路径不随 MD3 中文适配变化。
        assertEquals(17.sp, BiliMiuixTypography.bodyLarge.fontSize)
        assertEquals(14.sp, BiliMiuixTypography.bodySmall.fontSize)
    }
}

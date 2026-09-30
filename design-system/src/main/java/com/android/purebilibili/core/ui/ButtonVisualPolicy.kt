package com.android.purebilibili.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle

/** 依据 [ColorScheme.surface] 亮度判断当前是否为深色主题。 */
fun isColorSchemeDark(colorScheme: ColorScheme): Boolean =
    colorScheme.surface.luminance() < 0.5f

/**
 * 填充按钮(Filled Button)容器色:深色主题用 primary(标准强调),
 * 浅色主题用 primaryContainer,避免浅色下深种子色 primary 按钮过重。
 */
fun resolveFilledButtonContainerColor(colorScheme: ColorScheme): Color =
    if (isColorSchemeDark(colorScheme)) colorScheme.primary else colorScheme.primaryContainer

/** 填充按钮内容色,与 [resolveFilledButtonContainerColor] 配对。 */
fun resolveFilledButtonContentColor(colorScheme: ColorScheme): Color =
    if (isColorSchemeDark(colorScheme)) colorScheme.onPrimary else colorScheme.onPrimaryContainer

/** MD3 模式按钮的紧凑内容内边距(对齐 PiliPlus 的 visualDensity(-2, -1.25) 观感)。 */
val AppCompactButtonContentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)

/**
 * 解析 AppButton 的实际内容内边距:MD3 模式下调用方未显式定制(仍为 Material 默认值)时
 * 收紧为紧凑密度;MIUIX 模式由 AppMiuixButton 的 insideMargin 管线控制,原样透传。
 */
fun resolveAppButtonContentPadding(
    uiStyle: AppUiStyle,
    requested: PaddingValues,
    defaultPadding: PaddingValues,
): PaddingValues = when (uiStyle) {
    AppUiStyle.MIUIX -> requested
    AppUiStyle.MATERIAL3 -> if (requested == defaultPadding) AppCompactButtonContentPadding else requested
}

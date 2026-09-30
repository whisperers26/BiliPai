package com.android.purebilibili.core.ui.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.ui.AppPopupSurfaceRenderer
import com.android.purebilibili.core.ui.AppPopupSurfaceType

object BiliPaiPopupSurfaceRenderer : AppPopupSurfaceRenderer {
    @Composable
    override fun Render(
        type: AppPopupSurfaceType,
        modifier: Modifier,
        shape: Shape,
        containerColor: Color,
        contentColor: Color,
        tonalElevation: Dp,
        content: @Composable () -> Unit,
    ) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            content = content,
        )
    }
}

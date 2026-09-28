package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import com.android.purebilibili.core.ui.components.AppText

/** 双击跳转反馈占据的侧边宽度，与 [resolveFullscreenDoubleTapAction] 的左右 30% 触发区一致 */
private const val SEEK_FEEDBACK_SIDE_WIDTH_FRACTION = 0.3f

/**
 * 双击快进/后退的 ±N 提示：白字无背景，显示在点击的那一侧。
 */
@Composable
internal fun BoxScope.SeekFeedbackText(
    visible: Boolean,
    text: String,
    forward: Boolean
) {
    Box(
        modifier = Modifier
            .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(SEEK_FEEDBACK_SIDE_WIDTH_FRACTION),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            AppText(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.4f),
                        offset = Offset(1f, 1f),
                        blurRadius = 6f
                    )
                )
            )
        }
    }
}

package com.android.purebilibili.core.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

@Composable
internal fun HingeSafeOverlayHost(
    regionProvider: (IntSize, IntOffset) -> List<IntRect>,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        // A full-window Dialog has no platform "outside" area; handle taps behind the surface.
        if (onDismissRequest != null) {
            Box(Modifier.matchParentSize().pointerInput(onDismissRequest) {
                detectTapGestures { onDismissRequest() }
            })
        }
        WindowRegionLayout(
            modifier = Modifier.fillMaxSize(),
            regionProvider = regionProvider,
            primaryContent = {
                Box(Modifier.fillMaxSize(), contentAlignment = alignment, content = content)
            },
        )
    }
}

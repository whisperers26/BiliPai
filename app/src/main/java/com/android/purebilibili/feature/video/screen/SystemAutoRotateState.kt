package com.android.purebilibili.feature.video.screen

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private fun ContentResolver.readSystemAutoRotateEnabled(): Boolean =
    Settings.System.getInt(this, Settings.System.ACCELEROMETER_ROTATION, 0) != 0

/**
 * Tracks the system auto-rotate switch (the quick-settings rotation lock).
 * `true` means the device is allowed to rotate; `false` means rotation is locked.
 */
@Composable
internal fun rememberSystemAutoRotateEnabled(): State<Boolean> {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val state: MutableState<Boolean> = remember(resolver) {
        mutableStateOf(resolver.readSystemAutoRotateEnabled())
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = resolver.readSystemAutoRotateEnabled()
            }
        }
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer
        )
        observer.onChange(false)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state
}

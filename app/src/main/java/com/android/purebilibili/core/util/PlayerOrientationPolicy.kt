package com.android.purebilibili.core.util

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.WeakHashMap

internal data class PlayerWindowOrientationPolicy(
    val currentWindowWidthDp: Int,
    val currentWindowHeightDp: Int,
    val maximumWindowWidthDp: Int?,
    val maximumWindowHeightDp: Int?,
    val displayModeWidthPx: Int?,
    val displayModeHeightPx: Int?,
    val displayRotation: Int?,
    val isFoldableCoverWindow: Boolean,
    val isLandscapeNaturalDisplay: Boolean,
) {
    val usesInWindowFullscreen: Boolean
        get() = isFoldableCoverWindow && isLandscapeNaturalDisplay
}

internal fun AppDisplayContext.toPlayerWindowOrientationPolicy(): PlayerWindowOrientationPolicy {
    return PlayerWindowOrientationPolicy(
        currentWindowWidthDp = currentWindowWidthDp,
        currentWindowHeightDp = currentWindowHeightDp,
        maximumWindowWidthDp = maximumWindowWidthDp,
        maximumWindowHeightDp = maximumWindowHeightDp,
        displayModeWidthPx = displayModeWidthPx,
        displayModeHeightPx = displayModeHeightPx,
        displayRotation = displayRotation,
        isFoldableCoverWindow = isFoldableCoverWindow,
        isLandscapeNaturalDisplay =
            naturalOrientation == AppDisplayNaturalOrientation.Landscape,
    )
}

internal fun resolvePlayerWindowOrientationPolicy(
    displayContext: AppDisplayContext,
): PlayerWindowOrientationPolicy = displayContext.toPlayerWindowOrientationPolicy()

internal fun isFoldableCoverWindow(
    smallestScreenWidthDp: Int,
    currentWindowWidthDp: Int?,
    currentWindowHeightDp: Int?,
    maximumWidthDp: Int? = null,
    maximumHeightDp: Int? = null,
): Boolean {
    val isDeviceLargeScreen = smallestScreenWidthDp >= LARGE_SCREEN_SMALLEST_WIDTH_DP ||
        (maximumWidthDp != null && maximumHeightDp != null &&
            minOf(maximumWidthDp, maximumHeightDp) >= LARGE_SCREEN_SMALLEST_WIDTH_DP)
    return isDeviceLargeScreen &&
        currentWindowWidthDp != null && currentWindowHeightDp != null &&
        minOf(currentWindowWidthDp, currentWindowHeightDp) < LARGE_SCREEN_SMALLEST_WIDTH_DP
}

@Suppress("UNUSED_PARAMETER")
internal fun shouldRequestPhysicalPlayerOrientation(
    smallestScreenWidthDp: Int,
    currentWindowWidthDp: Int? = null,
    currentWindowHeightDp: Int? = null,
    maximumWidthDp: Int? = null,
    maximumHeightDp: Int? = null,
    configurationOrientation: Int? = null,
    displayRotation: Int? = null,
    displayModeWidthPx: Int? = null,
    displayModeHeightPx: Int? = null,
    platformIgnoresLargeScreenOrientationRequests: Boolean =
        Build.VERSION.SDK_INT >= 36,
): Boolean {
    // Preserve the user's intent on ordinary phones and large screens. A landscape-natural
    // foldable cover is the exception: its fullscreen is an in-window presentation, and axis
    // requests can create portrait letterboxing or an orientation loop on vendor ROMs.
    val isCoverWindow = isFoldableCoverWindow(
        smallestScreenWidthDp = smallestScreenWidthDp,
        currentWindowWidthDp = currentWindowWidthDp,
        currentWindowHeightDp = currentWindowHeightDp,
        maximumWidthDp = maximumWidthDp,
        maximumHeightDp = maximumHeightDp,
    )
    val isLandscapeNaturalCover = isCoverWindow &&
        configurationOrientation != null &&
        isLandscapeNaturalDisplay(
            configurationOrientation = configurationOrientation,
            displayRotation = displayRotation,
            displayModeWidthPx = displayModeWidthPx,
            displayModeHeightPx = displayModeHeightPx,
        )
    return !isLandscapeNaturalCover
}

internal fun resolvePlayerWindowOrientationPolicy(
    smallestScreenWidthDp: Int,
    currentWindowWidthDp: Int,
    currentWindowHeightDp: Int,
    maximumWidthDp: Int?,
    maximumHeightDp: Int?,
    configurationOrientation: Int,
    displayRotation: Int?,
    displayModeWidthPx: Int? = null,
    displayModeHeightPx: Int? = null,
    isKnownFoldableCoverWindow: Boolean = false,
): PlayerWindowOrientationPolicy {
    val isCoverWindow = isKnownFoldableCoverWindow || isFoldableCoverWindow(
        smallestScreenWidthDp = smallestScreenWidthDp,
        currentWindowWidthDp = currentWindowWidthDp,
        currentWindowHeightDp = currentWindowHeightDp,
        maximumWidthDp = maximumWidthDp,
        maximumHeightDp = maximumHeightDp,
    )
    return PlayerWindowOrientationPolicy(
        currentWindowWidthDp = currentWindowWidthDp,
        currentWindowHeightDp = currentWindowHeightDp,
        maximumWindowWidthDp = maximumWidthDp,
        maximumWindowHeightDp = maximumHeightDp,
        displayModeWidthPx = displayModeWidthPx,
        displayModeHeightPx = displayModeHeightPx,
        displayRotation = displayRotation,
        isFoldableCoverWindow = isCoverWindow,
        isLandscapeNaturalDisplay = isLandscapeNaturalDisplay(
            configurationOrientation = configurationOrientation,
            displayRotation = displayRotation,
            displayModeWidthPx = displayModeWidthPx,
            displayModeHeightPx = displayModeHeightPx,
        ),
    )
}

@Suppress("DEPRECATION")
internal fun Activity.resolvePlayerWindowOrientationPolicy(
    displayContext: AppDisplayContext? = null,
): PlayerWindowOrientationPolicy {
    // Do not call resolvePlayerWindowOrientationPolicy(...) from this Activity extension.
    // Kotlin would re-select this same overload via the implicit Activity receiver and
    // overflow the stack when opening video detail.
    return (displayContext ?: resolveAppDisplayContext()).toPlayerWindowOrientationPolicy()
}

private fun isPlayerAxisOrientationRequest(requestedOrientation: Int): Boolean {
    return requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
        requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED &&
        requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_BEHIND
}

internal fun resolveEffectivePlayerRequestedOrientation(
    requestedOrientation: Int,
    usesInWindowFullscreen: Boolean,
): Int {
    return if (usesInWindowFullscreen && isPlayerAxisOrientationRequest(requestedOrientation)) {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    } else {
        requestedOrientation
    }
}

/**
 * Sensor orientation modes follow gravity even under the system rotation lock. While locked they
 * become a fixed request, keeping the current landscape side. Explicit requests pass through, so
 * the fullscreen button still works.
 */
internal fun resolveSystemLockAwareOrientation(
    requestedOrientation: Int,
    systemAutoRotateEnabled: Boolean,
    currentRequestedOrientation: Int,
): Int {
    if (systemAutoRotateEnabled) return requestedOrientation
    return when (requestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> when (currentRequestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> currentRequestedOrientation
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else -> requestedOrientation
    }
}

private fun Activity.isSystemAutoRotateEnabled(): Boolean =
    Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0

/** What each activity last asked for, before the rotation lock was applied. Main thread only. */
private val desiredPlayerOrientations = WeakHashMap<Activity, Int>()

/**
 * Re-applies each activity's last request when the system rotation lock changes, so a sensor mode
 * set earlier stops following gravity once locked and resumes once unlocked.
 */
internal fun Application.installSystemRotationLockGuard() {
    contentResolver.registerContentObserver(
        Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
        false,
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                desiredPlayerOrientations.entries.toList().forEach { (activity, desired) ->
                    if (!activity.isFinishing && !activity.isDestroyed &&
                        !activity.isInPictureInPictureMode
                    ) {
                        activity.applyPlayerRequestedOrientation(desired)
                    }
                }
            }
        },
    )
}

/**
 * Android 16+ ignores orientation restrictions for target-36+ apps on large screens,
 * and target 37 removes the manifest opt-out. Older Android releases still honor
 * requestedOrientation on tablets, so do not discard a user's fullscreen request there.
 */
internal fun Activity.applyPlayerRequestedOrientation(
    requestedOrientation: Int,
    displayContext: AppDisplayContext? = null,
): Boolean {
    desiredPlayerOrientations[this] = requestedOrientation
    val policy = (displayContext ?: resolveAppDisplayContext()).toPlayerWindowOrientationPolicy()
    val effectiveOrientation = resolveEffectivePlayerRequestedOrientation(
        requestedOrientation = requestedOrientation,
        usesInWindowFullscreen = policy.usesInWindowFullscreen,
    )
    if (effectiveOrientation != requestedOrientation) {
        Logger.d(
            "PlayerOrientationPolicy",
            "Suppress axis request=$requestedOrientation on landscape-natural cover: " +
                "current=${policy.currentWindowWidthDp}x${policy.currentWindowHeightDp}dp, " +
                "maximum=${policy.maximumWindowWidthDp}x${policy.maximumWindowHeightDp}dp, " +
                "mode=${policy.displayModeWidthPx}x${policy.displayModeHeightPx}px, " +
                "rotation=${policy.displayRotation}"
        )
    }
    val lockAwareOrientation = resolveSystemLockAwareOrientation(
        requestedOrientation = effectiveOrientation,
        systemAutoRotateEnabled = isSystemAutoRotateEnabled(),
        currentRequestedOrientation = this.requestedOrientation,
    )
    if (this.requestedOrientation == lockAwareOrientation) return false
    this.requestedOrientation = lockAwareOrientation
    return true
}

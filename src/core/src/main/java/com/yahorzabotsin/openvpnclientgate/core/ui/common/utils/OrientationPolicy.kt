package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

import android.content.Context
import android.content.pm.ActivityInfo

/**
 * App-wide screen orientation policy (US-22): phone stays portrait-only, tablet follows the
 * device's natural sensor orientation and the user's OS-level rotation-lock setting (unchanged
 * from today's behavior), and TV stays landscape-only. Mirrors [TvUtils]'s
 * pure-function-plus-thin-wiring style: [isTablet] resolves device class off [Context], while
 * [resolveRequestedOrientation] is a pure function that is trivially unit-testable without
 * Robolectric resource resolution.
 */
object OrientationPolicy {
    private const val TABLET_SMALLEST_WIDTH_DP = 600

    fun isTablet(context: Context): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP
    }

    fun resolveRequestedOrientation(isTvDevice: Boolean, isTablet: Boolean): Int {
        return when {
            isTvDevice -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            isTablet -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

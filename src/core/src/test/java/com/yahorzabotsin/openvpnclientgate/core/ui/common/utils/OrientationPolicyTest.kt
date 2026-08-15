package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationPolicyTest {
    @Test
    fun `resolveRequestedOrientation locks phone to portrait`() {
        val orientation = OrientationPolicy.resolveRequestedOrientation(isTvDevice = false, isTablet = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
    }

    @Test
    fun `resolveRequestedOrientation leaves tablet unspecified`() {
        val orientation = OrientationPolicy.resolveRequestedOrientation(isTvDevice = false, isTablet = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, orientation)
    }

    @Test
    fun `resolveRequestedOrientation locks tv to landscape`() {
        val orientation = OrientationPolicy.resolveRequestedOrientation(isTvDevice = true, isTablet = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, orientation)
    }

    @Test
    fun `resolveRequestedOrientation prioritizes tv over tablet`() {
        // A TV device is never also classified as a tablet in practice, but TV must win if
        // isTablet is ever true for a TV UI mode (e.g. a Google TV with a large screen).
        val orientation = OrientationPolicy.resolveRequestedOrientation(isTvDevice = true, isTablet = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, orientation)
    }
}

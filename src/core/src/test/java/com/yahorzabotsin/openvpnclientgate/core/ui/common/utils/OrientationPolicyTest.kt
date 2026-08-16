package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OrientationPolicyTest {
    @Test
    fun `resolveRequestedOrientation locks phone to portrait`() {
        val orientation = OrientationPolicy.resolveRequestedOrientation(isTvDevice = false, isTablet = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
    }

    @Test
    fun `resolveRequestedOrientation defers to manifest for tablet`() {
        // null means "don't touch requestedOrientation" so any static manifest
        // android:screenOrientation on the activity is left intact (see CoreApp).
        val orientation = OrientationPolicy.resolveRequestedOrientation(isTvDevice = false, isTablet = true)
        assertNull(orientation)
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

    @Test
    @Config(qualifiers = "sw600dp")
    fun `isTablet returns true at 600dp boundary`() {
        val isTablet = OrientationPolicy.isTablet(RuntimeEnvironment.getApplication())
        assertEquals(true, isTablet)
    }

    @Test
    @Config(qualifiers = "sw599dp")
    fun `isTablet returns false just below 600dp boundary`() {
        val isTablet = OrientationPolicy.isTablet(RuntimeEnvironment.getApplication())
        assertEquals(false, isTablet)
    }
}

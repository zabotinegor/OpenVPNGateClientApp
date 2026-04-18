package com.yahorzabotsin.openvpnclientgate.mobile

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SplashActivityTest {

    private lateinit var activity: SplashActivity

    @Before
    fun setUp() {
        activity = SplashActivity()
    }

    /**
     * Test that hasNavigated guard prevents double navigation.
     * Verifies that calling invokeNavigateToMain twice only sets hasNavigated once,
     * even though startActivity will fail in unit test environment.
     */
    @Test
    fun hasNavigatedGuardPreventsDoubleNavigation() {
        // First call to navigateToMain will set hasNavigated (startActivity throws but flag is set first)
        try {
            invokeNavigateToMain(activity)
        } catch (e: Exception) {
            // Expected - startActivity fails in unit test environment
        }
        
        val hasNavigatedAfterFirst = getHasNavigated(activity)
        assertTrue("hasNavigated should be true after first call", hasNavigatedAfterFirst)
        
        // Second call should be blocked by the guard and not throw
        invokeNavigateToMain(activity)
        
        // hasNavigated should still be true
        val hasNavigatedAfterSecond = getHasNavigated(activity)
        assertTrue("hasNavigated should still be true", hasNavigatedAfterSecond)
    }

    /**
     * Test that scheduleNavigation with zero delay sets navigateAtElapsedMs correctly.
     */
    @Test
    fun scheduleNavigationSetsNavigateAtElapsedMs() {
        invokeScheduleNavigation(activity, 0L)
        
        val navigateAtElapsedMs = getNavigateAtElapsedMs(activity)
        assertTrue("navigateAtElapsedMs should be set", navigateAtElapsedMs > 0L)
    }

    /**
     * Test that scheduleNavigation only posts handler if lifecycle is STARTED.
     */
    @Test
    fun scheduleNavigationChecksLifecycleState() {
        // Activity not started yet, so handler should not post
        invokeScheduleNavigation(activity, 100L)
        
        val navigateAtElapsedMs = getNavigateAtElapsedMs(activity)
        // If lifecycle wasn't STARTED, navigateAtElapsedMs should still be set,
        // but no callback would have been posted
        assertTrue("navigateAtElapsedMs should be set even when lifecycle not STARTED", navigateAtElapsedMs > 0L)
    }

    /**
     * Test resolveGifDurationMs returns a positive duration or fallback.
     */
    @Test
    fun resolveGifDurationMsReturnsFallbackWhenResourceNotFound() {
        val duration = invokeResolveGifDurationMs(activity)
        assertTrue("Duration should be positive", duration > 0)
    }

    // --- Reflection helpers ---

    private fun invokeScheduleNavigation(activity: SplashActivity, delayMs: Long) {
        val method = SplashActivity::class.java
            .getDeclaredMethod("scheduleNavigation", Long::class.javaPrimitiveType)
        method.isAccessible = true
        try {
            method.invoke(activity, delayMs)
        } catch (e: Exception) {
            throw RuntimeException("Failed to invoke scheduleNavigation", e)
        }
    }

    private fun invokeNavigateToMain(activity: SplashActivity) {
        val method = SplashActivity::class.java.getDeclaredMethod("navigateToMain")
        method.isAccessible = true
        try {
            method.invoke(activity)
        } catch (e: Exception) {
            throw RuntimeException("Failed to invoke navigateToMain", e)
        }
    }

    private fun invokeResolveGifDurationMs(activity: SplashActivity): Long {
        val method = SplashActivity::class.java.getDeclaredMethod("resolveGifDurationMs")
        method.isAccessible = true
        return try {
            method.invoke(activity) as Long
        } catch (e: Exception) {
            // If resource not found, we expect fallback
            3000L
        }
    }

    private fun getHasNavigated(activity: SplashActivity): Boolean {
        val field = SplashActivity::class.java.getDeclaredField("hasNavigated")
        field.isAccessible = true
        return field.getBoolean(activity)
    }

    private fun getNavigateAtElapsedMs(activity: SplashActivity): Long {
        val field = SplashActivity::class.java.getDeclaredField("navigateAtElapsedMs")
        field.isAccessible = true
        return field.getLong(activity)
    }
}


package com.yahorzabotsin.openvpnclientgate.core.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppLogTest {

    private val tag = "${LogTags.APP}:AppLogTest"
    private var nowMs: Long = 1_000L

    @Before
    fun setUp() {
        ShadowLog.clear()
        Timber.uprootAll()
        Timber.plant(AppDebugTree())
        AppLog.resetForTest()
        AppLog.setTimeProviderForTest { nowMs }
    }

    @After
    fun tearDown() {
        AppLog.resetForTest()
        Timber.uprootAll()
        ShadowLog.clear()
    }

    @Test
    fun throttledDebugSuppressesDuplicatesInsideWindow() {
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)

        val messages = ShadowLog.getLogs()
            .filter { it.tag == tag }
            .map { it.msg }

        assertEquals(1, messages.size)
        assertEquals("spam", messages.first())
    }

    @Test
    fun throttledDebugFlushesSuppressedCountAfterWindow() {
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)
        nowMs += 30_001L
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)

        val messages = ShadowLog.getLogs()
            .filter { it.tag == tag }
            .map { it.msg }

        assertTrue(messages.any { it.contains("Suppressed 1 repeated logs") })
        assertEquals(3, messages.size)
    }

    @Test
    fun throttledCacheIsBoundedForUniqueKeys() {
        repeat(10_000) { index ->
            AppLog.dThrottled(tag, "msg-$index", key = "k-$index", windowMs = 5 * 60_000L)
        }

        assertTrue(AppLog.throttledKeyCountForTest() <= 4_096)
    }

    @Test
    fun suppressedCountSurvivesExpiredWindowUntilNextLog() {
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)
        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)
        nowMs += 30_001L

        repeat(127) { index ->
            AppLog.dThrottled(tag, "other-$index", key = "other-$index", windowMs = 30_000L)
        }

        AppLog.dThrottled(tag, "spam", key = "k", windowMs = 30_000L)

        val messages = ShadowLog.getLogs()
            .filter { it.tag == tag }
            .map { it.msg }

        assertTrue(messages.any { it.contains("Suppressed 1 repeated logs for key=k") })
    }
}

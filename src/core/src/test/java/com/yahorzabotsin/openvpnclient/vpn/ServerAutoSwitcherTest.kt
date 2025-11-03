package com.yahorzabotsin.openvpnclient.vpn

import android.os.Looper
import com.yahorzabotsin.openvpnclient.core.servers.Country
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.SignalStrength
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ServerAutoSwitcherTest {
    private val appContext = RuntimeEnvironment.getApplication()
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Unit)? = null
    private var originalStopper: ((android.content.Context) -> Unit)? = null
    private data class Call(val ctx: android.content.Context, val cfg: String, val title: String?, val reconnect: Boolean)
    private val calls = mutableListOf<Call>()
    private var stopCalls = 0

    @Before
    fun setUp() {
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        originalStarter = ServerAutoSwitcher.starter
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect -> calls.add(Call(ctx, config, title, reconnect)) }
        originalStopper = ServerAutoSwitcher.stopper
        ServerAutoSwitcher.stopper = { _ -> stopCalls += 1 }
        val servers = listOf(
            Server("n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server("n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        calls.clear()
        stopCalls = 0
    }

    @After
    fun tearDown() {
        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        ServerAutoSwitcher.resetNoReplyThreshold()
    }

    @Test
    fun switchesAfterThreshold() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, calls.size)
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf2", current?.config)
    }

    @Test
    fun cancelsOnStateChange() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)
        // Cancel before crossing the (test) threshold of 2 seconds
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_START)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)
    }

    @Test
    fun noAlternativeServersDoesNotSwitch() {
        val single = listOf(
            Server("n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", single)
        SelectedCountryStore.resetIndex(appContext)
        ShadowLog.clear()

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)

        val hadNoAltLog = ShadowLog.getLogs().any { it.tag == "ServerAutoSwitcher" && it.msg.contains("no alternative servers available") }
        assertEquals(true, hadNoAltLog)
        assertEquals(1, stopCalls)
    }
}

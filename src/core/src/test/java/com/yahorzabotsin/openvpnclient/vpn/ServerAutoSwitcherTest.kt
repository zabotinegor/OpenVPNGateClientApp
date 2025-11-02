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
    private var originalStarter: ((android.content.Context, String, String?) -> Unit)? = null
    private val calls = mutableListOf<Triple<android.content.Context, String, String?>>()

    @Before
    fun setUp() {
        originalStarter = ServerAutoSwitcher.starter
        ServerAutoSwitcher.starter = { ctx, config, title -> calls.add(Triple(ctx, config, title)) }
        val servers = listOf(
            Server("n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server("n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        calls.clear()
    }

    @After
    fun tearDown() {
        originalStarter?.let { ServerAutoSwitcher.starter = it }
    }

    @Test
    fun switchesAfterThreshold() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, calls.size)
        assertEquals("conf2", calls.first().second)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf2", current?.config)
    }

    @Test
    fun cancelsOnStateChange() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_START)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
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
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))

        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)

        val hadNoAltLog = ShadowLog.getLogs().any { it.tag == "ServerAutoSwitcher" && it.msg.contains("no alternative servers available") }
        assertEquals(true, hadNoAltLog)
    }
}

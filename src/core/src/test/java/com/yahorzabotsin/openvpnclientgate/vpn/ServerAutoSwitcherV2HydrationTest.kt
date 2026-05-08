package com.yahorzabotsin.openvpnclientgate.vpn

import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Tests for DEFAULT_V2 auto-switch parity (AC-3, TS-5, TS-6).
 *
 * TS-5: DEFAULT_V2 auto-switch can obtain the next server after startup without the user needing
 *       to reopen the country list (store already populated by initial selection).
 * TS-6: DEFAULT_V2 auto-switch hydrates the selected-country list when the store is empty and
 *       then continues shared circular switching behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ServerAutoSwitcherV2HydrationTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val source = "VPN_STATUS"
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Unit)? = null
    private var originalStopper: ((android.content.Context) -> Unit)? = null
    private var originalHydrationCallback: ((android.content.Context, () -> Unit) -> Unit)? = null
    private data class StartCall(val cfg: String, val reconnect: Boolean)
    private val startCalls = mutableListOf<StartCall>()
    private var stopCalls = 0

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 2)
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        ServerAutoSwitcher.setRepliedThresholdForTest(2)

        originalStarter = ServerAutoSwitcher.starter
        originalStopper = ServerAutoSwitcher.stopper
        originalHydrationCallback = ServerAutoSwitcher.v2HydrationCallback

        ServerAutoSwitcher.starter = { _, cfg, _, reconnect -> startCalls.add(StartCall(cfg, reconnect)) }
        ServerAutoSwitcher.stopper = { _ -> stopCalls++ }
        ServerAutoSwitcher.v2HydrationCallback = null

        startCalls.clear()
        stopCalls = 0
    }

    @After
    fun tearDown() {
        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        originalHydrationCallback?.let { ServerAutoSwitcher.v2HydrationCallback = it } ?: run {
            ServerAutoSwitcher.v2HydrationCallback = null
        }
        ServerAutoSwitcher.resetNoReplyThreshold()
        ServerAutoSwitcher.resetRepliedThreshold()
        SelectedCountryStore.getSelectedCountry(appContext)?.let {
            appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    // TS-5: After startup materialises the store (AC-1), DEFAULT_V2 auto-switch reads the
    // next server from the store and rotates circularly — no country-screen visit required.
    @Test
    fun autoSwitch_v2_rotates_within_populated_store() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        val servers = listOf(
            makeServer("conf-v2-1", "jp-1"),
            makeServer("conf-v2-2", "jp-2")
        )
        SelectedCountryStore.saveSelection(appContext, "Japan", servers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // After timeout: stop requested; wait for NOTCONNECTED then start next
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, startCalls.size)
        assertEquals("conf-v2-2", startCalls.first().cfg)
        assertEquals(true, startCalls.first().reconnect)
    }

    // TS-6: When the store is empty for DEFAULT_V2, auto-switch invokes the hydration callback.
    // After hydration populates the store, the switch proceeds with the first server.
    @Test
    fun autoSwitch_v2_hydrates_empty_store_and_then_switches() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        // Store is intentionally empty
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val hydratedServers = listOf(
            makeServer("conf-hydrated-1", "ip-1"),
            makeServer("conf-hydrated-2", "ip-2")
        )

        // Hydration callback synchronously populates the store then invokes onDone
        ServerAutoSwitcher.v2HydrationCallback = { ctx, onDone ->
            SelectedCountryStore.saveSelection(ctx, "Japan", hydratedServers)
            SelectedCountryStore.resetIndex(ctx)
            onDone()
        }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // The hydration callback invoked onDone synchronously on the handler thread after stop request
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // Hydration should have occurred and the switch should target the second server
        // (first circular move from index 0)
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertTrue(
            "Expected at least one start call after hydration; got ${startCalls.size}",
            startCalls.isNotEmpty()
        )
        val firstStartConfig = startCalls.first().cfg
        assertTrue(
            "Start should use a hydrated server config, got: $firstStartConfig",
            firstStartConfig.startsWith("conf-hydrated-")
        )
    }

    // TS-6 edge: When hydration also returns no servers, the engine is stopped without a crash.
    @Test
    fun autoSwitch_v2_hydration_returns_empty_store_stops_engine() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Hydration callback does NOT populate the store
        ServerAutoSwitcher.v2HydrationCallback = { _, onDone -> onDone() }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(0, startCalls.size)
        assertTrue("Engine stop expected after hydration with no servers", stopCalls > 0)
    }

    // AC-3.1: Same thresholds and timing apply for DEFAULT_V2 as for Legacy — the hydration
    // callback guard must not change the normal full-cycle behavior when store is non-empty.
    @Test
    fun autoSwitch_v2_full_cycle_stops_engine_without_hydration_when_store_non_empty() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        val single = listOf(makeServer("conf-single", "ip-single"))
        SelectedCountryStore.saveSelection(appContext, "Japan", single)
        SelectedCountryStore.resetIndex(appContext)

        // Hydration callback must NOT be called when the full cycle exhausts the list
        var hydrationCalled = false
        ServerAutoSwitcher.v2HydrationCallback = { _, onDone -> hydrationCalled = true; onDone() }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        assertEquals(false, hydrationCalled)
        assertEquals(0, startCalls.size)
        assertTrue("Engine stop expected after full cycle", stopCalls > 0)
    }

    // --------------- helpers ---------------

    private fun makeServer(config: String, ip: String) = Server(
        lineIndex = 0,
        name = ip,
        city = "",
        country = Country("Japan", "JP"),
        ping = 0,
        signalStrength = SignalStrength.WEAK,
        ip = ip,
        score = 0,
        speed = 0L,
        numVpnSessions = 0,
        uptime = 0L,
        totalUsers = 0L,
        totalTraffic = 0L,
        logType = "",
        operator = "",
        message = "",
        configData = config
    )
}

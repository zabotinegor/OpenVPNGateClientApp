package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

// Fix-cycle 13 (86cb35fbt, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-4.md
// section 2): manual QA round 4 device-reproduced a genuine, previously-undiscovered
// RemoteServiceException$ForegroundServiceDidNotStartInTimeException in the ENGINE's OWN
// de.blinkt.openvpn.core.OpenVPNService (a different class/process/manifest from this
// controller), caused by rapid ACTION_STOP -> ACTION_START churn during an auto-switch retry
// chain racing airplane-mode-style network flapping. The fix delays only the engine-facing
// dispatch (startIcsOpenVpn(), which reaches VPNLaunchHelper.startOpenVpn() ->
// Context.startForegroundService() against the engine's own service) for auto-switch reconnect
// starts (isReconnect == true) by ENGINE_RECONNECT_DISPATCH_BUFFER_MS, giving the engine's own
// async teardown of the PREVIOUS session more real time to land before the next FGS obligation
// is armed. A fresh user-initiated Connect tap (isReconnect == false) has no preceding stop to
// race and must remain dispatched immediately, unchanged.
//
// "Requested engine start (profile=...)" is logged synchronously, once, at the tail of
// startIcsOpenVpn() -- the only production log line downstream of the delay point under test. Its
// presence/absence at specific points on the Robolectric virtual clock is what each test below
// observes.
//
// Falsifiability: every test in this file must fail if OpenVpnService.kt's ACTION_START handler
// is reverted to unconditionally call startIcsOpenVpn(config, title) synchronously (removing the
// `if (isReconnect) { ... postDelayed ... } else { startIcsOpenVpn(...) }` branch and the
// ENGINE_RECONNECT_DISPATCH_BUFFER_MS constant it uses).
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [27])
class OpenVpnServiceReconnectEngineDispatchTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "OpenVpnService"
    private val engineStartLog = "Requested engine start"

    private fun hasEngineStartLog(): Boolean =
        ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains(engineStartLog) }

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
    }

    private fun reconnectStartIntent(startId: Int = 2) = Intent(appContext, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
        putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
        putExtra(VpnManager.extraConfigKey(appContext), "client\n")
        putExtra(VpnManager.extraTitleKey(appContext), "RU")
    }

    private fun freshStartIntent() = Intent(appContext, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
        putExtra(VpnManager.extraConfigKey(appContext), "client\n")
        putExtra(VpnManager.extraTitleKey(appContext), "RU")
    }

    @Test
    fun reconnectStart_doesNotDispatchToEngineSynchronously() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)

        assertFalse(
            "An auto-switch reconnect ACTION_START must not reach the engine (VPNLaunchHelper -> " +
                "Context.startForegroundService() against the engine's own service) synchronously " +
                "within the same onStartCommand() call -- see ENGINE_RECONNECT_DISPATCH_BUFFER_MS's " +
                "declaration comment for the FGS-obligation race this defers past",
            hasEngineStartLog()
        )
    }

    @Test
    fun reconnectStart_dispatchesToEngineAfterBuffer() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // ENGINE_RECONNECT_DISPATCH_BUFFER_MS is 500ms; advance comfortably past it.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertTrue(
            "The deferred reconnect dispatch must still reach the engine once the buffer has " +
                "elapsed -- this is a delay, not a drop",
            hasEngineStartLog()
        )
    }

    @Test
    fun freshUserStart_dispatchesToEngineImmediately() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(freshStartIntent(), 0, 1)

        assertTrue(
            "A fresh user-initiated Connect tap (isReconnect == false) has no preceding stop to " +
                "race and must be dispatched to the engine immediately, exactly as before this fix " +
                "-- the reconnect-only buffer must not add latency to this path",
            hasEngineStartLog()
        )
    }

    @Test
    fun userDisconnectDuringBufferWindow_suppressesDeferredEngineDispatch() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // A genuine user Disconnect lands inside the buffer window.
        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 3)

        // Advance well past the buffer.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertFalse(
            "A genuine user Disconnect landing inside the reconnect-dispatch buffer window must " +
                "suppress the deferred engine dispatch entirely (userInitiatedStop guard), not " +
                "merely delay it -- otherwise the app would reconnect to the engine moments after " +
                "an explicit Disconnect",
            hasEngineStartLog()
        )
    }
}

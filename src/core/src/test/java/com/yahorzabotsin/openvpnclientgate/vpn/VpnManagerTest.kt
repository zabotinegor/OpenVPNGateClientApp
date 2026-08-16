package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.util.Base64
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class VpnManagerTest {

    @Before
    fun resetState() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        VpnManager.resetActionStartDispatchTrackingForTest()
    }

    @Test
    fun startVpn_decodesBase64AndSetsExtras() {
        val app: Application = RuntimeEnvironment.getApplication()
        val plain = "remote example.com 1194"
        val encoded = Base64.encodeToString(plain.toByteArray(), Base64.DEFAULT)

        VpnManager.startVpn(app, encoded, displayName = "MyTitle")

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        assertNotNull(started)
        assertEquals(OpenVpnService::class.java.name, started.component?.className)

        val cfgKey = VpnManager.extraConfigKey(app)
        val titleKey = VpnManager.extraTitleKey(app)
        val actionKey = VpnManager.actionKey(app)
        assertEquals(plain, started.getStringExtra(cfgKey))
        assertEquals("MyTitle", started.getStringExtra(titleKey))
        assertEquals(VpnManager.ACTION_START, started.getStringExtra(actionKey))
    }

    @Test
    fun startVpn_acceptsPlainConfigIfNotBase64() {
        val app: Application = RuntimeEnvironment.getApplication()
        val plain = "not_base64!@#"

        VpnManager.startVpn(app, plain, displayName = null)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val cfgKey = VpnManager.extraConfigKey(app)
        val titleKey = VpnManager.extraTitleKey(app)
        assertEquals(plain, started.getStringExtra(cfgKey))
        assertEquals(null, started.getStringExtra(titleKey))
    }

    @Test
    fun stopVpn_setsStopActionAndHint() {
        val app: Application = RuntimeEnvironment.getApplication()

        VpnManager.stopVpn(app, preserveReconnectHint = true)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        val hintKey = VpnManager.extraPreserveReconnectKey(app)
        assertEquals(VpnManager.ACTION_STOP, started.getStringExtra(actionKey))
        assertEquals(true, started.getBooleanExtra(hintKey, false))
    }

    @Test
    fun pauseVpn_setsPauseAction() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        VpnManager.pauseVpn(app)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        assertEquals(VpnManager.ACTION_PAUSE, started.getStringExtra(actionKey))
    }

    @Test
    fun pauseVpn_movesConnectedStateToPausingImmediately() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        VpnManager.pauseVpn(app)

        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
    }

    @Test
    fun pauseVpn_rejectsWhenNotConnected() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        val result = VpnManager.pauseVpn(app)

        assertEquals(false, result)
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
    }

    @Test
    fun pauseVpn_acceptsWhenPausingAlreadyInProgress() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.beginPauseTransition()

        val result = VpnManager.pauseVpn(app)

        assertEquals(true, result)
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeVpn_setsResumeAction() {
        val app: Application = RuntimeEnvironment.getApplication()

        VpnManager.resumeVpn(app)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        assertEquals(VpnManager.ACTION_RESUME, started.getStringExtra(actionKey))
    }

    @Test
    fun resumeVpn_movesPausedStateToConnectingImmediately() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateState(ConnectionState.PAUSED)

        VpnManager.resumeVpn(app)

        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
    }

    @Test
    fun doublePause_secondPauseIsAccepted() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val result1 = VpnManager.pauseVpn(app)
        val result2 = VpnManager.pauseVpn(app)

        assertEquals(true, result1)
        assertEquals(true, result2)
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeDuringPause_transitionsToConnecting() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.beginPauseTransition()

        VpnManager.resumeVpn(app)

        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
        val shadowApp = Shadows.shadowOf(app)
        val started = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        assertEquals(VpnManager.ACTION_RESUME, started.getStringExtra(actionKey))
    }

    @Test
    fun stopControllerIfIdle_startsServiceWhenDisconnected() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        VpnManager.stopControllerIfIdle(app)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        assertEquals(VpnManager.ACTION_STOP_IF_IDLE, started.getStringExtra(actionKey))
    }

    @Test
    fun stopControllerIfIdle_skipsServiceStartWhenConnected() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        VpnManager.stopControllerIfIdle(app)

        val shadowApp = Shadows.shadowOf(app)
        val started = shadowApp.nextStartedService
        assertNull(started)
    }

    @Test
    fun syncStatus_startsServiceWithSyncAction() {
        val app: Application = RuntimeEnvironment.getApplication()

        VpnManager.syncStatus(app)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        assertEquals(VpnManager.ACTION_SYNC_STATUS, started.getStringExtra(actionKey))
    }

    @Test
    fun pauseVpn_restoresConnectedStateWhenServiceStartFails() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val failingContext = ThrowingServiceContext(app)
        val result = VpnManager.pauseVpn(failingContext)

        assertFalse(result)
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeVpn_restoresPausedStateWhenServiceStartFails() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateState(ConnectionState.PAUSED)

        val failingContext = ThrowingServiceContext(app)
        val result = VpnManager.resumeVpn(failingContext)

        assertFalse(result)
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    // Regression tests for VpnManager.hasRecentActionStartDispatch() (fix-cycle 7,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-2.md, "2026-08-14 continuation
    // 2"): records an ACTION_START dispatch attempt synchronously, immediately before the actual
    // startForegroundService() call, so OpenVpnService's one-shot idle-teardown stop can detect a
    // fresh start still in AMS/Binder transit -- see the field's declaration comment for the
    // FGS-obligation-timing race this closes.

    @Test
    fun hasRecentActionStartDispatch_falseWhenNoneRecorded() {
        assertFalse(VpnManager.hasRecentActionStartDispatch())
    }

    @Test
    fun startVpn_recordsRecentActionStartDispatch() {
        val app: Application = RuntimeEnvironment.getApplication()

        VpnManager.startVpn(app, "client\n", displayName = "RU")

        assertTrue(VpnManager.hasRecentActionStartDispatch())
    }

    @Test
    fun hasRecentActionStartDispatch_falseAfterWindowElapses() {
        val app: Application = RuntimeEnvironment.getApplication()
        VpnManager.startVpn(app, "client\n", displayName = "RU")

        val farFuture = android.os.SystemClock.elapsedRealtime() + 10_000L
        assertFalse(
            "The dispatch marker must not stay set forever -- it only needs to bridge the brief " +
                "AMS/Binder delivery gap, not survive indefinitely",
            VpnManager.hasRecentActionStartDispatch(nowElapsedRealtimeMs = farFuture)
        )
    }

    @Test
    fun stopVpn_doesNotRecordActionStartDispatch() {
        val app: Application = RuntimeEnvironment.getApplication()

        VpnManager.stopVpn(app)

        assertFalse(
            "Only ACTION_START dispatches should arm the recent-dispatch marker",
            VpnManager.hasRecentActionStartDispatch()
        )
    }

    @Test
    fun pauseVpn_doesNotRecordActionStartDispatch() {
        val app: Application = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        VpnManager.pauseVpn(app)

        assertFalse(VpnManager.hasRecentActionStartDispatch())
    }

    // Regression test for R9-3 (fix-cycle 9, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-9.md): lastActionStartDispatchElapsedRealtimeMs's own declaration comment says
    // "Once onStartCommand() runs, userInitiatedStart is the authoritative, longer-lived signal --
    // this flag only needs to bridge the brief pre-delivery gap", but before this fix nothing
    // outside tests ever cleared it, so it stayed "recent" for the full
    // RECENT_ACTION_START_DISPATCH_WINDOW_MS (2s) even after the start fully landed -- every
    // hasRecentActionStartDispatch() guard (cycle-7's original site plus fix-cycle 8's two
    // additions) then DROPPED an intervening stop decision instead of merely deferring it. See
    // OpenVpnServiceNotificationTest.startAction_clearsRecentActionStartDispatchMarker for the
    // integration-level test that onStartCommand()'s ACTION_START handler actually calls this.
    @Test
    fun clearRecentActionStartDispatch_clearsMarker() {
        val app: Application = RuntimeEnvironment.getApplication()
        VpnManager.startVpn(app, "client\n", displayName = "RU")
        assertTrue(
            "Precondition: startVpn() must record the dispatch marker",
            VpnManager.hasRecentActionStartDispatch()
        )

        VpnManager.clearRecentActionStartDispatch()

        assertFalse(
            "clearRecentActionStartDispatch() must clear the marker so a later hasRecentAction" +
                "StartDispatch() check does not keep treating a fully-landed start as still in flight",
            VpnManager.hasRecentActionStartDispatch()
        )
    }

    // Regression test for PR #127 round-2 Codex finding (thread 3791559721,
    // src/core/.../vpn/VpnManager.kt:202): when the dispatch call underlying an ACTION_START
    // request throws (e.g. a background auto-switch retry rejected by Android's
    // FGS-start-from-background restriction), lastActionStartDispatchElapsedRealtimeMs stayed
    // armed with nothing to unarm it. Every hasRecentActionStartDispatch() guard site in
    // OpenVpnService (one-shot-sync-confirmed, finishStopFlowConfirmed(), ACTION_STOP_IF_IDLE) is a
    // single-shot check with no self-retry, so a teardown that happened to race the marker inside
    // its window aborted once and nothing re-triggered it -- a pre-existing idle controller could
    // stay running until some unrelated later event (next app foreground/background cycle, etc.)
    // happened to re-issue a teardown, which is not actually bounded in practice. The fix schedules
    // a single delayed stopControllerIfIdle() re-check timed to run once the marker window has
    // definitely elapsed, closing the gap without clearing the marker early (which would reopen the
    // AMS-obligation race lastActionStartDispatchElapsedRealtimeMs's declaration comment already
    // guards against). Uses a startService()-throwing fault (matching this file's pre-existing
    // ThrowingServiceContext convention) rather than overriding startForegroundService(): on this
    // project's default Robolectric SDK (16, well below the API 26 startForegroundService cutoff),
    // ContextCompat.startForegroundService() itself falls back to context.startService() -- the
    // exact same call VpnManager.startControllerService()'s non-ACTION_START branch already uses,
    // so this is the fault-injection point that is actually exercised here.
    @Test
    fun startVpn_failedDispatch_selfHealsViaDelayedIdleRecheck() {
        val app: Application = RuntimeEnvironment.getApplication()
        val failOnceContext = FailOnceStartServiceContext(app)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val result = VpnManager.startVpn(failOnceContext, "client\n", displayName = "RU")

        assertFalse("A thrown dispatch call must be reported as a failed dispatch", result)
        assertTrue(
            "The recent-dispatch marker must stay armed on a failed dispatch -- AMS may already " +
                "have registered the FGS-start obligation before raising the exception, so clearing " +
                "it here would reopen the crash this bug's fix-flow closes",
            VpnManager.hasRecentActionStartDispatch()
        )

        val shadowApp = Shadows.shadowOf(app)
        assertNull(
            "No teardown should be dispatched yet -- still inside the safety window",
            shadowApp.nextStartedService
        )

        // Advance past RECENT_ACTION_START_DISPATCH_WINDOW_MS (2s) plus the fix's small buffer.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_300))

        val started = shadowApp.nextStartedService
        assertNotNull(
            "A failed ACTION_START dispatch must self-heal by re-running stopControllerIfIdle() " +
                "once the recent-dispatch marker window has elapsed, so a pre-existing idle " +
                "controller is not left running indefinitely",
            started
        )
        assertEquals(
            VpnManager.ACTION_STOP_IF_IDLE,
            started?.getStringExtra(VpnManager.actionKey(app))
        )
    }

    private class ThrowingServiceContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent?): android.content.ComponentName {
            throw RuntimeException("startService failed")
        }
    }

    private class FailOnceStartServiceContext(base: Context) : ContextWrapper(base) {
        private var startServiceCallCount = 0

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent?): android.content.ComponentName? {
            startServiceCallCount++
            if (startServiceCallCount == 1) {
                throw RuntimeException("startService failed")
            }
            return super.startService(service)
        }
    }
}
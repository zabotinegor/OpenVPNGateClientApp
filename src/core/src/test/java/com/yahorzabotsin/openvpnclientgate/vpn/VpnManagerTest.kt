package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class VpnManagerTest {

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

    private class ThrowingServiceContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent?): android.content.ComponentName {
            throw RuntimeException("startService failed")
        }
    }
}
package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServicePauseLifecycleTest {
    private val appContext = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @After
    fun tearDown() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun pauseLifecycle_clearsInFlightFlagOnActionStart() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "controllerForegroundActive", true)
        ReflectionHelpers.setField(service, "pauseActionInFlight", true)
        
        val startIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(startIntent, 0, 1)
        
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
    }

    @Test
    fun pauseLifecycle_clearsInFlightFlagOnActionStop() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "pauseActionInFlight", true)
        ReflectionHelpers.setField(service, "resumeActionInFlight", true)
        
        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 2)
        
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "resumeActionInFlight"))
    }

    @Test
    fun pauseResumeSequence_staysValid() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        
        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        VpnManager.pauseVpn(appContext)
        
        ConnectionStateManager.updateState(ConnectionState.PAUSING)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)
        ConnectionStateManager.updateState(ConnectionState.PAUSED)
        
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        
        val resumeIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_RESUME)
        }
        VpnManager.resumeVpn(appContext)
        
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
    }
}

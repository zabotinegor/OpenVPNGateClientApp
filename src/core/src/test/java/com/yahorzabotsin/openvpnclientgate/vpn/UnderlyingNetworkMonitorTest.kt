package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
// Pinned to SDK 27 like other framework-heavy tests in this package: under the default SDK
// selection the android.net ConnectivityManager/NetworkCapabilities shadows were not applied.
@Config(manifest = Config.NONE, sdk = [27])
class UnderlyingNetworkMonitorTest {
    private val app = RuntimeEnvironment.getApplication()
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val shadowCm = Shadows.shadowOf(cm)

    @Before
    fun setUp() {
        shadowCm.clearAllNetworks()
    }

    private fun caps(notVpn: Boolean, validated: Boolean, internet: Boolean = true): NetworkCapabilities {
        val nc = ShadowNetworkCapabilities.newInstance()
        val shadow = Shadows.shadowOf(nc)
        // The framework constructor already sets NOT_VPN by default; make every flag explicit.
        shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (internet) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (notVpn) {
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } else {
            shadow.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return nc
    }

    private fun addNetwork(id: Int, nc: NetworkCapabilities): Network {
        val network = ShadowNetwork.newInstance(id)
        val info = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        shadowCm.addNetwork(network, info)
        shadowCm.setNetworkCapabilities(network, nc)
        return network
    }

    @Test
    fun validatedNonVpnNetwork_isUsable() {
        addNetwork(101, caps(notVpn = true, validated = true))

        assertTrue(ConnectivityUnderlyingNetworkMonitor(cm).isUsableNetworkAvailable())
    }

    @Test
    fun vpnOnlyNetwork_isNotUsable() {
        addNetwork(102, caps(notVpn = false, validated = true))

        assertFalse(
            "the app's own tunnel must never count as the underlying network",
            ConnectivityUnderlyingNetworkMonitor(cm).isUsableNetworkAvailable()
        )
    }

    @Test
    fun unvalidatedNonVpnNetwork_isNotUsable() {
        addNetwork(103, caps(notVpn = true, validated = false))

        assertFalse(ConnectivityUnderlyingNetworkMonitor(cm).isUsableNetworkAvailable())
    }

    @Test
    fun noNetworks_isNotUsable() {
        assertFalse(ConnectivityUnderlyingNetworkMonitor(cm).isUsableNetworkAvailable())
    }

    @Test
    fun vpnPlusValidatedUnderlyingNetwork_isUsable() {
        addNetwork(104, caps(notVpn = false, validated = true))
        addNetwork(105, caps(notVpn = true, validated = true))

        assertTrue(ConnectivityUnderlyingNetworkMonitor(cm).isUsableNetworkAvailable())
    }

    @Test
    fun missingConnectivityManager_fallsBackToUsable() {
        assertTrue(ConnectivityUnderlyingNetworkMonitor(null as ConnectivityManager?).isUsableNetworkAvailable())
    }

    @Test
    fun nullCapabilities_areNotUsable() {
        assertFalse(ConnectivityUnderlyingNetworkMonitor.isUsable(null))
    }

    @Test
    fun callback_reportsOnlyUsabilityTransitions_onMainThread() {
        val monitor = ConnectivityUnderlyingNetworkMonitor(cm)
        val reported = mutableListOf<Boolean>()
        var onMain = true
        monitor.setListener {
            onMain = onMain && Looper.myLooper() == Looper.getMainLooper()
            reported.add(it)
        }
        monitor.start()
        val callback = monitor.callbackForTest()
        val wifi = ShadowNetwork.newInstance(201)

        callback.onCapabilitiesChanged(wifi, caps(notVpn = true, validated = true))
        callback.onCapabilitiesChanged(wifi, caps(notVpn = true, validated = true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a repeated usable report must not be re-delivered", listOf(true), reported)

        callback.onCapabilitiesChanged(wifi, caps(notVpn = true, validated = false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), reported)

        callback.onCapabilitiesChanged(wifi, caps(notVpn = true, validated = true))
        callback.onLost(wifi)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false, true, false), reported)
        assertTrue(onMain)
        monitor.stop()
    }

    @Test
    fun startAndStop_registerAndUnregisterOneCallback() {
        val monitor = ConnectivityUnderlyingNetworkMonitor(cm)
        val before = shadowCm.networkCallbacks.size

        monitor.start()
        monitor.start()
        assertEquals(before + 1, shadowCm.networkCallbacks.size)

        monitor.stop()
        assertEquals(before, shadowCm.networkCallbacks.size)
    }
}

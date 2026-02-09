package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.content.Context
import android.content.ContextWrapper
import com.yahorzabotsin.openvpnclientgate.core.servers.LastConfig
import com.yahorzabotsin.openvpnclientgate.core.servers.StoredServer
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionControlsPresenterTest {

    private val context: Context = ContextWrapper(null)
    private val presenter = ConnectionControlsPresenter(context, ConnectionControlsUseCase())

    @Test
    fun `formatDuration returns placeholder when not connected`() {
        val value = presenter.formatDuration(
            state = ConnectionState.DISCONNECTED,
            connectionStartTimeMs = System.currentTimeMillis() - 15_000L
        )

        assertEquals("00:00:00", value)
    }

    @Test
    fun `formatTraffic formats both directions via usecase`() {
        val (down, up) = presenter.formatTraffic(
            downloaded = 2L * 1024L * 1024L,
            uploaded = 512L
        )

        assertEquals("2.00 MB", down)
        assertEquals("512 B", up)
    }

    @Test
    fun `syncServer prefers current config ip and returns selected country`() {
        val store = FakeSelectionStore(
            selectedCountry = "Japan",
            currentServer = StoredServer(city = "Tokyo", config = "cfg-1", ip = "1.2.3.4"),
            lastStarted = LastConfig(country = "Japan", config = "cfg-1", ip = "5.6.7.8"),
            lastSuccessfulIp = "9.9.9.9",
            position = null
        )

        val sync = presenter.syncServer(
            selectionStore = store,
            selectedCountry = null,
            selectedServerIp = "11.11.11.11",
            vpnConfig = "cfg-1"
        )

        assertNotNull(sync)
        assertEquals("Japan", sync?.country)
        assertEquals("1.2.3.4", sync?.ip)
        assertEquals("", sync?.cityText)
    }

    @Test
    fun `resolveIpForConfig falls back to selected ip when store has no match`() {
        val store = FakeSelectionStore(
            selectedCountry = "Japan",
            currentServer = StoredServer(city = "Tokyo", config = "cfg-1", ip = "1.2.3.4"),
            lastStarted = null,
            lastSuccessfulIp = null,
            lastSuccessfulConfig = null,
            ipForConfig = null
        )

        val ip = presenter.resolveIpForConfig(
            selectionStore = store,
            config = "unknown",
            selectedServerIp = "11.11.11.11"
        )

        assertEquals("11.11.11.11", ip)
        assertTrue(store.ipForConfigRequested)
    }

    private class FakeSelectionStore(
        private val selectedCountry: String?,
        private val currentServer: StoredServer?,
        private val lastStarted: LastConfig?,
        private val lastSuccessfulIp: String?,
        private val lastSuccessfulConfig: String? = null,
        private val ipForConfig: String? = null,
        private val position: Pair<Int, Int>? = null
    ) : ConnectionControlsSelectionStore {

        var ipForConfigRequested: Boolean = false

        override fun getSelectedCountry(context: Context): String? = selectedCountry

        override fun currentServer(context: Context): StoredServer? = currentServer

        override fun getLastStartedConfig(context: Context): LastConfig? = lastStarted

        override fun getLastSuccessfulIpForSelected(context: Context): String? = lastSuccessfulIp

        override fun getLastSuccessfulConfigForSelected(context: Context): String? = lastSuccessfulConfig

        override fun getIpForConfig(context: Context, config: String): String? {
            ipForConfigRequested = true
            return ipForConfig
        }

        override fun getCurrentPosition(context: Context): Pair<Int, Int>? = position
    }
}

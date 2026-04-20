package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.LastConfig
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryVersionSignal
import com.yahorzabotsin.openvpnclientgate.core.servers.StoredServer
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionControlsViewVersionSignalTest {

    @Test
    fun `selected country signal updates server position on active lifecycle`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = ContextThemeWrapper(app, R.style.Theme_OpenVPNClientGate_Base)
        val view = ConnectionControlsView(context)

        val runtime = FakeRuntime()
        val store = FakeSelectionStore(position = 1 to 2)
        val listener = FakeDetailsListener()
        val lifecycleOwner = TestLifecycleOwner()

        view.setDependencies(
            presenter = ConnectionControlsPresenter(context, ConnectionControlsUseCase()),
            runtime = runtime,
            selectionStore = store
        )
        view.setConnectionDetailsListener(listener)
        view.setLifecycleOwner(lifecycleOwner)
        lifecycleOwner.moveTo(Lifecycle.State.STARTED)

        view.setServer(country = "Country", countryCode = "CC", ip = null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("1/2", listener.lastCity)

        store.position = 2 to 2
        SelectedCountryVersionSignal.bump()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2/2", listener.lastCity)

        lifecycleOwner.moveTo(Lifecycle.State.DESTROYED)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        override val lifecycle: Lifecycle
            get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private class FakeRuntime : ConnectionControlsRuntime {
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val engineLevel: StateFlow<ConnectionStatus?> = MutableStateFlow(null)
        override val engineDetail: StateFlow<String?> = MutableStateFlow(null)
        override val reconnectingHint: StateFlow<Boolean> = MutableStateFlow(false)
        override val remainingSeconds: StateFlow<Int?> = MutableStateFlow(null)
        override val connectionStartTimeMs: StateFlow<Long?> = MutableStateFlow(null)
        override val downloadedBytes: StateFlow<Long> = MutableStateFlow(0L)
        override val uploadedBytes: StateFlow<Long> = MutableStateFlow(0L)
    }

    private class FakeSelectionStore(
        var position: Pair<Int, Int>?
    ) : ConnectionControlsSelectionStore {
        override fun getSelectedCountry(context: Context): String? = "Country"

        override fun currentServer(context: Context): StoredServer? =
            StoredServer(city = "City", config = "cfg", countryCode = "CC", ip = "10.0.0.1")

        override fun getLastStartedConfig(context: Context): LastConfig? = null

        override fun getLastSuccessfulIpForSelected(context: Context): String? = null

        override fun getLastSuccessfulConfigForSelected(context: Context): String? = null

        override fun getIpForConfig(context: Context, config: String): String? = null

        override fun getCurrentPosition(context: Context): Pair<Int, Int>? = position
    }

    private class FakeDetailsListener : ConnectionControlsView.ConnectionDetailsListener {
        var lastCity: String = ""

        override fun updateDuration(text: String) = Unit

        override fun updateTraffic(downloaded: String, uploaded: String) = Unit

        override fun updateCity(city: String) {
            lastCity = city
        }

        override fun updateAddress(address: String) = Unit

        override fun updateStatus(text: String) = Unit
    }
}

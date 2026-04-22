package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.LastConfig
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryVersionSignal
import com.yahorzabotsin.openvpnclientgate.core.servers.StoredServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionControlsViewVersionSignalDeviceTest {

    @Test
    fun selectedCountrySignalUpdatesServerPositionOnActiveLifecycle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = ContextThemeWrapper(app, R.style.Theme_OpenVPNClientGate_Base)
        val runtime = DefaultConnectionControlsRuntime()
        val store = FakeSelectionStore(position = 1 to 2)
        val listener = FakeDetailsListener()
        val lifecycleOwner = TestLifecycleOwner()

        lateinit var view: ConnectionControlsView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ConnectionControlsView(context)
            view.setDependencies(
                presenter = ConnectionControlsPresenter(context, ConnectionControlsUseCase()),
                runtime = runtime,
                selectionStore = store
            )
            view.setConnectionDetailsListener(listener)
            view.setLifecycleOwner(lifecycleOwner)
            lifecycleOwner.moveTo(Lifecycle.State.STARTED)
            view.setServer(country = "Country", countryCode = "CC", ip = null)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("1/2", listener.lastCity)

        store.position = 2 to 2
        SelectedCountryVersionSignal.bump()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals("2/2", listener.lastCity)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            lifecycleOwner.moveTo(Lifecycle.State.DESTROYED)
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
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

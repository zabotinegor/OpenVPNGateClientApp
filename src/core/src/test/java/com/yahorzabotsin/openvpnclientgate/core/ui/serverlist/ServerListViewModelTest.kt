package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerListInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServerListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `init loads servers and emits focus effect`() = runTest {
        val interactor = FakeInteractor(
            loaded = listOf(
                server("Canada", "CA", 1),
                server("USA", "US", 2),
                server("USA", "US", 3)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.countries.size)
        assertEquals("Canada", state.countries[0].country.name)
        assertEquals(1, state.countries[0].serverCount)
        assertTrue(effects.first() is ServerListEffect.FocusFirstItem)
        job.cancel()
    }

    @Test
    fun `load error emits snackbar effect`() = runTest {
        val interactor = FakeInteractor(getError = IOException("boom"))
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.ShowSnackbar)
        assertEquals(
            UiText.Res(R.string.error_getting_servers),
            (effect as ServerListEffect.ShowSnackbar).text
        )
        job.cancel()
    }

    @Test
    fun `select country with no servers finishes canceled`() = runTest {
        val interactor = FakeInteractor(loaded = emptyList())
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())
        advanceUntilIdle()

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(2).toList(effects) }

        vm.onAction(ServerListAction.CountrySelected(Country("Nowhere", "NW")))
        advanceUntilIdle()

        assertTrue(effects[0] is ServerListEffect.ShowToast)
        assertEquals(
            UiText.Res(R.string.no_servers_for_country),
            (effects[0] as ServerListEffect.ShowToast).text
        )
        assertTrue(effects[1] is ServerListEffect.FinishCanceled)
        job.cancel()
    }

    @Test
    fun `select single server emits finish selection`() = runTest {
        val selectedCountry = Country("France", "FR")
        val selectedServer = server("France", "FR", 10)
        val result = ServerSelectionResult(
            countryName = "France",
            countryCode = "FR",
            city = "Paris",
            config = "config",
            ip = "1.2.3.4"
        )
        val interactor = FakeInteractor(
            loaded = listOf(selectedServer),
            selectionResult = result
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())
        advanceUntilIdle()

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(ServerListAction.CountrySelected(selectedCountry))
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.FinishWithSelection)
        assertEquals(result, (effect as ServerListEffect.FinishWithSelection).result)
        job.cancel()
    }

    @Test
    fun `select single server error emits snackbar and cancel`() = runTest {
        val selectedCountry = Country("France", "FR")
        val selectedServer = server("France", "FR", 10)
        val interactor = FakeInteractor(
            loaded = listOf(selectedServer),
            selectionError = IOException("failed")
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())
        advanceUntilIdle()

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(2).toList(effects) }

        vm.onAction(ServerListAction.CountrySelected(selectedCountry))
        advanceUntilIdle()

        assertTrue(effects[0] is ServerListEffect.ShowSnackbar)
        assertEquals(
            UiText.Res(R.string.error_getting_servers),
            (effects[0] as ServerListEffect.ShowSnackbar).text
        )
        assertTrue(effects[1] is ServerListEffect.SetResultCanceled)
        job.cancel()
    }

    @Test
    fun `init_v2_source_emits_country_list_with_server_count`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "CA", name = "Canada", serverCount = 3),
                CountryV2(code = "US", name = "United States", serverCount = 5)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.countries.size)
        // sorted by name: Canada before United States
        assertEquals("Canada", state.countries[0].country.name)
        assertEquals(3, state.countries[0].serverCount)
        assertEquals("United States", state.countries[1].country.name)
        assertEquals(5, state.countries[1].serverCount)
        assertTrue(effects.first() is ServerListEffect.FocusFirstItem)
        job.cancel()
    }

    @Test
    fun `init_v2_source_load_error_emits_snackbar`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            getError = IOException("v2 boom")
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.ShowSnackbar)
        assertEquals(
            UiText.Res(R.string.error_getting_servers),
            (effect as ServerListEffect.ShowSnackbar).text
        )
        job.cancel()
    }

    @Test
    fun `paused state is treated as vpn connected`() = runTest {
        val interactor = FakeInteractor(loaded = emptyList())
        val connection = FakeConnectionProvider(ConnectionState.PAUSED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger())

        advanceUntilIdle()

        assertTrue(vm.state.value.isVpnConnected)
    }
    private fun server(countryName: String, code: String?, lineIndex: Int): Server =
        Server(
            lineIndex = lineIndex,
            name = "srv-$lineIndex",
            city = "city-$lineIndex",
            country = Country(countryName, code),
            ping = 42,
            signalStrength = SignalStrength.STRONG,
            ip = "10.0.0.$lineIndex",
            score = 100,
            speed = 1000L,
            numVpnSessions = 1,
            uptime = 10L,
            totalUsers = 1L,
            totalTraffic = 1L,
            logType = "log",
            operator = "op",
            message = "msg",
            configData = ""
        )

    private class FakeInteractor(
        private val loaded: List<Server> = emptyList(),
        private val countriesV2: List<CountryV2> = emptyList(),
        private val v2Source: Boolean = false,
        private val selectionResult: ServerSelectionResult = ServerSelectionResult("", "", null, "", null),
        private val getError: Exception? = null,
        private val selectionError: Exception? = null
    ) : ServerListInteractor {
        override suspend fun getServers(forceRefresh: Boolean, cacheOnly: Boolean): List<Server> {
            getError?.let { throw it }
            return loaded
        }

        override suspend fun getCountriesV2(forceRefresh: Boolean, cacheOnly: Boolean): List<CountryV2> {
            getError?.let { throw it }
            return countriesV2
        }

        override fun isDefaultV2Source(): Boolean = v2Source

        override suspend fun resolveSelection(
            countryName: String,
            countryCode: String?,
            server: Server,
            countryServers: List<Server>
        ): ServerSelectionResult {
            selectionError?.let { throw it }
            return selectionResult
        }
    }

    private class FakeConnectionProvider(initial: ConnectionState) : VpnConnectionStateProvider {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = _state
        override fun isConnected(): Boolean =
            _state.value == ConnectionState.CONNECTED || _state.value == ConnectionState.PAUSED
    }

    private class FakeLogger : ServerListLogger {
        override fun logLoadSuccess(count: Int) = Unit
        override fun logLoadError(error: Exception) = Unit
        override fun logNoServers(countryName: String) = Unit
        override fun logSelectionError(countryName: String, error: Exception) = Unit
    }

}

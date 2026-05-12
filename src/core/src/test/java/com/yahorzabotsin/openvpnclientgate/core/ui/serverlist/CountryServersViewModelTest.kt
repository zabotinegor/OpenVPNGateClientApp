package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
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
class CountryServersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `initialize with empty country finishes canceled`() = runTest {
        val vm = CountryServersViewModel(
            interactor = FakeInteractor(),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger()
        )

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.Initialize(countryName = null, countryCode = null))
        advanceUntilIdle()

        assertTrue(effects.first() is CountryServersEffect.FinishCanceled)
        job.cancel()
    }

    @Test
    fun `initialize loads servers and emits focus effect`() = runTest {
        val interactor = FakeInteractor(
            loaded = listOf(
                server("France", "FR", 1),
                server("France", "FR", 2)
            )
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.CONNECTED),
            logger = FakeLogger()
        )

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        assertEquals(false, interactor.lastCacheOnly)
        assertEquals("FR", interactor.lastCountryCode)
        assertEquals(2, vm.state.value.servers.size)
        assertTrue(effects.first() is CountryServersEffect.FocusFirstItem)
        job.cancel()
    }

    @Test
    fun `initialize with empty loaded list shows toast and cancels`() = runTest {
        val vm = CountryServersViewModel(
            interactor = FakeInteractor(loaded = emptyList()),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger()
        )

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(2).toList(effects) }

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        assertTrue(effects[0] is CountryServersEffect.ShowToast)
        assertEquals(
            UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.no_servers_for_country),
            (effects[0] as CountryServersEffect.ShowToast).text
        )
        assertTrue(effects[1] is CountryServersEffect.FinishCanceled)
        job.cancel()
    }

    @Test
    fun `select server emits finish selection`() = runTest {
        val selected = server("France", "FR", 2)
        val result = ServerSelectionResult(
            countryName = "France",
            countryCode = "FR",
            city = "Paris",
            config = "cfg",
            ip = "1.2.3.4"
        )
        val interactor = FakeInteractor(
            loaded = listOf(server("France", "FR", 1), selected),
            selectionResult = result
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.ServerSelected(selected))
        advanceUntilIdle()

        val effect = effects.first() as CountryServersEffect.FinishWithSelection
        assertEquals(result, effect.result)
        job.cancel()
    }

    @Test
    fun `select server error emits snackbar and cancel`() = runTest {
        val selected = server("France", "FR", 2)
        val interactor = FakeInteractor(
            loaded = listOf(server("France", "FR", 1), selected),
            selectionError = IOException("failed")
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(2).toList(effects) }

        vm.onAction(CountryServersAction.ServerSelected(selected))
        advanceUntilIdle()

        assertTrue(effects[0] is CountryServersEffect.ShowSnackbar)
        assertEquals(
            UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.error_getting_servers),
            (effects[0] as CountryServersEffect.ShowSnackbar).text
        )
        assertTrue(effects[1] is CountryServersEffect.FinishCanceled)
        job.cancel()
    }

    private fun server(countryName: String, code: String?, index: Int): Server =
        Server(
            lineIndex = index,
            name = "srv-$index",
            city = "city-$index",
            country = Country(countryName, code),
            ping = 42,
            signalStrength = SignalStrength.STRONG,
            ip = "10.0.0.$index",
            score = 100,
            speed = 1000,
            numVpnSessions = 1,
            uptime = 1,
            totalUsers = 1,
            totalTraffic = 1,
            logType = "log",
            operator = "op",
            message = "msg",
            configData = ""
        )

    private class FakeInteractor(
        private val loaded: List<Server> = emptyList(),
        private val selectionResult: ServerSelectionResult = ServerSelectionResult("", null, null, "", null),
        private val selectionError: Exception? = null
    ) : CountryServersInteractor {
        var lastCacheOnly: Boolean? = null
        var lastCountryCode: String? = null

        override suspend fun getServersForCountry(
            countryName: String,
            countryCode: String?,
            cacheOnly: Boolean
        ): List<Server> {
            lastCacheOnly = cacheOnly
            lastCountryCode = countryCode
            return loaded
        }

        override suspend fun resolveSelection(
            countryName: String,
            countryCode: String?,
            servers: List<Server>,
            selectedServer: Server
        ): ServerSelectionResult {
            selectionError?.let { throw it }
            return selectionResult
        }
    }

    private class FakeConnectionProvider(initial: ConnectionState) : VpnConnectionStateProvider {
        private val flow = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = flow
        override fun isConnected(): Boolean = flow.value == ConnectionState.CONNECTED
    }

    private class FakeLogger : CountryServersLogger {
        override fun logLoadSuccess(countryName: String, count: Int) = Unit
        override fun logLoadError(countryName: String, error: Exception) = Unit
        override fun logNoServers(countryName: String) = Unit
        override fun logSelectionError(serverIp: String?, error: Exception) = Unit
    }
}

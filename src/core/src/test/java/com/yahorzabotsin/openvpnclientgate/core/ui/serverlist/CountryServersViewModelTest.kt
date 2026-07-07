package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesServerStore
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
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
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
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
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
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
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
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
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
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
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

    private fun server(countryName: String, code: String?, index: Int, id: Int = 0): Server =
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
            configData = "",
            id = id
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

    private class FakeFavoritesServerStore(
        initialFavorites: Set<Int> = emptySet()
    ) : FavoritesServerStore {
        private val favorites = initialFavorites.toMutableSet()

        override fun getFavoriteServerIds(): Set<Int> = favorites.toSet()

        override fun isFavoriteServer(serverId: Int): Boolean = favorites.contains(serverId)

        override fun addFavoriteServer(serverId: Int) {
            if (serverId > 0) favorites.add(serverId)
        }

        override fun removeFavoriteServer(serverId: Int) {
            favorites.remove(serverId)
        }
    }

    // --- SUB-03 acceptance criteria: pinned favorites section + long-press toggle ---

    @Test
    fun `AC1 - pinned favorites section appears above regular list when a favorite server is present`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val favoritesStore = FakeFavoritesServerStore(setOf(20))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items[0] is ServerListItem.SectionHeader)
        val favoriteRow = items[1] as ServerListItem.ServerRow
        assertEquals(20, favoriteRow.server.id)
        assertTrue(favoriteRow.isFavorite)
        // regular list still contains both servers afterwards
        assertEquals(10, (items[2] as ServerListItem.ServerRow).server.id)
        assertEquals(20, (items[3] as ServerListItem.ServerRow).server.id)
        assertEquals(4, items.size)
    }

    @Test
    fun `AC2 - no favorites section when no favorite from this country is available`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        // Favorite id 999 is not present among this country's servers.
        val favoritesStore = FakeFavoritesServerStore(setOf(999))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items.none { it is ServerListItem.SectionHeader })
        assertEquals(2, items.size)
        assertTrue(items.all { it is ServerListItem.ServerRow })
    }

    @Test
    fun `AC3 - toggle favorite reflects current state via add then remove`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA))
        val favoritesStore = FakeFavoritesServerStore()
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })

        vm.onAction(CountryServersAction.ToggleFavorite(serverA))
        advanceUntilIdle()
        assertTrue(favoritesStore.isFavoriteServer(10))
        assertTrue(vm.state.value.items[0] is ServerListItem.SectionHeader)

        vm.onAction(CountryServersAction.ToggleFavorite(serverA))
        advanceUntilIdle()
        assertTrue(!favoritesStore.isFavoriteServer(10))
        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })
    }

    @Test
    fun `AC3 - servers with id 0 are not favoritable`() = runTest {
        val legacyServer = server("France", "FR", 1, id = 0)
        val interactor = FakeInteractor(loaded = listOf(legacyServer))
        val favoritesStore = FakeFavoritesServerStore()
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.ToggleFavorite(legacyServer))
        advanceUntilIdle()

        assertEquals(0, favoritesStore.getFavoriteServerIds().size)
        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })
    }

    @Test
    fun `AC4 - tapping a server row in the favorites section selects it like the regular list`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val result = ServerSelectionResult(
            countryName = "France",
            countryCode = "FR",
            city = "Paris",
            config = "cfg",
            ip = "1.2.3.4"
        )
        val interactor = FakeInteractor(loaded = listOf(serverA), selectionResult = result)
        val favoritesStore = FakeFavoritesServerStore(setOf(10))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val favoriteRow = vm.state.value.items[1] as ServerListItem.ServerRow

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.ServerSelected(favoriteRow.server))
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is CountryServersEffect.FinishWithSelection)
        assertEquals(result, (effect as CountryServersEffect.FinishWithSelection).result)
        job.cancel()
    }

    @Test
    fun `AC5 - favoriting updates pinned section immediately without reload`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val favoritesStore = FakeFavoritesServerStore()
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()
        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })

        vm.onAction(CountryServersAction.ToggleFavorite(serverA))
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items[0] is ServerListItem.SectionHeader)
        val favoriteRow = items[1] as ServerListItem.ServerRow
        assertEquals(10, favoriteRow.server.id)
        assertTrue(favoriteRow.isFavorite)
    }

    @Test
    fun `AC6 - short-tap selection for non-favorite servers is unchanged`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val result = ServerSelectionResult(
            countryName = "France",
            countryCode = "FR",
            city = "Nice",
            config = "cfg2",
            ip = "5.6.7.8"
        )
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB), selectionResult = result)
        // Only serverB is favorite; serverA remains in the regular (non-favorite) list.
        val favoritesStore = FakeFavoritesServerStore(setOf(20))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.ServerSelected(serverA))
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is CountryServersEffect.FinishWithSelection)
        assertEquals(result, (effect as CountryServersEffect.FinishWithSelection).result)
        job.cancel()
    }
}

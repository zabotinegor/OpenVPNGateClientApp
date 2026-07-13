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
import org.junit.Assert.assertFalse
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
    fun `initialize loads servers and emits focus effect with position 0 when no favorites`() = runTest {
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
        val focusEffect = effects.first() as CountryServersEffect.FocusFirstItem
        assertEquals(0, focusEffect.adapterPosition)
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
        var isFavoriteServerCalls = 0
            private set

        override fun getFavoriteServerIds(): Set<Int> = favorites.toSet()

        override fun isFavoriteServer(serverId: Int): Boolean {
            isFavoriteServerCalls++
            return favorites.contains(serverId)
        }

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
        assertTrue((items[0] as ServerListItem.SectionHeader).showFavoriteIcon)
        val favoriteRow = items[1] as ServerListItem.ServerRow
        assertEquals(20, favoriteRow.server.id)
        assertTrue(favoriteRow.isFavorite)
        // SUB-09: second "All servers" header marks the start of the full list below.
        assertTrue(items[2] is ServerListItem.SectionHeader)
        assertFalse((items[2] as ServerListItem.SectionHeader).showFavoriteIcon)
        // Regular list is additive (mirrors SUB-02 countries screen): it contains ALL
        // servers, with the favorited one still at its normal position, marked favorite.
        assertEquals(10, (items[3] as ServerListItem.ServerRow).server.id)
        assertFalse((items[3] as ServerListItem.ServerRow).isFavorite)
        assertEquals(20, (items[4] as ServerListItem.ServerRow).server.id)
        assertTrue((items[4] as ServerListItem.ServerRow).isFavorite)
        assertEquals(5, items.size)
    }

    // --- SUB-09: second "All servers" header above the full list ---

    @Test
    fun `SUB-09 AC3 - second header labeled All servers appears only when favorites section is visible`() = runTest {
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

        val headers = vm.state.value.items.filterIsInstance<ServerListItem.SectionHeader>()
        assertEquals(2, headers.size)
        assertEquals(UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.favorites_section_title), headers[0].title)
        assertTrue(headers[0].showFavoriteIcon)
        assertEquals(UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.all_servers_section_title), headers[1].title)
        assertFalse(headers[1].showFavoriteIcon)
    }

    @Test
    fun `SUB-09 AC4 - no second header when there are no favorites`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })
    }

    @Test
    fun `SUB-09 edge case - every server favorited still shows both headers and the full list below`() = runTest {
        // When 100% of a country's servers are favorited, the pinned block and the "All
        // servers" list below it end up with identical content (minus ordering) - buildItems
        // must not special case this away or crash; both sections should render additively.
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 11)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val favoritesStore = FakeFavoritesServerStore(setOf(10, 11))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val items = vm.state.value.items
        val headers = items.filterIsInstance<ServerListItem.SectionHeader>()
        assertEquals(2, headers.size)
        assertEquals(
            UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.favorites_section_title),
            headers[0].title
        )
        assertEquals(
            UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.all_servers_section_title),
            headers[1].title
        )

        val pinnedRows = items.filterIsInstance<ServerListItem.ServerRow>().filter { it.isPinnedSection }
        val regularRows = items.filterIsInstance<ServerListItem.ServerRow>().filter { !it.isPinnedSection }
        assertEquals(2, pinnedRows.size)
        assertEquals(2, regularRows.size)
        assertTrue(pinnedRows.all { it.isFavorite })
        assertTrue(regularRows.all { it.isFavorite })
        // header(1) + pinned(2) + header(1) + regular(2) = 6
        assertEquals(6, items.size)
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

    // --- Additive pinned section (round-5 review): favorite stays in the regular list ---
    // Mirrors ServerListViewModelTest's SUB-02 expectations: the pinned Favorites section
    // is a shortcut ON TOP of the regular list, which always contains every server.
    @Test
    fun `favorited_server_appears_in_pinned_section_and_regular_list_additive`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val favoritesStore = FakeFavoritesServerStore(setOf(10))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val items = vm.state.value.items
        // Expected structure:
        // [0] SectionHeader (Favorites)
        // [1] ServerRow(serverA, isFavorite=true)   <- pinned shortcut
        // [2] SectionHeader (All servers, SUB-09)
        // [3] ServerRow(serverA, isFavorite=true)   <- still at its normal list position
        // [4] ServerRow(serverB, isFavorite=false)
        assertEquals(5, items.size)
        assertTrue(items[0] is ServerListItem.SectionHeader)
        val pinnedRow = items[1] as ServerListItem.ServerRow
        assertEquals(10, pinnedRow.server.id)
        assertTrue(pinnedRow.isFavorite)
        assertTrue(items[2] is ServerListItem.SectionHeader)
        val regularFavoriteRow = items[3] as ServerListItem.ServerRow
        assertEquals(10, regularFavoriteRow.server.id)
        assertTrue(regularFavoriteRow.isFavorite)
        val nonFavoriteRow = items[4] as ServerListItem.ServerRow
        assertEquals(20, nonFavoriteRow.server.id)
        assertTrue(!nonFavoriteRow.isFavorite)
    }

    // --- Round-7 review: toggle uses in-memory favoriteServerIds, no store re-read ---
    @Test
    fun `toggle favorite uses in-memory state and does not re-read store for current state`() = runTest {
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

        vm.onAction(CountryServersAction.ToggleFavorite(serverA))
        advanceUntilIdle()
        assertEquals(setOf(10), favoritesStore.getFavoriteServerIds())

        vm.onAction(CountryServersAction.ToggleFavorite(serverA))
        advanceUntilIdle()
        assertEquals(emptySet<Int>(), favoritesStore.getFavoriteServerIds())

        // The current-state check must come from _state.value.favoriteServerIds,
        // never from a SharedPreferences read via isFavoriteServer.
        assertEquals(0, favoritesStore.isFavoriteServerCalls)
    }

    // --- Fix 3: FocusFirstItem lands on section header instead of first server row ---
    @Test
    fun fix3_focus_position_is_1_when_favorites_section_present_skips_header() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val favoritesStore = FakeFavoritesServerStore(setOf(10))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR"))
        advanceUntilIdle()

        val focusEffect = effects.first() as CountryServersEffect.FocusFirstItem
        assertEquals(1, focusEffect.adapterPosition)
        job.cancel()
    }
}

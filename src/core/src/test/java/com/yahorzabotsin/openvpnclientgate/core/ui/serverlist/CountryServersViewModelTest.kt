package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersPage
import com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesServerStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

        vm.onAction(CountryServersAction.Initialize(countryName = null, countryCode = null, pageSize = 50))
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
        private val selectionError: Exception? = null,
        // --- paging fixtures ---
        // First page (skip == 0) always returns `loaded` with `firstPageHasMore`. Subsequent
        // page requests (skip > 0) return `nextPageServers`/`nextPageHasMore` unless the
        // requested skip matches `pageErrorAtSkip`, in which case `pageError` is thrown instead
        // (simulates a mid-scroll network failure for retry tests).
        private val firstPageHasMore: Boolean = false,
        // Overrides the skip==0 page's nextSkip (defaults to the old `loaded.size` behavior)
        // so a test can simulate a raw full page that filters down to zero displayable servers
        // while the backend still reports more pages remain.
        private val firstPageNextSkip: Int? = null,
        // Bounds a would-be-infinite skip==0 retry loop in tests -- once the skip==0 branch
        // has been requested this many times, hasMore flips to false so an *unfixed* loop-
        // detection test still terminates (with a larger, differing call count) instead of
        // hanging forever; a fixed loop stops after the very first call regardless.
        private val firstPageHasMoreCallLimit: Int? = null,
        private val nextPageServers: List<Server> = emptyList(),
        private val nextPageHasMore: Boolean = false,
        private val pageError: Exception? = null,
        private val pageErrorAtSkip: Int? = null,
        // Optional suspension gate: when set, a skip>0 call suspends on this Deferred before
        // returning, letting a test observe the in-flight (isLoadingMore=true) state before
        // completing it.
        private val nextPageGate: CompletableDeferred<Unit>? = null,
        // When set, skip>0 calls consume these pages in request order (overrides the
        // nextPageServers/nextPageHasMore fixtures).
        private val nextPageSequence: List<CountryServersPage>? = null
    ) : CountryServersInteractor {
        var lastCacheOnly: Boolean? = null
        var lastCountryCode: String? = null
        var lastTake: Int? = null
        var lastPagingSessionId: String? = null
            private set
        var getServersPageCallCount = 0
            private set
        val requestedSkips = mutableListOf<Int>()
        // AbandonPagingSession call tracking.
        var abandonPagingSessionCallCount = 0
            private set
        var lastAbandonedSessionId: String? = null
            private set
        // resolveSelection's paging params, recorded for assertions.
        var lastResolveSelectionHasMorePages: Boolean? = null
            private set
        var lastResolveSelectionNextSkip: Int? = null
            private set

        override suspend fun getServersForCountry(
            countryName: String,
            countryCode: String?,
            cacheOnly: Boolean
        ): List<Server> {
            lastCacheOnly = cacheOnly
            lastCountryCode = countryCode
            return loaded
        }

        override suspend fun getServersPage(
            countryName: String,
            countryCode: String?,
            skip: Int,
            take: Int,
            cacheOnly: Boolean,
            pagingSessionId: String
        ): CountryServersPage {
            lastCacheOnly = cacheOnly
            lastCountryCode = countryCode
            lastTake = take
            lastPagingSessionId = pagingSessionId
            getServersPageCallCount++
            requestedSkips.add(skip)
            if (skip > 0) nextPageGate?.await()
            if (pageErrorAtSkip != null && skip == pageErrorAtSkip) {
                throw (pageError ?: IOException("simulated page error"))
            }
            return if (skip == 0) {
                val effectiveHasMore = firstPageHasMoreCallLimit?.let { limit ->
                    requestedSkips.count { it == 0 } < limit
                } ?: firstPageHasMore
                CountryServersPage(
                    servers = loaded,
                    hasMore = effectiveHasMore,
                    nextSkip = firstPageNextSkip ?: loaded.size
                )
            } else {
                val seqIndex = requestedSkips.count { it != 0 } - 1
                nextPageSequence?.getOrNull(seqIndex) ?: CountryServersPage(
                    servers = nextPageServers,
                    hasMore = nextPageHasMore,
                    nextSkip = loaded.size + nextPageServers.size
                )
            }
        }

        override suspend fun resolveSelection(
            countryName: String,
            countryCode: String?,
            servers: List<Server>,
            selectedServer: Server,
            hasMorePages: Boolean,
            nextSkip: Int
        ): ServerSelectionResult {
            lastResolveSelectionHasMorePages = hasMorePages
            lastResolveSelectionNextSkip = nextSkip
            selectionError?.let { throw it }
            return selectionResult
        }

        override fun abandonPagingSession(pagingSessionId: String) {
            abandonPagingSessionCallCount++
            lastAbandonedSessionId = pagingSessionId
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

    // --- pinned favorites section + long-press toggle ---

    @Test
    fun `pinned favorites section appears above regular list when a favorite server is present`() = runTest {
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items[0] is ServerListItem.SectionHeader)
        assertTrue((items[0] as ServerListItem.SectionHeader).showFavoriteIcon)
        val favoriteRow = items[1] as ServerListItem.ServerRow
        assertEquals(20, favoriteRow.server.id)
        assertTrue(favoriteRow.isFavorite)
        // second "All servers" header marks the start of the full list below.
        assertTrue(items[2] is ServerListItem.SectionHeader)
        assertFalse((items[2] as ServerListItem.SectionHeader).showFavoriteIcon)
        // Regular list is additive (mirrors countries screen): it contains ALL
        // servers, with the favorited one still at its normal position, marked favorite.
        assertEquals(10, (items[3] as ServerListItem.ServerRow).server.id)
        assertFalse((items[3] as ServerListItem.ServerRow).isFavorite)
        assertEquals(20, (items[4] as ServerListItem.ServerRow).server.id)
        assertTrue((items[4] as ServerListItem.ServerRow).isFavorite)
        assertEquals(5, items.size)
    }

    // --- second "All servers" header above the full list ---

    @Test
    fun `second header labeled All servers appears only when favorites section is visible`() = runTest {
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        val headers = vm.state.value.items.filterIsInstance<ServerListItem.SectionHeader>()
        assertEquals(2, headers.size)
        assertEquals(UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.favorites_section_title), headers[0].title)
        assertTrue(headers[0].showFavoriteIcon)
        assertEquals(UiText.Res(com.yahorzabotsin.openvpnclientgate.core.R.string.all_servers_section_title), headers[1].title)
        assertFalse(headers[1].showFavoriteIcon)
    }

    @Test
    fun `no second header when there are no favorites`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(loaded = listOf(serverA, serverB))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })
    }

    @Test
    fun `edge case - every server favorited still shows both headers and the full list below`() = runTest {
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
    fun `no favorites section when no favorite from this country is available`() = runTest {
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items.none { it is ServerListItem.SectionHeader })
        assertEquals(2, items.size)
        assertTrue(items.all { it is ServerListItem.ServerRow })
    }

    @Test
    fun `toggle favorite reflects current state via add then remove`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA))
        val favoritesStore = FakeFavoritesServerStore()
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
    fun `servers with id 0 are not favoritable`() = runTest {
        val legacyServer = server("France", "FR", 1, id = 0)
        val interactor = FakeInteractor(loaded = listOf(legacyServer))
        val favoritesStore = FakeFavoritesServerStore()
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.ToggleFavorite(legacyServer))
        advanceUntilIdle()

        assertEquals(0, favoritesStore.getFavoriteServerIds().size)
        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })
    }

    @Test
    fun `tapping a server row in the favorites section selects it like the regular list`() = runTest {
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
    fun `favoriting updates pinned section immediately without reload`() = runTest {
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
    fun `short-tap selection for non-favorite servers is unchanged`() = runTest {
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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
    // Mirrors ServerListViewModelTest's expectations: the pinned Favorites section
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        val items = vm.state.value.items
        // Expected structure:
        // [0] SectionHeader (Favorites)
        // [1] ServerRow(serverA, isFavorite=true)   <- pinned shortcut
        // [2] SectionHeader (All servers)
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
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
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

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        val focusEffect = effects.first() as CountryServersEffect.FocusFirstItem
        assertEquals(1, focusEffect.adapterPosition)
        job.cancel()
    }

    // ==================== lazy-load servers within a country ====================

    // --- only the first page loads on open, sized by the caller-supplied pageSize ---

    @Test
    fun `initial load requests skip 0 with the given pageSize as take`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA), firstPageHasMore = true)
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 7))
        advanceUntilIdle()

        assertEquals(listOf(0), interactor.requestedSkips)
        assertEquals(7, interactor.lastTake)
        assertEquals(1, vm.state.value.servers.size)
        assertTrue("hasMorePages must reflect the first page's result", vm.state.value.hasMorePages)
    }

    // --- scrolling near the loaded end fetches and appends the next page, with a
    // loading indicator shown while that fetch is in flight ---

    @Test
    fun `LoadNextPage appends the next page and clears the loading indicator once done`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverB),
            nextPageHasMore = false
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(listOf(0, 1), interactor.requestedSkips)
        assertEquals(listOf(serverA, serverB), vm.state.value.servers)
        assertFalse(vm.state.value.hasMorePages)
        assertFalse(vm.state.value.isLoadingMore)
        assertTrue(
            "no loading-footer item should remain once the page fetch settles",
            vm.state.value.items.none { it is ServerListItem.LoadingFooter }
        )
    }

    @Test
    fun `loading footer is shown while the next page fetch is in flight`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val gate = CompletableDeferred<Unit>()
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverB),
            nextPageHasMore = false,
            nextPageGate = gate
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle() // runs up to the suspension point inside the fake (nextPageGate.await())

        assertTrue("isLoadingMore must be true while the fetch is in flight", vm.state.value.isLoadingMore)
        assertEquals(
            ServerListItem.LoadingFooter(FooterState.LOADING),
            vm.state.value.items.last()
        )

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.state.value.isLoadingMore)
        assertEquals(listOf(serverA, serverB), vm.state.value.servers)
    }

    // --- Paging mutations must be deferred out of RecyclerView's scroll-callback
    // frame. Production injects CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // (non-immediate => always dispatches through the main queue). These tests pin that
    // contract with a controlled scheduler.
    // Falsifiability: reverting loadNextPage() to viewModelScope.launch (keeping the unused
    // constructor param) makes the inline test fail -- under an unconfined Main the whole
    // footer update then runs inline inside onAction(), which the synchronous assertions
    // below detect via requestedSkips/isLoadingMore. ---

    @Test
    fun `LoadNextPage does not mutate state inline within the triggering call frame`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverB),
            nextPageHasMore = false
        )
        // A queued paging scheduler sharing this test's scheduler simulates production's
        // non-immediate main queue: work posted by LoadNextPage must not run until the
        // current call frame yields.
        val pagingDispatcher = StandardTestDispatcher(testScheduler)
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore(),
            pagingScope = CoroutineScope(SupervisorJob() + pagingDispatcher)
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.LoadNextPage)

        assertEquals(
            "no page request may leave the triggering frame before it yields",
            listOf(0),
            interactor.requestedSkips
        )
        assertFalse("isLoadingMore must not be set inline by the trigger", vm.state.value.isLoadingMore)
        assertTrue(
            "no loading-footer item may appear synchronously inside the triggering frame",
            vm.state.value.items.none { it is ServerListItem.LoadingFooter }
        )

        advanceUntilIdle()

        assertEquals(listOf(0, 1), interactor.requestedSkips)
        assertEquals(listOf(serverA, serverB), vm.state.value.servers)
        assertFalse(vm.state.value.isLoadingMore)
    }

    @Test
    fun `onCleared cancels an in-flight deferred page fetch so it never appends after teardown`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val gate = CompletableDeferred<Unit>()
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverB),
            nextPageHasMore = false,
            nextPageGate = gate
        )
        val store = ViewModelStore()
        val pagingDispatcher = StandardTestDispatcher(testScheduler)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CountryServersViewModel(
                interactor = interactor,
                connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
                logger = FakeLogger(),
                favoritesStore = FakeFavoritesServerStore(),
                pagingScope = CoroutineScope(SupervisorJob() + pagingDispatcher)
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[CountryServersViewModel::class.java]

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle() // runs up to the suspension point inside the fake (nextPageGate.await())
        assertTrue(vm.state.value.isLoadingMore)

        store.clear()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "a fetch cancelled by onCleared must not append its page after teardown",
            listOf(serverA),
            vm.state.value.servers
        )
        assertEquals(listOf(0, 1), interactor.requestedSkips)
    }

    @Test
    fun `paging mutation stays off the caller frame even when Main runs inline`() = runTest {
        // Reproduces the production condition of the defect: real Android Main behaves like an
        // immediate dispatcher, so the pre-fix code (viewModelScope.launch) executed the entire
        // LoadNextPage state update synchronously inside onScrolled. Swap Main to an unconfined
        // dispatcher for this test; the injected paging scope (non-immediate in production) must
        // still defer.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val serverA = server("France", "FR", 1, id = 10)
            val serverB = server("France", "FR", 2, id = 20)
            val interactor = FakeInteractor(
                loaded = listOf(serverA),
                firstPageHasMore = true,
                nextPageServers = listOf(serverB),
                nextPageHasMore = false
            )
            val pagingDispatcher = StandardTestDispatcher(testScheduler)
            val vm = CountryServersViewModel(
                interactor = interactor,
                connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
                logger = FakeLogger(),
                favoritesStore = FakeFavoritesServerStore(),
                pagingScope = CoroutineScope(SupervisorJob() + pagingDispatcher)
            )
            vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
            advanceUntilIdle()
            assertTrue(vm.state.value.hasMorePages)

            vm.onAction(CountryServersAction.LoadNextPage)

            assertEquals(
                "with an inline Main, the pre-fix code fetched and mutated state synchronously; " +
                    "the paging scope must keep the work off the caller frame",
                listOf(0),
                interactor.requestedSkips
            )
            assertFalse(vm.state.value.isLoadingMore)

            advanceUntilIdle()

            assertEquals(listOf(serverA, serverB), vm.state.value.servers)
        } finally {
            // Restore the rule's original Main so its finished() resetMain keeps working.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        }
    }

    // --- Fix: the paging claim must be taken synchronously by the trigger itself, so
    // back-to-back triggers cannot double-launch the same skip while the first deferred
    // coroutine is still queued. ---

    @Test
    fun `two back-to-back LoadNextPage actions produce exactly one page fetch`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverB),
            nextPageHasMore = true
        )
        val pagingDispatcher = StandardTestDispatcher(testScheduler)
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore(),
            pagingScope = CoroutineScope(SupervisorJob() + pagingDispatcher)
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        // Two scroll callbacks firing back-to-back inside one frame: the second must be
        // rejected synchronously instead of queuing a duplicate fetch of the same skip.
        vm.onAction(CountryServersAction.LoadNextPage)
        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(
            "exactly one fetch per unique skip -- no duplicate for the same trigger burst",
            listOf(0, 1),
            interactor.requestedSkips
        )
        assertEquals(listOf(serverA, serverB), vm.state.value.servers)
        assertTrue(vm.state.value.hasMorePages)
    }

    @Test
    fun `double-tap RetryLoadNextPage refetches the failed skip exactly once`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverB),
            nextPageHasMore = false,
            pageErrorAtSkip = 1
        )
        val pagingDispatcher = StandardTestDispatcher(testScheduler)
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore(),
            pagingScope = CoroutineScope(SupervisorJob() + pagingDispatcher)
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()
        assertTrue(vm.state.value.pageLoadError)

        // Rapid double-tap on the retry affordance: only one of the two may refetch skip=1.
        vm.onAction(CountryServersAction.RetryLoadNextPage)
        vm.onAction(CountryServersAction.RetryLoadNextPage)
        advanceUntilIdle()

        assertEquals(
            "a retry burst must refetch the failed skip exactly once",
            listOf(0, 1, 1),
            interactor.requestedSkips
        )
        assertTrue("the retried fetch fails again against this fake", vm.state.value.pageLoadError)
    }

    // --- A later page that neither advances the cursor nor adds servers must
    // terminate paging instead of re-fetching the identical offset on every scroll trigger. ---

    @Test
    fun `loadNextPage stops paging when a later page returns a non-advancing cursor`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = emptyList(),
            nextPageHasMore = true
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(listOf(0, 1), interactor.requestedSkips)
        org.junit.Assert.assertEquals(
            "hasMorePages after non-advancing page",
            false,
            vm.state.value.hasMorePages
        )
        org.junit.Assert.assertEquals(
            "the terminal session must be released immediately",
            1,
            interactor.abandonPagingSessionCallCount
        )
        assertTrue(vm.state.value.items.none { it is ServerListItem.LoadingFooter })

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()
        assertEquals(
            "no further fetch may fire once paging has stopped",
            listOf(0, 1),
            interactor.requestedSkips
        )
    }

    // --- Review fix: teardown abandons the paging session unconditionally -- it is a no-op
    // for completed sessions and still cleans up paths where the UI state never recorded
    // more pages (malformed/empty non-advancing pages). ---

    @Test
    fun `onCleared abandons the paging session even when hasMorePages is false`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA), firstPageHasMore = false)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CountryServersViewModel(
                interactor = interactor,
                connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
                logger = FakeLogger(),
                favoritesStore = FakeFavoritesServerStore()
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[CountryServersViewModel::class.java]

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertFalse(vm.state.value.hasMorePages)

        store.clear()

        assertEquals(1, interactor.abandonPagingSessionCallCount)
    }

    // --- Review fix: servers without a stable id must not collapse onto the shared 0 key
    // when pages are merged. ---

    @Test
    fun `merge keeps zero-id servers as distinct rows across pages`() = runTest {
        val zeroIdA = server("France", "FR", 1, id = 0).copy(ip = "10.0.0.100", configData = "CFG-A")
        val zeroIdB = server("France", "FR", 2, id = 0).copy(ip = "10.0.0.200", configData = "CFG-B")
        val zeroIdADup = server("France", "FR", 3, id = 0).copy(ip = "10.0.0.100", configData = "CFG-A")
        val stable = server("France", "FR", 4, id = 5)
        val interactor = FakeInteractor(
            loaded = listOf(zeroIdA),
            firstPageHasMore = true,
            nextPageServers = listOf(zeroIdB, zeroIdADup, stable),
            nextPageHasMore = false
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(
            "zero-id entries keep a fallback identity: distinct ips stay, the duplicate is dropped",
            listOf(zeroIdA, zeroIdB, stable),
            vm.state.value.servers
        )
    }

    // --- Review fix: incremental loads must drain advancing empty pages (blank-configData
    // entries) exactly like the initial load, otherwise a user parked at the loaded end can
    // never trigger another scroll callback and the remaining servers stay unreachable. ---

    @Test
    fun `loadNextPage drains advancing empty pages until a displayable page arrives`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageSequence = listOf(
                CountryServersPage(servers = emptyList(), hasMore = true, nextSkip = 2),
                CountryServersPage(servers = listOf(serverB), hasMore = false, nextSkip = 3)
            )
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(
            "advancing empty pages must be drained within the same trigger",
            listOf(0, 1, 2),
            interactor.requestedSkips
        )
        assertEquals(listOf(serverA, serverB), vm.state.value.servers)
        assertFalse(vm.state.value.hasMorePages)
        assertFalse(vm.state.value.isLoadingMore)
    }

    // --- Review fix: the drain is bounded per trigger (a degenerate backend must not turn one
    // scroll trigger into an unbounded request burst), and it continues through pages that
    // contribute no new rows (duplicate-only pages), preserving the cursor for the next
    // trigger. ---

    @Test
    fun `loadNextPage bounds the empty-page drain per trigger and preserves the cursor`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageSequence = (1..12).map { i ->
                CountryServersPage(servers = emptyList(), hasMore = true, nextSkip = i * 50 + 1)
            }
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(
            "drain must stop at the per-trigger bound (initialize + first fetch + MAX_EMPTY_PAGE_DRAIN drains)",
            12,
            interactor.requestedSkips.size
        )
        assertTrue(
            "the cursor must be preserved so the next trigger continues draining",
            vm.state.value.hasMorePages
        )
        assertFalse(vm.state.value.isLoadingMore)
    }

    @Test
    fun `loadNextPage drains pages that contribute only duplicates`() = runTest {
        val serverA = server("France", "FR", 1, id = 10).copy(ip = "10.0.0.100", configData = "CFG-A")
        val duplicateOfA = server("France", "FR", 2, id = 10).copy(ip = "10.0.0.100", configData = "CFG-A")
        val serverB = server("France", "FR", 3, id = 20).copy(ip = "10.0.0.200", configData = "CFG-B")
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageSequence = listOf(
                CountryServersPage(servers = listOf(duplicateOfA), hasMore = true, nextSkip = 2),
                CountryServersPage(servers = listOf(serverB), hasMore = false, nextSkip = 3)
            )
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(
            "a duplicate-only page must be drained to reach the later unique server",
            listOf(0, 1, 2),
            interactor.requestedSkips
        )
        assertEquals(listOf(serverA, serverB), vm.state.value.servers)
        assertFalse(vm.state.value.hasMorePages)
    }

    // --- once every server has loaded, no further fetch is triggered and no indicator shows ---

    @Test
    fun `LoadNextPage is a no-op once hasMorePages is false`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA), firstPageHasMore = false)
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertFalse(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals("only the initial skip=0 call should ever happen", listOf(0), interactor.requestedSkips)
        assertFalse(vm.state.value.isLoadingMore)
        assertTrue(vm.state.value.items.none { it is ServerListItem.LoadingFooter })
    }

    // --- a mid-scroll page failure shows a retry affordance; retrying resumes from the
    // same page ---

    @Test
    fun `page fetch failure mid-scroll surfaces retry state instead of a silent stall`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            pageErrorAtSkip = 1
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertTrue(vm.state.value.pageLoadError)
        assertFalse(vm.state.value.isLoadingMore)
        // The failed page must not have been appended -- only the first page's server remains.
        assertEquals(listOf(serverA), vm.state.value.servers)
        // hasMorePages must still be true so a retry is actually attempted, not silently dropped.
        assertTrue(vm.state.value.hasMorePages)
        assertEquals(
            ServerListItem.LoadingFooter(FooterState.ERROR),
            vm.state.value.items.last()
        )
    }

    @Test
    fun `RetryLoadNextPage resumes from the same skip that failed`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            pageErrorAtSkip = 1
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()
        assertTrue(vm.state.value.pageLoadError)

        vm.onAction(CountryServersAction.RetryLoadNextPage)
        advanceUntilIdle()

        // Same skip (1) requested again -- retry resumes from the same page, it does not
        // advance past the failed one.
        assertEquals(listOf(0, 1, 1), interactor.requestedSkips)
        assertTrue("retry against the same fixture fails again the same way", vm.state.value.pageLoadError)
    }

    @Test
    fun `RetryLoadNextPage is a no-op without a prior page error`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA), firstPageHasMore = false)
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.RetryLoadNextPage)
        advanceUntilIdle()

        assertEquals(listOf(0), interactor.requestedSkips)
    }

    // --- a favorited server appears in the pinned section once its page has loaded,
    // not eagerly before that ---

    @Test
    fun `favorited server from a later page appears only once that page has loaded`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val favoritedServerB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(favoritedServerB),
            nextPageHasMore = false
        )
        val favoritesStore = FakeFavoritesServerStore(setOf(20))
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = favoritesStore
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        // Before the second page loads, the favorite (on page 2) is not present at all, so no
        // pinned section should be rendered yet -- not a separate eager favorites fetch.
        assertTrue(vm.state.value.items.none { it is ServerListItem.SectionHeader })

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items[0] is ServerListItem.SectionHeader)
        val pinnedRow = items[1] as ServerListItem.ServerRow
        assertEquals(20, pinnedRow.server.id)
        assertTrue(pinnedRow.isFavorite)
    }

    // --- onCleared calls abandonPagingSession (session-keyed; a safe no-op
    // for sessions that already completed), and it releases exactly this screen's session. ---

    @Test
    fun `onCleared calls abandonPagingSession when hasMorePages is true at teardown`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA), firstPageHasMore = true)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CountryServersViewModel(
                interactor = interactor,
                connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
                logger = FakeLogger(),
                favoritesStore = FakeFavoritesServerStore()
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[CountryServersViewModel::class.java]

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        store.clear()

        assertEquals(1, interactor.abandonPagingSessionCallCount)
        assertTrue("teardown must forward this screen's paging session id", !interactor.lastAbandonedSessionId.isNullOrBlank())
    }

    @Test
    fun `onCleared abandons unconditionally even when hasMorePages is false`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val interactor = FakeInteractor(loaded = listOf(serverA), firstPageHasMore = false)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CountryServersViewModel(
                interactor = interactor,
                connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
                logger = FakeLogger(),
                favoritesStore = FakeFavoritesServerStore()
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[CountryServersViewModel::class.java]

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertFalse(vm.state.value.hasMorePages)

        store.clear()

        assertEquals(
            "teardown abandons unconditionally: safe no-op for completed sessions, cleanup for early-release paths",
            1,
            interactor.abandonPagingSessionCallCount
        )
    }

    // --- an empty-after-filtering first page with hasMore=true must not finish the screen;
    // it must keep paging until real servers are found ---

    @Test
    fun `empty filtered first page with hasMore keeps loading until real servers appear`() = runTest {
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = emptyList(),
            firstPageHasMore = true,
            firstPageNextSkip = 50,
            nextPageServers = listOf(serverB),
            nextPageHasMore = false
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        assertEquals("must keep paging past the empty-after-filtering first page", listOf(0, 50), interactor.requestedSkips)
        assertEquals(listOf(serverB), vm.state.value.servers)
        assertFalse(vm.state.value.hasMorePages)
        assertTrue(
            "an empty-but-hasMore first page must not finish/cancel the screen",
            effects.first() is CountryServersEffect.FocusFirstItem
        )
        job.cancel()
    }

    // --- offset-based pagination can re-deliver the same server id at a shifted offset; the
    // ViewModel must de-dup by id when appending a newly-fetched page ---

    @Test
    fun `duplicate server id across two pages does not appear twice in state servers`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val serverADuplicate = server("France", "FR", 1, id = 10)
        val serverB = server("France", "FR", 2, id = 20)
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            nextPageServers = listOf(serverADuplicate, serverB),
            nextPageHasMore = false
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        vm.onAction(CountryServersAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(listOf(10, 20), vm.state.value.servers.map { it.id })
        assertEquals(2, vm.state.value.servers.size)
        assertEquals("the first (earliest-loaded) occurrence must be kept", serverA, vm.state.value.servers[0])
    }

    // --- the loop must not re-issue an identical request when nextSkip does not advance ---

    @Test
    fun `empty first page whose nextSkip does not advance stops instead of looping forever`() = runTest {
        val interactor = FakeInteractor(
            loaded = emptyList(),
            firstPageHasMore = true,
            firstPageNextSkip = 0,
            // Escape hatch so an *unfixed* ViewModel still terminates the test (with a call
            // count that fails the assertion below) instead of looping forever.
            firstPageHasMoreCallLimit = 5
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )

        val effects = mutableListOf<CountryServersEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(2).toList(effects) }

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()

        assertEquals(
            "must not re-issue an identical request when nextSkip does not advance",
            listOf(0),
            interactor.requestedSkips
        )
        assertTrue(effects[0] is CountryServersEffect.ShowToast)
        assertTrue(effects[1] is CountryServersEffect.FinishCanceled)
        job.cancel()
    }

    // --- pin the ViewModel -> interactor wiring (hasMorePages/nextSkip forwarding) ---

    @Test
    fun `server selection forwards hasMorePages true and the current nextSkip to resolveSelection`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val result = ServerSelectionResult("France", "FR", "Paris", "cfg", "1.2.3.4")
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            firstPageNextSkip = 42,
            selectionResult = result
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)
        assertEquals(42, vm.state.value.nextSkip)

        vm.onAction(CountryServersAction.ServerSelected(serverA))
        advanceUntilIdle()

        assertEquals(true, interactor.lastResolveSelectionHasMorePages)
        assertEquals(42, interactor.lastResolveSelectionNextSkip)
    }

    @Test
    fun `server selection from a complete list forwards hasMorePages false`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val result = ServerSelectionResult("France", "FR", "Paris", "cfg", "1.2.3.4")
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = false,
            selectionResult = result
        )
        val vm = CountryServersViewModel(
            interactor = interactor,
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeLogger(),
            favoritesStore = FakeFavoritesServerStore()
        )
        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertFalse(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.ServerSelected(serverA))
        advanceUntilIdle()

        assertEquals(false, interactor.lastResolveSelectionHasMorePages)
    }

    // --- a completed selection must still abandon the foreground paging session in
    // onCleared(). resolveSelection()'s background backfill fetches with
    // accumulate = false, so it is fully session-isolated from ServersV2Repository's shared
    // pageAccumulators and can never release this screen's own accumulator entry on its behalf.
    // onCleared() is therefore the *only* remaining release path, for every teardown reason
    // including a completed selection -- skipping it here leaked the foreground accumulator's full configData
    // blobs for the process lifetime on every early selection. ---

    @Test
    fun `onCleared abandons paging session after a completed selection with hasMorePages true`() = runTest {
        val serverA = server("France", "FR", 1, id = 10)
        val result = ServerSelectionResult("France", "FR", "Paris", "cfg", "1.2.3.4")
        val interactor = FakeInteractor(
            loaded = listOf(serverA),
            firstPageHasMore = true,
            selectionResult = result
        )
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CountryServersViewModel(
                interactor = interactor,
                connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
                logger = FakeLogger(),
                favoritesStore = FakeFavoritesServerStore()
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[CountryServersViewModel::class.java]

        vm.onAction(CountryServersAction.Initialize(countryName = "France", countryCode = "FR", pageSize = 50))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMorePages)

        vm.onAction(CountryServersAction.ServerSelected(serverA))
        advanceUntilIdle()

        store.clear()

        assertEquals(
            "onCleared() is the only release path left for the foreground accumulator once the " +
                "backfill is session-isolated (accumulate=false); it must abandon regardless of " +
                "whether a selection completed",
            1,
            interactor.abandonPagingSessionCallCount
        )
        assertTrue("teardown must forward this screen's paging session id", !interactor.lastAbandonedSessionId.isNullOrBlank())
    }
}

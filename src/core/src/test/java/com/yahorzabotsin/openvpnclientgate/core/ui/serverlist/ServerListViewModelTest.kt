package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import kotlinx.coroutines.CancellationException
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesCountryStore
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
    fun `init_v2_source_cancellation_is_rethrown`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            getError = CancellationException("cancelled")
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val logger = CountingLogger()
        val vm = ServerListViewModel(interactor, connection, logger, FakeFavoritesCountryStore())

        advanceUntilIdle()

        // CancellationException must not be swallowed as an error — no snackbar and no error log
        assertEquals(0, logger.loadErrorCalls)
        assertEquals(false, vm.state.value.isLoading)
        assertEquals(0, vm.state.value.countries.size)
    }

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
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.countries.size)
        assertEquals("Canada", state.countries[0].country.name)
        assertEquals(1, state.countries[0].serverCount)
        val effect = effects.first()
        assertTrue(effect is ServerListEffect.FocusFirstItem)
        assertEquals(0, (effect as ServerListEffect.FocusFirstItem).adapterPosition)
        job.cancel()
    }

    @Test
    fun `load error emits snackbar effect`() = runTest {
        val interactor = FakeInteractor(getError = IOException("boom"))
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())

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
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())
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
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())
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
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())
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
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())

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
        val effect = effects.first()
        assertTrue(effect is ServerListEffect.FocusFirstItem)
        assertEquals(0, (effect as ServerListEffect.FocusFirstItem).adapterPosition)
        job.cancel()
    }

    @Test
    fun `init_v2_source_load_error_emits_snackbar`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            getError = IOException("v2 boom")
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())

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
    fun `init_v2_source_network_no_cache_error_is_suppressed`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            getError = IOException("getCountries[locale=ru]: network failed and no cache available")
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val logger = CountingLogger()
        val vm = ServerListViewModel(interactor, connection, logger, FakeFavoritesCountryStore())

        advanceUntilIdle()

        assertEquals(0, vm.state.value.countries.size)
        assertEquals(0, logger.loadErrorCalls)
    }

    @Test
    fun `paused state is treated as vpn connected`() = runTest {
        val interactor = FakeInteractor(loaded = emptyList())
        val connection = FakeConnectionProvider(ConnectionState.PAUSED)
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), FakeFavoritesCountryStore())

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

    private class CountingLogger : ServerListLogger {
        var loadErrorCalls: Int = 0

        override fun logLoadSuccess(count: Int) = Unit

        override fun logLoadError(error: Exception) {
            loadErrorCalls += 1
        }

        override fun logNoServers(countryName: String) = Unit
        override fun logSelectionError(countryName: String, error: Exception) = Unit
    }

    private class FakeFavoritesCountryStore(
        initialFavorites: Set<String> = emptySet()
    ) : FavoritesCountryStore {
        private val favorites = initialFavorites.toMutableSet()

        override fun getFavoriteCountryCodes(): Set<String> = favorites.toSet()

        override fun isFavoriteCountry(countryCode: String): Boolean =
            favorites.any { it.equals(countryCode, ignoreCase = true) }

        override fun addFavoriteCountry(countryCode: String) {
            favorites.add(countryCode)
        }

        override fun removeFavoriteCountry(countryCode: String) {
            favorites.removeAll { it.equals(countryCode, ignoreCase = true) }
        }
    }

    /**
     * Mirrors the real [com.yahorzabotsin.openvpnclientgate.core.servers.DefaultFavoritesCountryStore]
     * -> [com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesStore] delegation *before* the
     * case-normalization fix: an exact, case-sensitive `Set<String>.contains()` lookup, storing
     * whatever casing is passed in verbatim. Used to prove the fixed [FavoritesCountryStore]
     * implementations agree with the case-insensitive display filter in `buildItems()` even when a
     * synced country code differs in casing from the casing originally used to favorite it.
     */
    private class CaseSensitiveFavoritesCountryStore(
        initialFavorites: Set<String> = emptySet()
    ) : FavoritesCountryStore {
        private val favorites = initialFavorites.toMutableSet()

        override fun getFavoriteCountryCodes(): Set<String> = favorites.toSet()

        override fun isFavoriteCountry(countryCode: String): Boolean =
            favorites.contains(countryCode)

        override fun addFavoriteCountry(countryCode: String) {
            favorites.add(countryCode)
        }

        override fun removeFavoriteCountry(countryCode: String) {
            favorites.remove(countryCode)
        }
    }

    // --- SUB-02 acceptance criteria: pinned favorites section + long-press toggle ---

    @Test
    fun `AC1 - pinned favorites section appears above regular list when a favorite is available`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "CA", name = "Canada", serverCount = 3),
                CountryV2(code = "US", name = "United States", serverCount = 5)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore(setOf("US"))
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items[0] is CountryListItem.SectionHeader)
        val favoriteRow = items[1] as CountryListItem.CountryRow
        assertEquals("United States", favoriteRow.countryWithServers.country.name)
        assertTrue(favoriteRow.isFavorite)
        // regular list still contains both countries afterwards, alphabetically
        assertEquals("Canada", (items[2] as CountryListItem.CountryRow).countryWithServers.country.name)
        assertEquals("United States", (items[3] as CountryListItem.CountryRow).countryWithServers.country.name)
        assertEquals(4, items.size)
    }

    @Test
    fun `AC2 - no favorites section when none available`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "CA", name = "Canada", serverCount = 3),
                CountryV2(code = "US", name = "United States", serverCount = 5)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        // Favorite "FR" is not present in the synced list => should be filtered out entirely.
        val favoritesStore = FakeFavoritesCountryStore(setOf("FR"))
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items.none { it is CountryListItem.SectionHeader })
        assertEquals(2, items.size)
        assertTrue(items.all { it is CountryListItem.CountryRow })
    }

    @Test
    fun `AC3 - toggle favorite reflects current state via add then remove`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(CountryV2(code = "US", name = "United States", serverCount = 5))
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore()
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()

        assertTrue(vm.state.value.items.none { it is CountryListItem.SectionHeader })

        vm.onAction(ServerListAction.ToggleFavorite(Country("United States", "US")))
        advanceUntilIdle()
        assertTrue(favoritesStore.isFavoriteCountry("US"))
        assertTrue(vm.state.value.items[0] is CountryListItem.SectionHeader)

        vm.onAction(ServerListAction.ToggleFavorite(Country("United States", "US")))
        advanceUntilIdle()
        assertTrue(!favoritesStore.isFavoriteCountry("US"))
        assertTrue(vm.state.value.items.none { it is CountryListItem.SectionHeader })
    }

    @Test
    fun `AC4 - selecting a country from the favorites section navigates like the regular list`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(CountryV2(code = "US", name = "United States", serverCount = 5))
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore(setOf("US"))
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()

        val favoriteRow = vm.state.value.items[1] as CountryListItem.CountryRow

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(ServerListAction.CountrySelected(favoriteRow.countryWithServers.country))
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.OpenCountryServers)
        assertEquals("United States", (effect as ServerListEffect.OpenCountryServers).countryName)
        assertEquals("US", effect.countryCode)
        job.cancel()
    }

    @Test
    fun `AC5 - favoriting updates pinned section immediately without reload`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "CA", name = "Canada", serverCount = 3),
                CountryV2(code = "US", name = "United States", serverCount = 5)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore()
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()
        assertTrue(vm.state.value.items.none { it is CountryListItem.SectionHeader })

        vm.onAction(ServerListAction.ToggleFavorite(Country("Canada", "CA")))
        advanceUntilIdle()

        val items = vm.state.value.items
        assertTrue(items[0] is CountryListItem.SectionHeader)
        val favoriteRow = items[1] as CountryListItem.CountryRow
        assertEquals("Canada", favoriteRow.countryWithServers.country.name)
        assertTrue(favoriteRow.isFavorite)
    }

    @Test
    fun `AC3 regression - toggle agrees with pinned section display when favorite casing differs from synced country code`() = runTest {
        // Reproduces the SUB-02 review finding: a favorite persisted as "us" (lowercase) but a
        // later sync surfaces the country with code "US" (uppercase). buildItems() matches
        // favorites case-insensitively, so "US" is shown pinned as a favorite. With the fix,
        // the backing store (mirrored here by a case-sensitive fake, matching the real
        // FavoritesStore contract prior to normalization) must still agree that "US" is
        // currently favorite so the long-press toggle removes it rather than re-adding it
        // under a new casing.
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(CountryV2(code = "US", name = "United States", serverCount = 5))
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        // Store already contains the favorite under a different casing than the synced code.
        val favoritesStore = FakeFavoritesCountryStore(setOf("us"))
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()

        // Pinned favorites section renders "US" as favorited despite the casing mismatch.
        val itemsBeforeToggle = vm.state.value.items
        assertTrue(itemsBeforeToggle[0] is CountryListItem.SectionHeader)
        val favoriteRow = itemsBeforeToggle[1] as CountryListItem.CountryRow
        assertEquals("United States", favoriteRow.countryWithServers.country.name)
        assertTrue(favoriteRow.isFavorite)

        // Long-press toggle must be interpreted as "remove", not "add again under new casing".
        vm.onAction(ServerListAction.ToggleFavorite(Country("United States", "US")))
        advanceUntilIdle()

        assertTrue(!favoritesStore.isFavoriteCountry("US"))
        assertTrue(!favoritesStore.isFavoriteCountry("us"))
        assertEquals(0, favoritesStore.getFavoriteCountryCodes().size)
        assertTrue(vm.state.value.items.none { it is CountryListItem.SectionHeader })
    }

    @Test
    fun `focus skips non-focusable section header when favorites exist`() = runTest {
        // Regression test for P2 finding: when a SectionHeader is inserted at position 0,
        // TV/keyboard users need focus on the first CountryRow (position 1), not the header.
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "US", name = "United States", serverCount = 5),
                CountryV2(code = "CA", name = "Canada", serverCount = 3)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore(setOf("US"))
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.FocusFirstItem)
        // Focus position should be 1 (the first CountryRow after the SectionHeader at 0)
        assertEquals(1, (effect as ServerListEffect.FocusFirstItem).adapterPosition)
        job.cancel()
    }

    @Test
    fun `focus position 0 when no favorites section exists`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "US", name = "United States", serverCount = 5),
                CountryV2(code = "CA", name = "Canada", serverCount = 3)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore()  // No favorites
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.FocusFirstItem)
        // Focus position should be 0 (no header, so first item is a CountryRow)
        assertEquals(0, (effect as ServerListEffect.FocusFirstItem).adapterPosition)
        job.cancel()
    }

    @Test
    fun `AC6 - short-tap navigation for non-favorite countries is unchanged`() = runTest {
        val interactor = FakeInteractor(
            v2Source = true,
            countriesV2 = listOf(
                CountryV2(code = "CA", name = "Canada", serverCount = 3),
                CountryV2(code = "US", name = "United States", serverCount = 5)
            )
        )
        val connection = FakeConnectionProvider(ConnectionState.DISCONNECTED)
        val favoritesStore = FakeFavoritesCountryStore(setOf("US"))
        val vm = ServerListViewModel(interactor, connection, FakeLogger(), favoritesStore)
        advanceUntilIdle()

        val effects = mutableListOf<ServerListEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.take(1).toList(effects) }

        vm.onAction(ServerListAction.CountrySelected(Country("Canada", "CA")))
        advanceUntilIdle()

        val effect = effects.first()
        assertTrue(effect is ServerListEffect.OpenCountryServers)
        assertEquals("Canada", (effect as ServerListEffect.OpenCountryServers).countryName)
        assertEquals("CA", effect.countryCode)
        job.cancel()
    }

}

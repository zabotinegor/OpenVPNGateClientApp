package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersPageResponse
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Api
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Repository
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerV2
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for DEFAULT_V2 initial selection parity (AC-1, TS-1, TS-2).
 */
@RunWith(RobolectricTestRunner::class)
class MainSelectionInteractorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("v2_") }?.forEach { it.delete() }
        UserSettingsStore.save(context, UserSettings(serverSource = ServerSource.DEFAULT_V2))
    }

    // TS-1: DEFAULT_V2 startup with empty store loads countries, hydrates first country server
    // list, saves selection, and returns a usable initial selection for the main screen.
    @Test
    fun loadInitialSelection_v2_empty_store_returns_first_country_first_server() = runBlocking {
        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("JP", "Japan", 2)),
            serversPerCountry = mapOf(
                "JP" to listOf(
                    ServerV2("1.2.3.4", "JP", "Japan", "config-jp-1"),
                    ServerV2("1.2.3.5", "JP", "Japan", "config-jp-2")
                )
            )
        )
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Japan", result!!.country)
        assertEquals("config-jp-1", result.config)
        assertEquals("JP", result.countryCode)
        assertEquals("1.2.3.4", result.ip)

        // Store must be populated so VPN and auto-switch flows can use it
        val stored = SelectedCountryStore.currentServer(context)
        assertNotNull(stored)
        assertEquals("config-jp-1", stored!!.config)
        assertEquals(2, SelectedCountryStore.getServers(context).size)
        assertEquals("Japan", SelectedCountryStore.getSelectedCountry(context))
    }

    // TS-2: DEFAULT_V2 startup with an already stored current server reuses the stored
    // selection without replacing it with the first country / first server.
    @Test
    fun loadInitialSelection_v2_with_stored_selection_reuses_stored_server() = runBlocking {
        // Pre-populate store as if a previous session had selected the second server
        SelectedCountryStore.saveSelection(
            context, "Germany",
            listOf(
                makeStoredServer("config-de-1", "DE", "1.0.0.1"),
                makeStoredServer("config-de-2", "DE", "1.0.0.2")
            )
        )
        SelectedCountryStore.setCurrentIndex(context, 1)

        // The API would return Japan as first country, but we should NOT reset to it
        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("JP", "Japan", 1)),
            serversPerCountry = mapOf("JP" to listOf(ServerV2("9.9.9.9", "JP", "Japan", "config-jp-1")))
        )
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        // Must reuse stored selection, not the API first country
        assertEquals("Germany", result!!.country)
        assertEquals("config-de-2", result.config)
        assertEquals("1.0.0.2", result.ip)

        // Store index must remain at the second server
        assertEquals(1, SelectedCountryStore.getCurrentIndex(context))
    }

    @Test
    fun loadInitialSelection_v2_with_position_like_city_rehydrates_from_v2_servers() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "Belarus",
            listOf(
                makeStoredServer(
                    config = "legacy-config",
                    countryCode = "BY",
                    ip = "213.184.224.127",
                    city = "1/1"
                )
            )
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("BY", "Belarus", 2)),
            serversPerCountry = mapOf(
                "BY" to listOf(
                    ServerV2(
                        ip = "213.184.224.127",
                        countryCode = "BY",
                        countryName = "Belarus",
                        configData = "v2-config",
                        city = "Minsk",
                        utc = "UTC+3"
                    ),
                    ServerV2(
                        ip = "213.184.224.128",
                        countryCode = "BY",
                        countryName = "Belarus",
                        configData = "v2-config-2",
                        city = "Grodno",
                        utc = "UTC+3"
                    )
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Belarus", result!!.country)
        assertEquals("Minsk", result.city)
        assertEquals("v2-config", result.config)
        assertEquals("BY", result.countryCode)
        assertEquals("213.184.224.127", result.ip)

        val stored = SelectedCountryStore.currentServer(context)
        assertNotNull(stored)
        assertEquals("Minsk", stored!!.city)
        assertEquals("UTC+3", stored.utc)
        assertTrue(SelectedCountryStore.getServers(context).size >= 2)
    }

    @Test
    fun loadInitialSelection_v2_hydration_selects_correct_server_by_config_when_all_share_same_ip() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "Belarus",
            listOf(makeStoredServer(config = "by-3", countryCode = "BY", ip = "213.184.224.127"))
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("BY", "Belarus", 3)),
            serversPerCountry = mapOf(
                "BY" to listOf(
                    ServerV2("213.184.224.127", "BY", "Belarus", "by-1", city = "Minsk"),
                    ServerV2("213.184.224.127", "BY", "Belarus", "by-2", city = "Minsk"),
                    ServerV2("213.184.224.127", "BY", "Belarus", "by-3", city = "Minsk")
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("by-3", result!!.config)
        assertEquals(2, SelectedCountryStore.getCurrentIndex(context))
    }

    // TS-3: Position-like city "–/–" triggers V2 rehydration to replace stale server position text.
    @Test
    fun loadInitialSelection_v2_with_dash_slash_dash_city_rehydrates_from_v2_servers() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(
                makeStoredServer(config = "legacy-fr", countryCode = "FR", ip = "1.2.3.4", city = "--/--")
            )
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("FR", "France", 1)),
            serversPerCountry = mapOf(
                "FR" to listOf(
                    ServerV2(
                        ip = "1.2.3.4",
                        countryCode = "FR",
                        countryName = "France",
                        configData = "v2-fr",
                        city = "Paris",
                        utc = "UTC+1"
                    )
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Paris", result!!.city)
        assertEquals("v2-fr", result.config)
    }

    @Test
    fun loadInitialSelection_v2_with_em_dash_placeholder_city_rehydrates_from_v2_servers() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(
                makeStoredServer(config = "legacy-fr", countryCode = "FR", ip = "1.2.3.4", city = "\u2014/\u2014")
            )
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("FR", "France", 1)),
            serversPerCountry = mapOf(
                "FR" to listOf(
                    ServerV2(
                        ip = "1.2.3.4",
                        countryCode = "FR",
                        countryName = "France",
                        configData = "v2-fr",
                        city = "Paris",
                        utc = "UTC+1"
                    )
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Paris", result!!.city)
        assertEquals("v2-fr", result.config)
    }

    @Test
    fun loadInitialSelection_v2_with_blank_stored_city_rehydrates_from_v2_servers() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(
                makeStoredServer(config = "legacy-fr", countryCode = "FR", ip = "1.2.3.4", city = "")
            )
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("FR", "France", 1)),
            serversPerCountry = mapOf(
                "FR" to listOf(
                    ServerV2(
                        ip = "1.2.3.4",
                        countryCode = "FR",
                        countryName = "France",
                        configData = "v2-fr",
                        city = "Paris",
                        utc = "UTC+1"
                    )
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Paris", result!!.city)
        assertEquals("v2-fr", result.config)
    }

    // TS-4: Position-like city when V2 repo is absent — falls back to stored selection as-is.
    @Test
    fun loadInitialSelection_v2_position_like_city_without_v2_repo_falls_back_to_stored() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "Japan",
            listOf(
                makeStoredServer(config = "cfg-jp", countryCode = "JP", ip = "5.5.5.5", city = "3/5")
            )
        )

        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = null  // no V2 repo → hydration skipped
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        // Should fall through to stored selection without hydration
        assertNotNull(result)
        assertEquals("Japan", result!!.country)
        assertEquals("cfg-jp", result.config)
    }

    // TS-5: Hydration with empty server list for matched country returns null and
    // loadInitialSelection falls back to the raw stored selection.
    @Test
    fun loadInitialSelection_v2_hydration_empty_server_list_falls_back_to_stored() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "Italy",
            listOf(
                makeStoredServer(config = "cfg-it", countryCode = "IT", ip = "9.9.9.1", city = "2/3")
            )
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("IT", "Italy", 0)),
            serversPerCountry = mapOf("IT" to emptyList())  // empty server list
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        // Hydration returns null → fallback to stored position-like city entry
        assertNotNull(result)
        assertEquals("Italy", result!!.country)
        assertEquals("cfg-it", result.config)
    }

    // TS-6: Hydration with no IP match selects index 0 of the refreshed server list.
    @Test
    fun loadInitialSelection_v2_hydration_no_ip_match_selects_first_server() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "Spain",
            listOf(
                makeStoredServer(config = "old-cfg", countryCode = "ES", ip = "99.99.99.99", city = "1/2")
            )
        )

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("ES", "Spain", 2)),
            serversPerCountry = mapOf(
                "ES" to listOf(
                    ServerV2("10.0.0.1", "ES", "Spain", "v2-es-1", city = "Barcelona", utc = "UTC+1"),
                    ServerV2("10.0.0.2", "ES", "Spain", "v2-es-2", city = "Madrid", utc = "UTC+1")
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        // No IP match → index 0 → first server
        assertNotNull(result)
        assertEquals("Barcelona", result!!.city)
        assertEquals("v2-es-1", result.config)
        assertEquals("10.0.0.1", result.ip)
    }

    // Verify that null is returned gracefully when no countries are available (AC-1.3 edge case)
    @Test
    fun loadInitialSelection_v2_empty_countries_returns_null() = runBlocking {
        val v2Api = FakeServersV2Api(countries = emptyList(), serversPerCountry = emptyMap())
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNull(result)
    }

    @Test
    fun loadInitialSelection_v2_network_failure_returns_null_without_throw() = runBlocking {
        val v2Api = object : ServersV2Api {
            override suspend fun getCountries(locale: String): List<CountryV2> {
                throw IOException("network unavailable")
            }

            override suspend fun getServers(
                locale: String,
                countryCode: String,
                isActive: Boolean,
                skip: Int,
                take: Int
            ): ServersPageResponse {
                throw AssertionError("servers should not be requested when countries fail")
            }
        }
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNull(result)
    }

    @Test
    fun loadInitialSelection_v2_stored_selection_survives_country_fetch_failure() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "Japan",
            listOf(
                makeStoredServer(config = "cfg-jp", countryCode = "JP", ip = "5.5.5.5", city = "Tokyo")
            )
        )

        val v2Api = object : ServersV2Api {
            override suspend fun getCountries(locale: String): List<CountryV2> {
                throw IOException("network unavailable")
            }

            override suspend fun getServers(
                locale: String,
                countryCode: String,
                isActive: Boolean,
                skip: Int,
                take: Int
            ): ServersPageResponse {
                throw AssertionError("servers should not be requested when stored selection is reused")
            }
        }
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Japan", result!!.country)
        assertEquals("cfg-jp", result.config)
        assertEquals("5.5.5.5", result.ip)
    }

    @Test
    fun loadInitialSelection_v2_runs_network_fetch_off_caller_thread() = runBlocking {
        val callerThreadName = Thread.currentThread().name
        var countriesThreadName: String? = null
        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("JP", "Japan", 1)),
            serversPerCountry = mapOf(
                "JP" to listOf(
                    ServerV2("1.2.3.4", "JP", "Japan", "cfg-jp")
                )
            ),
            threadRecorder = { method, threadName ->
                if (method == "countries") countriesThreadName = threadName
            }
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertNotNull(countriesThreadName)
        assertTrue(countriesThreadName != callerThreadName)
    }

    // Regression: hydration is a deferred write. MainViewModel.onStoreVersionChanged() enters it
    // with a selection captured before the country/server loads, so a country picked while those
    // loads are in flight must not be reverted by the hydration write that lands afterwards.
    @Test
    fun loadInitialSelection_v2_hydration_does_not_revert_a_country_chosen_mid_flight() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(makeStoredServer(config = "legacy-fr", countryCode = "FR", ip = "1.2.3.4", city = ""))
        )

        val v2Api = object : ServersV2Api {
            override suspend fun getCountries(locale: String): List<CountryV2> =
                listOf(CountryV2("FR", "France", 1), CountryV2("DE", "Germany", 1))

            override suspend fun getServers(
                locale: String,
                countryCode: String,
                isActive: Boolean,
                skip: Int,
                take: Int
            ): ServersPageResponse {
                // The user picks Germany while this hydration fetch for France is in flight.
                SelectedCountryStore.saveSelection(
                    context,
                    "Germany",
                    listOf(makeStoredServer(config = "cfg-de", countryCode = "DE", ip = "8.8.8.8", city = "Berlin"))
                )
                val items = listOf(
                    ServerV2("1.2.3.4", "FR", "France", "v2-fr", city = "Paris", utc = "UTC+1")
                )
                return ServersPageResponse(items = items, total = items.size)
            }
        }
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        // The newer choice must survive both the hydration write and the returned selection.
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(context))
        assertEquals("cfg-de", SelectedCountryStore.currentServer(context)?.config)
        assertNull(result)
    }

    // The hydration *list* write is guarded, but setCurrentIndex and the returned
    // InitialSelection still ran after that critical section.
    // A selection committed in that gap got the new country's server pool persisted with an index
    // measured against the OLD (discarded) list -- so the persisted "current server" pointed at an
    // arbitrary entry of a country the index was never computed against -- and the caller still
    // received the stale country/server pairing.
    //
    // Reproduction: the hydration is parked at its FIRST vpn_selection_prefs write (the guarded
    // server-list write) and a foreground selection is queued behind it, confirmed BLOCKED on the
    // monitor. Releasing the park then re-opens the only interesting question: can that queued
    // selection land BEFORE the hydration's index write? The test records exactly that at the
    // moment the index write is reached. With list and index written under one continuously-held
    // monitor the answer is always no; with the index write outside it, the queued selection is
    // free to slip in, and the France index then lands on Germany's pool.
    @Test(timeout = 30_000)
    fun loadInitialSelection_v2_hydration_indexWrite_cannotInterleaveWithNewerSelection() {
        val germanyServers = listOf(
            makeStoredServer(config = "cfg-de-1", countryCode = "DE", ip = "8.8.8.1", city = "Berlin"),
            makeStoredServer(config = "cfg-de-2", countryCode = "DE", ip = "8.8.8.2", city = "Hamburg"),
            makeStoredServer(config = "cfg-de-3", countryCode = "DE", ip = "8.8.8.3", city = "Munich")
        )
        // Stored France selection with a placeholder city, so startup takes the hydration path.
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(makeStoredServer(config = "cfg-fr-2", countryCode = "FR", ip = "1.2.3.5", city = ""))
        )

        val selectionEdits = AtomicInteger(0)
        val hydrationParkedAtListWrite = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val hydrationReachedIndexWrite = CountDownLatch(1)
        val newerSelectionCompleted = CountDownLatch(1)
        val newerSelectionLandedBeforeIndexWrite = AtomicBoolean(false)
        val revalidateArmed = AtomicBoolean(true)

        val instrumentedCtx = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != "vpn_selection_prefs") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        when (selectionEdits.incrementAndGet()) {
                            // 1st write = the guarded server-list write. Park here so the racer
                            // can be queued on the monitor with certainty.
                            1 -> {
                                hydrationParkedAtListWrite.countDown()
                                releaseHydration.await(20, TimeUnit.SECONDS)
                            }
                            // 2nd write = the dependent index write. Sample whether the queued
                            // selection managed to land in between.
                            2 -> {
                                newerSelectionLandedBeforeIndexWrite.set(
                                    newerSelectionCompleted.count == 0L
                                )
                                hydrationReachedIndexWrite.countDown()
                            }
                        }
                        return delegate.edit()
                    }

                    override fun getString(key: String?, defValue: String?): String? {
                        // The post-write revalidation. That revalidation runs under the selection
                        // monitor, so waiting here for the queued racer thread would deadlock it;
                        // land the newer selection inline on this thread instead (the monitor is
                        // reentrant). Effect is the same as the racer winning the monitor first:
                        // the revalidation observes a selection newer than the hydrated one.
                        if (key == "selected_country" && selectionEdits.get() >= 2 &&
                            revalidateArmed.compareAndSet(true, false)
                        ) {
                            SelectedCountryStore.saveSelection(context, "Germany", germanyServers)
                        }
                        return delegate.getString(key, defValue)
                    }
                }
            }
        }

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("FR", "France", 2), CountryV2("DE", "Germany", 3)),
            serversPerCountry = mapOf(
                "FR" to listOf(
                    ServerV2("1.2.3.4", "FR", "France", "cfg-fr-1", city = "Paris", utc = "UTC+1"),
                    ServerV2("1.2.3.5", "FR", "France", "cfg-fr-2", city = "Lyon", utc = "UTC+1")
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = instrumentedCtx,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = java.util.concurrent.atomic.AtomicReference<InitialSelection?>(null)
        val hydration = Thread {
            result.set(runBlocking { interactor.loadInitialSelection(cacheOnly = false) })
        }
        hydration.start()
        assertTrue(
            "hydration never reached its server-list write",
            hydrationParkedAtListWrite.await(20, TimeUnit.SECONDS)
        )

        // The user picks Germany while the hydration sits at its server-list write.
        val newerSelection = Thread {
            SelectedCountryStore.saveSelection(context, "Germany", germanyServers)
            newerSelectionCompleted.countDown()
        }
        newerSelection.start()
        assertTrue(
            "the newer selection never queued on the selection monitor",
            awaitBlocked(newerSelection)
        )
        assertFalse(
            "a newer selection must not complete while a guarded write holds the lock",
            newerSelectionCompleted.count == 0L
        )

        // Release the hydration with the newer selection queued on the monitor: whichever of the
        // two takes it next, the index write must not observe a foreign server pool.
        releaseHydration.countDown()
        assertTrue(
            "hydration never reached its index write",
            hydrationReachedIndexWrite.await(20, TimeUnit.SECONDS)
        )
        newerSelection.join(20_000)
        hydration.join(20_000)

        assertFalse(
            "the newer selection slipped between the hydration's server-list write and its " +
                "dependent index write -- the index was resolved against a list that is no " +
                "longer the persisted one",
            newerSelectionLandedBeforeIndexWrite.get()
        )
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(context))
        assertEquals(
            listOf("cfg-de-1", "cfg-de-2", "cfg-de-3"),
            SelectedCountryStore.getServers(context).map { it.config }
        )
        // The hydration's index (1, resolved against France's list) must not have been applied to
        // Germany's pool: the newer selection's own index=0 is the one that stands.
        assertEquals("cfg-de-1", SelectedCountryStore.currentServer(context)?.config)
        // ...and the superseded hydration must not be reported to the caller as current.
        assertNull(result.get())
    }

    // Regression: the post-write freshness check used to compare only the country name. Choosing a
    // different server *inside the same country* after the atomic hydration write releases the
    // monitor leaves the selected country equal, so the stale pairing passed the check and was
    // handed back to the caller, which reconnects to the superseded server.
    //
    // Reproduction: the hydration writes France's pool with index 1 (the stored config), and the
    // same-country switch to index 0 is injected exactly at the post-write revalidation's read of
    // the selected country -- on the hydration thread, so it lands after the write and before the
    // check, deterministically rather than by timing. With a country-only check the interactor
    // returns the superseded server; with the server identity revalidated it must return null.
    @Test(timeout = 30_000)
    fun loadInitialSelection_v2_hydration_discards_result_when_same_country_server_changes() {
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(makeStoredServer(config = "v2-fr-2", countryCode = "FR", ip = "1.2.3.5", city = ""))
        )

        val selectionEdits = AtomicInteger(0)
        val switchArmed = AtomicBoolean(true)
        val sameCountrySwitchApplied = AtomicBoolean(false)

        val instrumentedCtx = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != "vpn_selection_prefs") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        selectionEdits.incrementAndGet()
                        return delegate.edit()
                    }

                    override fun getString(key: String?, defValue: String?): String? {
                        // After the list+index write pair, the next read of the selected country is
                        // the post-write revalidation. The user picks another server of the SAME
                        // country right there.
                        if (key == "selected_country" && selectionEdits.get() >= 2 &&
                            switchArmed.compareAndSet(true, false)
                        ) {
                            SelectedCountryStore.setCurrentIndex(context, 0)
                            sameCountrySwitchApplied.set(true)
                        }
                        return delegate.getString(key, defValue)
                    }
                }
            }
        }

        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("FR", "France", 2)),
            serversPerCountry = mapOf(
                "FR" to listOf(
                    ServerV2("1.2.3.4", "FR", "France", "v2-fr-1", city = "Paris", utc = "UTC+1"),
                    ServerV2("1.2.3.5", "FR", "France", "v2-fr-2", city = "Lyon", utc = "UTC+1")
                )
            )
        )
        val interactor = DefaultMainSelectionInteractor(
            appContext = instrumentedCtx,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = runBlocking { interactor.loadInitialSelection(cacheOnly = false) }

        assertTrue(
            "the same-country server switch never fired, the race window was not entered",
            sameCountrySwitchApplied.get()
        )
        // The user's newer, same-country choice stands...
        assertEquals("France", SelectedCountryStore.getSelectedCountry(context))
        assertEquals("v2-fr-1", SelectedCountryStore.currentServer(context)?.config)
        // ...and the superseded server must not be reported to the caller as current.
        assertNull(result)
    }

    // Regression: the *write-time* guard used to compare only the country name. When the user picks
    // a different server of the SAME country while the hydration fetch is still in flight, the
    // country stays equal, so the guarded write landed anyway -- persisting the pool with the index
    // of the captured (now superseded) server and reverting the user's choice at the write itself.
    // The post-write freshness check could not catch it either: it revalidates against the very
    // server this write had just persisted, so it saw a "current" selection and handed the stale
    // result back to the caller, which reconnects to it.
    //
    // Reproduction: the same-country switch is injected inside the in-flight servers fetch -- i.e.
    // strictly before the guarded write -- so it is deterministic rather than timing-dependent.
    @Test
    fun loadInitialSelection_v2_hydration_does_not_revert_a_same_country_server_chosen_mid_flight() = runBlocking {
        SelectedCountryStore.saveSelection(
            context,
            "France",
            listOf(
                makeStoredServer(config = "v2-fr-1", countryCode = "FR", ip = "1.2.3.4", city = ""),
                makeStoredServer(config = "v2-fr-2", countryCode = "FR", ip = "1.2.3.5", city = "")
            )
        )
        // The stored (captured) selection is the second server, with a placeholder city so startup
        // takes the hydration path.
        SelectedCountryStore.setCurrentIndex(context, 1)

        val sameCountrySwitchApplied = AtomicBoolean(false)
        val v2Api = object : ServersV2Api {
            override suspend fun getCountries(locale: String): List<CountryV2> =
                listOf(CountryV2("FR", "France", 2))

            override suspend fun getServers(
                locale: String,
                countryCode: String,
                isActive: Boolean,
                skip: Int,
                take: Int
            ): ServersPageResponse {
                // The user picks another server of the SAME country while this fetch is in flight.
                SelectedCountryStore.setCurrentIndex(context, 0)
                sameCountrySwitchApplied.set(true)
                val items = listOf(
                    ServerV2("1.2.3.4", "FR", "France", "v2-fr-1", city = "Paris", utc = "UTC+1"),
                    ServerV2("1.2.3.5", "FR", "France", "v2-fr-2", city = "Lyon", utc = "UTC+1")
                )
                return ServersPageResponse(items = items, total = items.size)
            }
        }
        val interactor = DefaultMainSelectionInteractor(
            appContext = context,
            serverRepository = ServerRepository(EmptyCsvApi()),
            serversV2Repository = ServersV2Repository(v2Api)
        )

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertTrue(
            "the same-country server switch never fired, the race window was not entered",
            sameCountrySwitchApplied.get()
        )
        // The newer, same-country choice must survive the hydration write...
        assertEquals("France", SelectedCountryStore.getSelectedCountry(context))
        assertEquals("v2-fr-1", SelectedCountryStore.currentServer(context)?.config)
        // ...and the superseded server must not be reported to the caller as current.
        assertNull(result)
    }

    // --------------- helpers ---------------

    /** Waits until [thread] is parked on a monitor, so the race window is entered deterministically. */
    private fun awaitBlocked(thread: Thread, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (thread.state) {
                Thread.State.BLOCKED -> return true
                Thread.State.TERMINATED -> return false
                else -> Thread.sleep(5)
            }
        }
        return false
    }

    private fun makeStoredServer(
        config: String,
        countryCode: String,
        ip: String,
        city: String = ""
    ) = com.yahorzabotsin.openvpnclientgate.core.servers.Server(
        lineIndex = 0,
        name = ip,
        city = city,
        country = com.yahorzabotsin.openvpnclientgate.core.servers.Country("Germany", countryCode),
        ping = 0,
        signalStrength = com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength.WEAK,
        ip = ip,
        score = 0,
        speed = 0L,
        numVpnSessions = 0,
        uptime = 0L,
        totalUsers = 0L,
        totalTraffic = 0L,
        logType = "",
        operator = "",
        message = "",
        configData = config
    )

    private class FakeServersV2Api(
        private val countries: List<CountryV2>,
        private val serversPerCountry: Map<String, List<ServerV2>>,
        private val threadRecorder: ((method: String, threadName: String) -> Unit)? = null
    ) : ServersV2Api {
        override suspend fun getCountries(locale: String): List<CountryV2> {
            threadRecorder?.invoke("countries", Thread.currentThread().name)
            return countries
        }
        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            threadRecorder?.invoke("servers", Thread.currentThread().name)
            val items = serversPerCountry[countryCode.uppercase()] ?: emptyList()
            return ServersPageResponse(items = items, total = items.size)
        }
    }

    private class EmptyCsvApi : VpnServersApi {
        override suspend fun getServers(url: String) =
            "TITLE\nHEADER\n".toResponseBody("text/plain".toMediaType())
    }
}

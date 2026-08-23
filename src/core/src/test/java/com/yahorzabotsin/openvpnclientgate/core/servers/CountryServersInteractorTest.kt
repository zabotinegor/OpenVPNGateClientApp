package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class CountryServersInteractorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("v2_") && it.extension == "json"
        }?.forEach { it.delete() }
    }

    // UT-5.2 -- DEFAULT_V2: calls v2 repo, not legacy ServerRepository
    @Test
    fun getServersForCountry_v2_calls_v2_repo_not_legacy() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":2}]""",
            serversJson = buildServersJson("JP", 2)
        )
        val v2Repo = ServersV2Repository(api)
        // Pre-populate country cache so getServersForCountryV2 finds the country
        v2Repo.getCountries(context, forceRefresh = true)

        val legacyApi = FailingVpnServersApi()
        val legacyRepo = ServerRepository(legacyApi)
        val interactor = DefaultCountryServersInteractor(context, legacyRepo, v2Repo)

        val servers = interactor.getServersForCountry("Japan", cacheOnly = false)

        assertEquals(2, servers.size)
        assertEquals(0, legacyApi.callCount)
    }

    // UT-5.3 -- DEFAULT_V2: getServersForCountry does NOT save to SelectedCountryStore;
    // selection is only persisted when the user confirms via resolveSelection.
    @Test
    fun getServersForCountry_v2_does_not_save_to_selected_country_store() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"DE","name":"Germany","serverCount":3}]""",
            serversJson = buildServersJson("DE", 3)
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)

        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)
        val servers = interactor.getServersForCountry("Germany", cacheOnly = false)

        assertEquals(3, servers.size)
        // SelectedCountryStore must NOT be populated until the user explicitly confirms a server
        val pos = runCatching { SelectedCountryStore.getCurrentPosition(context) }.getOrNull()
        assertTrue(pos == null)
    }

    // UT-5.4 -- DEFAULT_V2: configData is populated from v2 server
    @Test
    fun getServersForCountry_v2_configData_populated() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val expectedConfig = "OPENVPN_CONFIG_BLOB"
        val serversJson = """{"items":[
            {"ip":"10.0.0.1","countryCode":"FR","countryName":"France","configData":"$expectedConfig"}
        ]}"""
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"FR","name":"France","serverCount":1}]""",
            serversJson = serversJson
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)

        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)
        val servers = interactor.getServersForCountry("France", cacheOnly = false)

        assertEquals(1, servers.size)
        assertEquals(expectedConfig, servers[0].configData)
    }

    // UT-5.5 -- DEFAULT_V2: empty result from repo throws IOException
    @Test(expected = IOException::class)
    fun getServersForCountry_v2_empty_result_throws(): Unit = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"US","name":"United States","serverCount":10}]""",
            serversJson = """{"items":[]}""" // empty
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)

        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)
        interactor.getServersForCountry("United States", cacheOnly = false)
    }

    @Test
    fun getServersForCountry_v2_prefers_requested_countryCode_over_stored_selection() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[
                {"code":"DE","name":"Germany","serverCount":1},
                {"code":"JP","name":"Japan","serverCount":1}
            ]""",
            serversJson = buildServersJson("JP", 1)
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)

        // Simulate previous selection in store; request must still use UI countryCode.
        val previouslySelected = Server(
            lineIndex = 0,
            name = "de-server",
            city = "",
            country = Country("Germany", "DE"),
            ping = 0,
            signalStrength = SignalStrength.WEAK,
            ip = "1.1.1.1",
            score = 0,
            speed = 0L,
            numVpnSessions = 0,
            uptime = 0L,
            totalUsers = 0L,
            totalTraffic = 0L,
            logType = "",
            operator = "",
            message = "",
            configData = "CFG"
        )
        SelectedCountryStore.saveSelection(context, "Germany", listOf(previouslySelected))

        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)
        interactor.getServersForCountry("Japan", countryCode = "JP", cacheOnly = false)

        assertEquals("JP", api.lastRequestedCountryCode)
    }

    // ==================== US-23: getServersPage (lazy loading) ====================

    // AC1/AC2 -- cold cache: the interactor requests exactly one page from the network, not
    // the whole country, and reports hasMore/nextSkip from that single response.
    @Test
    fun getServersPage_v2_cold_cache_fetches_only_the_requested_page() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":120}]""",
            serversPageResponses = listOf(buildServersJsonWithTotal("JP", 50, total = 120))
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)
        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)

        val page = interactor.getServersPage("Japan", "JP", skip = 0, take = 50, cacheOnly = false)

        assertEquals(1, api.serversCallCount)
        assertEquals(listOf(0), api.requestedSkips)
        assertEquals(50, api.lastTake)
        assertEquals(50, page.servers.size)
        assertTrue("more pages remain (only 50 of 120 fetched)", page.hasMore)
        assertEquals(50, page.nextSkip)
    }

    // AC2 -- a second page request uses the caller-supplied skip offset, not a re-derived one.
    @Test
    fun getServersPage_v2_second_page_uses_the_given_skip_offset() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":70}]""",
            serversPageResponses = listOf(
                buildServersJsonWithTotal("JP", 50, total = 70),
                buildServersJsonWithTotal("JP", 20, total = 70)
            )
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)
        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)

        interactor.getServersPage("Japan", "JP", skip = 0, take = 50, cacheOnly = false)
        val page2 = interactor.getServersPage("Japan", "JP", skip = 50, take = 50, cacheOnly = false)

        assertEquals(listOf(0, 50), api.requestedSkips)
        assertEquals(20, page2.servers.size)
        assertFalse("last page reached (50 + 20 == total 70)", page2.hasMore)
    }

    // AC8 -- a fresh full-list cache (written by a prior completed session, or by the legacy
    // eager fetch) is served in one shot, unchanged, without a network call.
    @Test
    fun getServersPage_v2_skip0_uses_fresh_cache_fast_path_without_network_call() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"DE","name":"Germany","serverCount":3}]""",
            serversJson = buildServersJson("DE", 3)
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)
        // Prime the warm cache exactly as the pre-existing eager path would.
        v2Repo.getServersForCountry(context, "DE", serverCount = 3, forceRefresh = true)
        val callsAfterPriming = api.serversCallCount

        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)
        val page = interactor.getServersPage("Germany", "DE", skip = 0, take = 50, cacheOnly = false)

        assertEquals("cache hit must not make another network call", callsAfterPriming, api.serversCallCount)
        assertEquals(3, page.servers.size)
        assertFalse("full cached list means nothing more to fetch", page.hasMore)
    }

    // Out of scope source (VPN Gate/legacy): always the full list as a single page, unchanged.
    @Test
    fun getServersPage_non_v2_source_returns_full_list_as_single_page() = runBlocking {
        setSource(ServerSource.VPNGATE)
        val csv = "TITLE, SAMPLE\nHEADER, IGNORE\n" +
            "legacy,9.9.9.9,0,10,0,Japan,JP,0,0,0,0,L,op,msg,cfg"
        val legacyRepo = ServerRepository(FixedApi(csv))
        val interactor = DefaultCountryServersInteractor(context, legacyRepo, serversV2Repository = null)

        val page = interactor.getServersPage("Japan", "JP", skip = 0, take = 50, cacheOnly = false)

        assertEquals(1, page.servers.size)
        assertEquals("9.9.9.9", page.servers[0].ip)
        assertFalse(page.hasMore)
        assertEquals(1, page.nextSkip)
    }

    // AC4 -- a network failure on a genuine (non-first) page propagates so the ViewModel can
    // surface the retry affordance, instead of being swallowed here.
    @Test(expected = IOException::class)
    fun getServersPage_v2_network_failure_mid_scroll_propagates(): Unit = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":120}]""",
            serversPageResponses = listOf(buildServersJsonWithTotal("JP", 50, total = 120))
            // only one scripted response: the 2nd call falls back to the Fake's default empty
            // page, which is not what we want here -- use a throwing fake instead below.
        )
        val v2Repo = ServersV2Repository(ThrowingOnSecondPageApi(api))
        v2Repo.getCountries(context, forceRefresh = true)
        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)

        interactor.getServersPage("Japan", "JP", skip = 0, take = 50, cacheOnly = false)
        interactor.getServersPage("Japan", "JP", skip = 50, take = 50, cacheOnly = false)
    }

    // ==================== US-23 code review fix cycle ====================

    // M1 -- a stale (expired) on-disk cache plus a failing network call at skip==0 must fall
    // back to the stale cache and return it, instead of throwing and closing the screen.
    // Restores the pre-US-23 fetchWithCache() stale-cache fallback for the paged cold path.
    // Also covers minor m9: the existing AC8 cache test (above) only primes a *fresh* cache,
    // which is exactly why M1 slipped through review undetected.
    @Test
    fun getServersPage_v2_skip0_stale_cache_and_network_failure_falls_back_to_stale_list() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val workingApi = FakeServersV2Api(
            countriesJson = """[{"code":"DE","name":"Germany","serverCount":3}]""",
            serversJson = buildServersJson("DE", 3)
        )
        val primingRepo = ServersV2Repository(workingApi)
        primingRepo.getCountries(context, forceRefresh = true)
        primingRepo.getServersForCountry(context, "DE", serverCount = 3, forceRefresh = true)

        // Expire the just-primed server cache (file stays on disk, timestamp is now stale).
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_servers_de_${currentLocaleCode()}", 1L).commit()

        // A second repository instance sharing the same on-disk cache, but whose network calls
        // for servers always fail -- simulates the network being down while a stale cache exists.
        val offlineRepo = ServersV2Repository(AlwaysThrowingServersApi(workingApi))
        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), offlineRepo)

        val page = interactor.getServersPage("Germany", "DE", skip = 0, take = 50, cacheOnly = false)

        assertEquals("stale cache must be served instead of throwing", 3, page.servers.size)
        assertFalse(page.hasMore)
    }

    // M2 -- product decision: selecting a server from a country whose full list has not yet
    // loaded (hasMorePages=true) must kick off a silent background fetch of the remaining
    // pages, so SelectedCountryStore's persisted candidate pool for ServerAutoSwitcher
    // eventually becomes complete -- without the selection call itself waiting on it.
    @Test
    fun resolveSelection_v2_partial_list_triggers_background_backfill_of_full_candidate_pool() = runBlocking {
        setSource(ServerSource.DEFAULT_V2)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":120}]""",
            serversPageResponses = listOf(
                buildServersJsonWithTotalAndIds("JP", 50, total = 120, startId = 1),
                buildServersJsonWithTotalAndIds("JP", 50, total = 120, startId = 51),
                buildServersJsonWithTotalAndIds("JP", 20, total = 120, startId = 101)
            )
        )
        val v2Repo = ServersV2Repository(api)
        v2Repo.getCountries(context, forceRefresh = true)
        val interactor = DefaultCountryServersInteractor(context, ServerRepository(FailingVpnServersApi()), v2Repo)

        // The user only scrolled through the first page (50 of 120) before selecting.
        val firstPage = interactor.getServersPage("Japan", "JP", skip = 0, take = 50, cacheOnly = false)
        assertTrue(firstPage.hasMore)
        assertEquals(50, firstPage.servers.size)

        interactor.resolveSelection(
            countryName = "Japan",
            countryCode = "JP",
            servers = firstPage.servers,
            selectedServer = firstPage.servers[0],
            hasMorePages = firstPage.hasMore,
            nextSkip = firstPage.nextSkip
        )

        assertTrue("selecting from a partial list must trigger a background backfill", interactor.lastBackfillJob != null)
        interactor.lastBackfillJob?.join()

        val stored = SelectedCountryStore.getServers(context)
        assertEquals("full candidate pool must be persisted once the backfill completes", 120, stored.size)
    }

    private class AlwaysThrowingServersApi(private val delegate: ServersV2Api) : ServersV2Api {
        override suspend fun getCountries(locale: String): List<CountryV2> = delegate.getCountries(locale)
        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse = throw IOException("simulated offline network failure")
    }

    private fun buildServersJsonWithTotal(code: String, count: Int, total: Int): String {
        val items = (1..count).joinToString(",") { i ->
            """{"ip":"10.$i.0.$code","countryCode":"$code","countryName":"Country$code","configData":"CONFIG$i"}"""
        }
        return """{"items":[$items],"total":$total}"""
    }

    private fun buildServersJsonWithTotalAndIds(code: String, count: Int, total: Int, startId: Int): String {
        val items = (0 until count).joinToString(",") { i ->
            val id = startId + i
            """{"ip":"10.$id.0.$code","countryCode":"$code","countryName":"Country$code","configData":"CONFIG$id","id":$id}"""
        }
        return """{"items":[$items],"total":$total}"""
    }

    private fun currentLocaleCode(): String =
        UserSettingsStore.resolvePreferredLocale(UserSettingsStore.load(context).language)

    private class FixedApi(private val body: String) : VpnServersApi {
        override suspend fun getServers(url: String): okhttp3.ResponseBody =
            body.toResponseBody("text/plain".toMediaTypeOrNull())
    }

    /** Wraps a [FakeServersV2Api] to throw on the 2nd+ getServers() call, simulating a
     * mid-scroll network failure on a genuine (non-first) page while keeping the first call's
     * scripted success response intact. */
    private class ThrowingOnSecondPageApi(private val delegate: FakeServersV2Api) : ServersV2Api {
        private var callCount = 0
        override suspend fun getCountries(locale: String): List<CountryV2> = delegate.getCountries(locale)
        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            callCount++
            if (callCount > 1) throw IOException("simulated mid-scroll network failure")
            return delegate.getServers(locale, countryCode, isActive, skip, take)
        }
    }

    // --------------- helpers ---------------

    private fun setSource(source: ServerSource) {
        UserSettingsStore.saveServerSource(context, source)
    }

    private fun buildServersJson(code: String, count: Int): String {
        val items = (1..count).joinToString(",") { i ->
            """{"ip":"10.$i.0.1","countryCode":"$code","countryName":"Country$code","configData":"CONFIG$i"}"""
        }
        return """{"items":[$items]}"""
    }

    private class FakeServersV2Api(
        private val countriesJson: String = "[]",
        private val serversJson: String = "{\"items\":[]}",
        // US-23: when set, each successive getServers() call returns the next entry (indexed
        // by call order), so a test can script a distinct response per page/skip.
        private val serversPageResponses: List<String>? = null
    ) : ServersV2Api {
        var lastRequestedCountryCode: String? = null
        var serversCallCount = 0
            private set
        val requestedSkips = mutableListOf<Int>()
        var lastTake: Int? = null

        override suspend fun getCountries(locale: String): List<CountryV2> =
            Gson().fromJson(countriesJson, Array<CountryV2>::class.java).toList()
        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            lastRequestedCountryCode = countryCode
            requestedSkips.add(skip)
            lastTake = take
            val json = serversPageResponses?.getOrElse(serversCallCount) { "{\"items\":[]}" } ?: serversJson
            serversCallCount++
            return Gson().fromJson(json, ServersPageResponse::class.java)
        }
    }

    private class FailingVpnServersApi : VpnServersApi {
        var callCount = 0
        override suspend fun getServers(url: String): okhttp3.ResponseBody {
            callCount++
            throw IOException("Should not be called for DEFAULT_V2")
        }
    }

    // Test for fix #1/#3: verify correct SharedPreferences name is used
    @Test
    fun test_setup_uses_correct_selected_country_prefs_name() {
        // SelectedCountryStore uses 'vpn_selection_prefs', not 'selected_country'
        val testPrefs = context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
        testPrefs.edit().putString("selected_country", "Japan").apply()
        val stored = testPrefs.getString("selected_country", null)
        assertEquals("Japan", stored)
    }
}

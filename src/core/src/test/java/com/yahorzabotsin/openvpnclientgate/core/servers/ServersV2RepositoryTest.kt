package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ServersV2RepositoryTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("v2_") && it.extension == "json"
        }?.forEach { it.delete() }
    }

    // UT-2.1 — parses countries JSON into CountryV2 list
    @Test
    fun getCountries_success() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":14},
                               {"code":"US","name":"United States","serverCount":30}]"""
        )
        val repo = ServersV2Repository(api)

        val result = repo.getCountries(context, forceRefresh = true)

        assertEquals(2, result.size)
        assertEquals("JP", result[0].code)
        assertEquals("Japan", result[0].name)
        assertEquals(14, result[0].serverCount)
    }

    // UT-2.2 — second call without forceRefresh uses cache (no second HTTP call)
    @Test
    fun getCountries_caches_result() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"DE","name":"Germany","serverCount":5}]"""
        )
        val repo = ServersV2Repository(api)

        repo.getCountries(context, forceRefresh = true)
        repo.getCountries(context, forceRefresh = false)

        assertEquals(1, api.countriesCallCount)
    }

    // UT-2.3 — cache expired → new HTTP request made
    @Test
    fun getCountries_cache_expired() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"FR","name":"France","serverCount":8}]"""
        )
        val repo = ServersV2Repository(api)

        // Write expired timestamp
        repo.getCountries(context, forceRefresh = true)
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_countries", 1L).commit()

        repo.getCountries(context, forceRefresh = false)

        assertEquals(2, api.countriesCallCount)
    }

    // UT-2.4 — API failure with existing cache returns cached data
    @Test
    fun getCountries_api_failure_returns_cache() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"PL","name":"Poland","serverCount":3}]"""
        )
        val repo = ServersV2Repository(api)
        repo.getCountries(context, forceRefresh = true)

        // Expire cache and make API throw
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_countries", 1L).commit()
        api.throwOnCountries = IOException("network down")

        val result = repo.getCountries(context, forceRefresh = false)

        assertEquals(1, result.size)
        assertEquals("PL", result[0].code)
    }

    // UT-2.5 — API failure with no cache throws
    @Test(expected = IOException::class)
    fun getCountries_api_failure_no_cache_throws(): Unit = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = "[]",
            throwOnCountries = IOException("no network")
        )
        val repo = ServersV2Repository(api)

        repo.getCountries(context, forceRefresh = true)
    }

    // UT-2.6 — serverCount ≤ 50 → exactly one HTTP request
    @Test
    fun getServersForCountry_single_page() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJson("JP", 10))
        val repo = ServersV2Repository(api)

        val result = repo.getServersForCountry(context, "JP", serverCount = 10, forceRefresh = true)

        assertEquals(10, result.size)
        assertEquals(1, api.serversCallCount)
    }

    // UT-2.7 — serverCount > 50 → multiple pages requested
    @Test
    fun getServersForCountry_multi_page() = runBlocking {
        // Page 1: 50 servers; page 2: 20 servers
        val api = FakeServersV2Api(
            serversPageResponses = listOf(
                buildServersJson("JP", 50),
                buildServersJson("JP", 20)
            )
        )
        val repo = ServersV2Repository(api)

        val result = repo.getServersForCountry(context, "JP", serverCount = 70, forceRefresh = true)

        assertEquals(70, result.size)
        assertEquals(2, api.serversCallCount)
    }

    // UT-2.7b — when API returns total=0 and full pages, serverCount fallback must stop paging.
    @Test
    fun getServersForCountry_serverCount_fallback_stops_when_total_zero() = runBlocking {
        val fullPageWithZeroTotal = buildServersJsonWithTotal("JP", 50, total = 0)
        val api = FakeServersV2Api(
            serversPageResponses = listOf(
                fullPageWithZeroTotal,
                fullPageWithZeroTotal,
                fullPageWithZeroTotal
            )
        )
        val repo = ServersV2Repository(api)

        val result = repo.getServersForCountry(context, "JP", serverCount = 100, forceRefresh = true)

        assertEquals(2, api.serversCallCount)
        assertEquals(100, result.size)
    }

    // UT-2.8 — servers with empty configData are filtered out
    @Test
    fun getServersForCountry_filters_empty_configData() = runBlocking {
        val json = """{"items":[
            {"ip":"1.1.1.1","countryCode":"JP","countryName":"Japan","configData":"VALIDCONFIG"},
            {"ip":"2.2.2.2","countryCode":"JP","countryName":"Japan","configData":""},
            {"ip":"3.3.3.3","countryCode":"JP","countryName":"Japan","configData":"ANOTHERVALID"}
        ]}"""
        val api = FakeServersV2Api(serversJson = json)
        val repo = ServersV2Repository(api)

        val result = repo.getServersForCountry(context, "JP", serverCount = 3, forceRefresh = true)

        assertEquals(2, result.size)
        assertTrue(result.none { it.configData.isBlank() })
    }

    // UT-2.8b — full page with some blank configData still fetches next page
    // Regression: previously used filtered page.size for termination, causing early stop.
    @Test
    fun getServersForCountry_full_page_with_blanks_fetches_next_page() = runBlocking {
        // Page 1: 50 raw entries, but 5 have blank configData → filtered size = 45 < PAGE_SIZE.
        // Without the raw-size fix the loop would stop here; with fix it proceeds to page 2.
        val page1Entries = (1..45).map { i ->
            """{"ip":"10.$i.0.1","countryCode":"JP","countryName":"Japan","configData":"CFG$i"}"""
        } + (1..5).map {
            """{"ip":"10.9$it.0.1","countryCode":"JP","countryName":"Japan","configData":""}"""
        }
        val page1Json = """{"items":[${page1Entries.joinToString(",")}]}"""
        val page2Json = buildServersJson("JP", 10)

        val api = FakeServersV2Api(serversPageResponses = listOf(page1Json, page2Json))
        val repo = ServersV2Repository(api)

        val result = repo.getServersForCountry(context, "JP", serverCount = 60, forceRefresh = true)

        assertEquals(2, api.serversCallCount)
        assertEquals(55, result.size) // 45 valid from page1 + 10 from page2
        assertTrue(result.none { it.configData.isBlank() })
    }

    // UT-2.8c — wrapped API response {"items":[...]} is parsed correctly (regression for JSONException)
    @Test
    fun getServersForCountry_parses_wrapped_api_response() = runBlocking {
        val wrapped = """{"items":[{"ip":"5.5.5.5","countryCode":"CA","countryName":"Canada","configData":"CFGDATA"}],"total":1,"page":1,"pageSize":50}"""
        val api = FakeServersV2Api(serversJson = wrapped)
        val repo = ServersV2Repository(api)

        val result = repo.getServersForCountry(context, "CA", serverCount = 1, forceRefresh = true)

        assertEquals(1, result.size)
        assertEquals("5.5.5.5", result[0].ip)
        assertEquals("CFGDATA", result[0].configData)
    }

    // UT-2.9 — caches for JP and DE are independent
    @Test
    fun getServersForCountry_caches_by_country_code() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJson("JP", 5))
        val repo = ServersV2Repository(api)

        repo.getServersForCountry(context, "JP", 5, forceRefresh = true)
        repo.getServersForCountry(context, "DE", 5, forceRefresh = true)

        val jpCacheFile = File(context.cacheDir, "v2_servers_jp.json")
        val deCacheFile = File(context.cacheDir, "v2_servers_de.json")
        assertTrue(jpCacheFile.exists())
        assertTrue(deCacheFile.exists())
    }

    // UT-2.10 — expired server cache triggers new request
    @Test
    fun getServersForCountry_cache_expired() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJson("US", 3))
        val repo = ServersV2Repository(api)

        repo.getServersForCountry(context, "US", 3, forceRefresh = true)
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_servers_us", 1L).commit()

        repo.getServersForCountry(context, "US", 3, forceRefresh = false)

        assertEquals(2, api.serversCallCount)
    }

    // UT-2.11 — two concurrent cache-miss requests produce only one HTTP call
    // forceRefresh=false: Mutex serialises so second caller finds cache written by first
    @Test
    fun getServersForCountry_concurrent_requests_one_http_call() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJson("CA", 5))
        val repo = ServersV2Repository(api)

        val results = (1..2).map {
            async { repo.getServersForCountry(context, "CA", 5, forceRefresh = false) }
        }.awaitAll()

        assertEquals(5, results[0].size)
        assertEquals(5, results[1].size)
        // Mutex serialises: first call fetches+caches, second finds fresh cache → 1 HTTP call
        assertEquals(1, api.serversCallCount)
    }

    // --------------- helpers ---------------

    // TS-2 (AC-4.1) — parse failure (Gson JsonSyntaxException) with stale cache falls back to
    // cached countries without crashing the caller. Regression for the minified-build path where
    // the network deserialization throws instead of returning null fields.
    @Test
    fun getCountries_parse_failure_returns_stale_cache() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"IT","name":"Italy","serverCount":7}]"""
        )
        val repo = ServersV2Repository(api)

        // Prime cache
        repo.getCountries(context, forceRefresh = true)
        // Expire cache
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_countries", 1L).commit()
        // Simulate Gson deserialization failure on the next network attempt
        api.throwOnCountries = JsonSyntaxException("simulated deserialization failure")

        val result = repo.getCountries(context, forceRefresh = false)

        assertEquals(1, result.size)
        assertEquals("IT", result[0].code)
    }

    // TS-7 — cacheOnly=true with valid-cache parse failure must not fall through to network.
    @Test
    fun getCountries_cache_only_parse_failure_does_not_call_network() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"IT","name":"Italy","serverCount":7}]"""
        )
        val repo = ServersV2Repository(api)

        // Prime cache with valid content.
        repo.getCountries(context, forceRefresh = true)
        val callsAfterPrime = api.countriesCallCount

        // Corrupt cache file while timestamp remains valid.
        File(context.cacheDir, "v2_countries.json").writeText("{not-json")

        try {
            repo.getCountries(context, forceRefresh = false, cacheOnly = true)
            throw AssertionError("Expected IOException for cache parse failure in cacheOnly mode")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(callsAfterPrime, api.countriesCallCount)
    }

    // TS-3 (AC-4.1) — parse failure (Gson JsonSyntaxException) with no cache produces a
    // controlled IOException that callers handle without a fatal crash loop.
    @Test(expected = IOException::class)
    fun getCountries_parse_failure_no_cache_throws(): Unit = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = "[]",
            throwOnCountries = JsonSyntaxException("simulated deserialization failure — no cache")
        )
        val repo = ServersV2Repository(api)

        repo.getCountries(context, forceRefresh = true)
    }

    private fun buildServersJson(code: String, count: Int): String {
        val items = (1..count).joinToString(",") { i ->
            """{"ip":"10.$i.0.1","countryCode":"$code","countryName":"Country$code","configData":"CONFIG$i"}"""
        }
        return """{"items":[$items]}"""
    }

    private fun buildServersJsonWithTotal(code: String, count: Int, total: Int): String {
        val items = (1..count).joinToString(",") { i ->
            """{"ip":"10.$i.0.1","countryCode":"$code","countryName":"Country$code","configData":"CONFIG$i"}"""
        }
        return """{"items":[$items],"total":$total}"""
    }

    private class FakeServersV2Api(
        private val countriesJson: String = "[]",
        private val serversJson: String = "{\"items\":[]}",
        private val serversPageResponses: List<String>? = null,
        var throwOnCountries: Exception? = null
    ) : ServersV2Api {
        var countriesCallCount = 0
        var serversCallCount = 0

        override suspend fun getCountries(): List<CountryV2> {
            throwOnCountries?.let { throw it }
            countriesCallCount++
            return Gson().fromJson(countriesJson, Array<CountryV2>::class.java).toList()
        }

        override suspend fun getServers(
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            val pageJson = serversPageResponses?.getOrElse(serversCallCount) { "{\"items\":[]}" } ?: serversJson
            serversCallCount++
            return Gson().fromJson(pageJson, ServersPageResponse::class.java)
        }
    }
}

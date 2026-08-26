package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.Locale

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
        assertEquals(currentLocaleCode(), api.lastCountriesLocale)
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
        val locale = currentLocaleCode()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_countries_$locale", 1L).commit()

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
            .edit().putLong("ts_countries_${currentLocaleCode()}", 1L).commit()
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
        assertEquals(currentLocaleCode(), api.lastServersLocale)
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

        val locale = currentLocaleCode()
        val jpCacheFile = File(context.cacheDir, "v2_servers_jp_${locale}.json")
        val deCacheFile = File(context.cacheDir, "v2_servers_de_${locale}.json")
        assertTrue(jpCacheFile.exists())
        assertTrue(deCacheFile.exists())
    }

    @Test
    fun getServersForCountry_normalizes_cache_keys_with_locale_root() = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val api = FakeServersV2Api(serversJson = buildServersJson("IQ", 1))
            val repo = ServersV2Repository(api)
            val locale = currentLocaleCode()

            repo.getServersForCountry(context, "IQ", 1, forceRefresh = true)

            val normalizedCacheFile = File(context.cacheDir, "v2_servers_iq_${locale}.json")
            val localeSensitiveCacheFile = File(context.cacheDir, "v2_servers_ıq.json")
            assertTrue(normalizedCacheFile.exists())
            assertEquals(false, localeSensitiveCacheFile.exists())

            val ts = context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
                .getLong("ts_servers_iq_${locale}", -1L)
            assertTrue(ts > 0L)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun getServersForCountry_sanitizes_country_code_for_cache_file_and_timestamp() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJson("JP", 1))
        val repo = ServersV2Repository(api)
        val locale = currentLocaleCode()

        repo.getServersForCountry(context, "JP/../../evil", 1, forceRefresh = true)

        val sanitizedCacheFile = File(context.cacheDir, "v2_servers_jpevil_${locale}.json")
        val unsafeCacheFile = File(context.cacheDir, "v2_servers_jp/../../evil_${locale}.json")
        assertTrue(sanitizedCacheFile.exists())
        assertEquals(false, unsafeCacheFile.exists())
        assertTrue(
            context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
                .contains("ts_servers_jpevil_${locale}")
        )
    }

    // UT-2.10 — expired server cache triggers new request
    @Test
    fun getServersForCountry_cache_expired() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJson("US", 3))
        val repo = ServersV2Repository(api)
        val locale = currentLocaleCode()

        repo.getServersForCountry(context, "US", 3, forceRefresh = true)
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_servers_us_${locale}", 1L).commit()

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
        val locale = currentLocaleCode()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_countries_$locale", 1L).commit()
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
        File(context.cacheDir, "v2_countries_${currentLocaleCode()}.json").writeText("{not-json")

        try {
            repo.getCountries(context, forceRefresh = false, cacheOnly = true)
            throw AssertionError("Expected IOException for cache parse failure in cacheOnly mode")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(callsAfterPrime, api.countriesCallCount)
    }

    @Test
    fun getCountries_cache_only_reads_legacy_cache_and_migrates() = runBlocking {
        val api = FakeServersV2Api(countriesJson = "[]")
        val repo = ServersV2Repository(api)
        val locale = currentLocaleCode()
        val legacyTs = System.currentTimeMillis()
        val legacyCountriesJson = """[{"code":"NL","name":"Netherlands","serverCount":4}]"""

        File(context.cacheDir, "v2_countries.json").writeText(legacyCountriesJson)
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_countries", legacyTs).commit()

        val result = repo.getCountries(context, forceRefresh = false, cacheOnly = true)

        assertEquals(1, result.size)
        assertEquals("NL", result[0].code)
        assertEquals(0, api.countriesCallCount)
        assertTrue(File(context.cacheDir, "v2_countries_${locale}.json").isFile)
        assertEquals(
            legacyTs,
            context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
                .getLong("ts_countries_${locale}", -1L)
        )
    }

    @Test
    fun getCountries_migration_rethrows_cancellation_exception() = runBlocking {
        val locale = currentLocaleCode()
        File(context.cacheDir, "v2_countries.json").writeText("""[{"code":"JP","name":"Japan","serverCount":1}]""")
        File(context.cacheDir, "v2_countries_${locale}.json").delete()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().remove("ts_countries_${locale}").putLong("ts_countries", System.currentTimeMillis()).commit()

        val repo = ServersV2Repository(
            api = FakeServersV2Api(countriesJson = "[]"),
            fileCopy = { _, _ -> throw CancellationException("cancel-migration-countries") }
        )

        try {
            repo.getCountries(context, forceRefresh = false)
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancel-migration-countries", e.message)
        }
    }

    @Test
    fun getCountries_migration_failure_deletes_partial_target_file() = runBlocking {
        val locale = currentLocaleCode()
        val localizedFile = File(context.cacheDir, "v2_countries_${locale}.json")
        File(context.cacheDir, "v2_countries.json").writeText("""[{"code":"JP","name":"Japan","serverCount":1}]""")
        localizedFile.delete()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().remove("ts_countries_${locale}").putLong("ts_countries", System.currentTimeMillis()).commit()

        val repo = ServersV2Repository(
            api = FakeServersV2Api(countriesJson = "[]"),
            fileCopy = { _, target ->
                target.writeText("partial")
                throw IOException("simulated copy failure")
            }
        )

        try {
            repo.getCountries(context, forceRefresh = false, cacheOnly = true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected: no cache remains after the migration failure cleanup
        }

        assertFalse("partial countries cache file should be deleted after migration failure", localizedFile.exists())
    }

    @Test
    fun getServersForCountry_cache_only_reads_legacy_cache_and_migrates() = runBlocking {
        val api = FakeServersV2Api(serversJson = "{\"items\":[]}")
        val repo = ServersV2Repository(api)
        val locale = currentLocaleCode()
        val legacyTs = System.currentTimeMillis()
        val legacyServersJson = """[
            {"ip":"10.1.0.1","countryCode":"JP","countryName":"CountryJP","configData":"CONFIG1"},
            {"ip":"10.2.0.1","countryCode":"JP","countryName":"CountryJP","configData":"CONFIG2"}
        ]"""

        File(context.cacheDir, "v2_servers_jp.json").writeText(legacyServersJson)
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().putLong("ts_servers_jp", legacyTs).commit()

        val result = repo.getServersForCountry(
            context = context,
            countryCode = "JP",
            serverCount = 2,
            forceRefresh = false,
            cacheOnly = true
        )

        assertEquals(2, result.size)
        assertEquals(0, api.serversCallCount)
        assertTrue(File(context.cacheDir, "v2_servers_jp_${locale}.json").isFile)
        assertEquals(
            legacyTs,
            context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
                .getLong("ts_servers_jp_${locale}", -1L)
        )
    }

    @Test
    fun getServersForCountry_migration_rethrows_cancellation_exception() = runBlocking {
        val locale = currentLocaleCode()
        File(context.cacheDir, "v2_servers_jp.json").writeText("""[{"ip":"10.1.0.1","countryCode":"JP","countryName":"CountryJP","configData":"CFG"}]""")
        File(context.cacheDir, "v2_servers_jp_${locale}.json").delete()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().remove("ts_servers_jp_${locale}").putLong("ts_servers_jp", System.currentTimeMillis()).commit()

        val repo = ServersV2Repository(
            api = FakeServersV2Api(serversJson = "{\"items\":[]}"),
            fileCopy = { _, _ -> throw CancellationException("cancel-migration-servers") }
        )

        try {
            repo.getServersForCountry(context, "JP", serverCount = 1, forceRefresh = false)
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancel-migration-servers", e.message)
        }
    }

    @Test
    fun getServersForCountry_migration_failure_deletes_partial_target_file() = runBlocking {
        val locale = currentLocaleCode()
        val localizedFile = File(context.cacheDir, "v2_servers_jp_${locale}.json")
        File(context.cacheDir, "v2_servers_jp.json").writeText("""[{"ip":"10.1.0.1","countryCode":"JP","countryName":"CountryJP","configData":"CFG"}]""")
        localizedFile.delete()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
            .edit().remove("ts_servers_jp_${locale}").putLong("ts_servers_jp", System.currentTimeMillis()).commit()

        val repo = ServersV2Repository(
            api = FakeServersV2Api(serversJson = "{\"items\":[]}"),
            fileCopy = { _, target ->
                target.writeText("partial")
                throw IOException("simulated copy failure")
            }
        )

        try {
            repo.getServersForCountry(context, "JP", serverCount = 1, forceRefresh = false, cacheOnly = true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected: no cache remains after the migration failure cleanup
        }

        assertFalse("partial servers cache file should be deleted after migration failure", localizedFile.exists())
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

    private fun currentLocaleCode(): String =
        UserSettingsStore.resolvePreferredLocale(UserSettingsStore.load(context).language)

    // TS-4 (AC-4.2) — clearCountriesCache removes legacy and locale-scoped entries, preventing migration loop.
    @Test
    fun clearCountriesCache_removes_legacy_and_locale_scoped_entries() = runBlocking {
        val locale = currentLocaleCode()
        val legacyFile = File(context.cacheDir, "v2_countries.json")
        val localizedFile = File(context.cacheDir, "v2_countries_${locale}.json")
        val prefs = context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)

        // Create both legacy and locale-scoped cache files
        legacyFile.writeText("""[{"code":"JP","name":"Japan","serverCount":14}]""")
        localizedFile.writeText("""[{"code":"US","name":"United States","serverCount":30}]""")
        prefs.edit().putLong("ts_countries", 12345L).putLong("ts_countries_$locale", 67890L).apply()

        val repo = ServersV2Repository(FakeServersV2Api(countriesJson = "[]"))

        // Clear cache
        repo.clearCountriesCache(context)

        // Verify both files are deleted
        assertTrue("legacy file should be deleted", !legacyFile.exists())
        assertTrue("localized file should be deleted", !localizedFile.exists())
        // Verify both timestamp keys are removed
        assertTrue("legacy ts key should be removed", !prefs.contains("ts_countries"))
        assertTrue("localized ts key should be removed", !prefs.contains("ts_countries_$locale"))
    }

    @Test
    fun clearCountriesCache_keeps_unrelated_timestamp_keys() = runBlocking {
        val prefs = context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)
        prefs.edit().putLong("ts_servers_jp", 555L).apply()

        val repo = ServersV2Repository(FakeServersV2Api(countriesJson = "[]"))
        repo.clearCountriesCache(context)

        assertTrue("non-country ts key should remain untouched", prefs.contains("ts_servers_jp"))
    }

    // TS-5 (AC-4.2) — after clearCountriesCache, getCountries does not rehydrate from legacy cache.
    @Test(expected = IOException::class)
    fun getCountries_after_clear_does_not_rehydrate_from_legacy() {
        runBlocking {
            val locale = currentLocaleCode()
            val legacyFile = File(context.cacheDir, "v2_countries.json")
            val prefs = context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE)

            // Create legacy cache but no localized file
            legacyFile.writeText("""[{"code":"JP","name":"Japan","serverCount":14}]""")
            prefs.edit().putLong("ts_countries", System.currentTimeMillis()).apply()

            val repo = ServersV2Repository(FakeServersV2Api(countriesJson = "[]"))

            // Clear cache (removes legacy file)
            repo.clearCountriesCache(context)

            // Attempt cacheOnly read should fail (legacy was cleared, no localized cache exists)
            repo.getCountries(context, forceRefresh = false, cacheOnly = true)
        }
    }

    // ==================== getServersPage() ====================

    // A backend that reports a wrong/hostile `total` must not keep hasMore=true forever;
    // getServersPage() must stop at MAX_PAGES_SAFETY_LIMIT (200) the same way fetchAllPages does.
    @Test
    fun getServersPage_stops_at_safety_limit_when_backend_total_is_hostile() = runBlocking {
        val api = RepeatingPageApi(pageSize = 50, hostileTotal = 1_000_000)
        val repo = ServersV2Repository(api)

        var skip = 0
        var hasMore = true
        var pagesFetched = 0
        while (hasMore && pagesFetched < 250) {
            val page = repo.getServersPage(context, "JP", skip = skip, take = 50, pagingSessionId = "safety")
            skip = page.nextSkip
            hasMore = page.hasMore
            pagesFetched++
        }

        assertEquals("must stop at the safety limit, not the hostile total", 200, pagesFetched)
        assertFalse("hasMore must be forced false once the safety limit is hit", hasMore)
    }

    // When the safety limit forces hasMore=false, the accumulated list is knowingly
    // incomplete and must NOT be cached as this country's authoritative full list (it would
    // otherwise stick, wrongly, for the whole TTL).
    @Test
    fun getServersPage_safety_limit_stop_does_not_persist_incomplete_list_as_full_cache() = runBlocking {
        val api = RepeatingPageApi(pageSize = 50, hostileTotal = 1_000_000)
        val repo = ServersV2Repository(api)

        var skip = 0
        var hasMore = true
        var pagesFetched = 0
        while (hasMore && pagesFetched < 250) {
            val page = repo.getServersPage(context, "JP", skip = skip, take = 50, pagingSessionId = "safety")
            skip = page.nextSkip
            hasMore = page.hasMore
            pagesFetched++
        }
        assertEquals(200, pagesFetched)

        val cached = repo.getFreshCachedServers(context, "JP")
        assertTrue("a safety-limit stop must not cache a knowingly-incomplete list", cached.isNullOrEmpty())
    }

    // accumulate=false must not touch the shared pageAccumulators/pagesFetchedForSession
    // state, and must not be affected by (or affect) a concurrent abandonPagingSession() call on
    // the same lockKey -- the whole point of the session-isolation parameter.
    @Test
    fun getServersPage_accumulate_false_ignores_concurrent_abandonPagingSession_and_does_not_self_persist() = runBlocking {
        val page1 = buildServersJsonWithIds(listOf(1, 2, 3))
        val page2 = buildServersJsonWithIds(listOf(4))
        val api = FakeServersV2Api(serversPageResponses = listOf(page1, page2))
        val repo = ServersV2Repository(api)

        val firstPage = repo.getServersPage(context, "JP", skip = 0, take = 3, accumulate = false)
        assertTrue(firstPage.hasMore)
        assertEquals(listOf(1, 2, 3), firstPage.servers.map { it.id })

        // A concurrent onCleared()-style abandon must be a no-op for a non-accumulating session:
        // it never wrote into pageAccumulators in the first place.
        repo.abandonPagingSession("non-accumulating")

        val secondPage = repo.getServersPage(context, "JP", skip = firstPage.nextSkip, take = 3, accumulate = false)
        assertFalse(secondPage.hasMore)
        assertEquals(listOf(4), secondPage.servers.map { it.id })

        // A non-accumulating call must not have written the on-disk full-list cache itself --
        // that is the caller's own responsibility via persistFullServerList.
        val cached = repo.getFreshCachedServers(context, "JP")
        assertTrue("accumulate=false must not persist the disk cache on its own", cached.isNullOrEmpty())
    }

    // persistFullServerList lets a session-isolated caller (the silent background
    // backfill) write the same on-disk cache file/timestamp key that a normal accumulating
    // session would have, so the country takes the warm-cache fast path on its next open.
    @Test
    fun persistFullServerList_writes_the_same_cache_getServersForCountry_reads() = runBlocking {
        val api = FakeServersV2Api()
        val repo = ServersV2Repository(api)
        val servers = listOf(
            ServerV2(ip = "1.1.1.1", countryCode = "JP", countryName = "Japan", configData = "CFG1", id = 1),
            ServerV2(ip = "2.2.2.2", countryCode = "JP", countryName = "Japan", configData = "CFG2", id = 2)
        )

        repo.persistFullServerList(context, "JP", servers)

        val cached = repo.getServersForCountry(context, "JP", serverCount = 2, forceRefresh = false)
        assertEquals(2, cached.size)
        assertEquals(setOf(1, 2), cached.map { it.id }.toSet())
    }

    // Pages fetched seconds-to-minutes apart (user scrolling) can see the backend's
    // active-server cache shift, yielding the same server again at a different offset.
    // getServersPage() must de-duplicate by server id when accumulating for the persisted cache.
    @Test
    fun getServersPage_deduplicates_accumulated_servers_by_id_across_pages() = runBlocking {
        val page1 = buildServersJsonWithIds(listOf(1, 2, 3)) // take=3, rawCount=3 -> hasMore=true
        val page2 = buildServersJsonWithIds(listOf(3, 4)) // id 3 repeats; rawCount=2 < take=3 -> hasMore=false
        val api = FakeServersV2Api(serversPageResponses = listOf(page1, page2))
        val repo = ServersV2Repository(api)

        val firstPage = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "m6")
        assertTrue(firstPage.hasMore)
        val secondPage = repo.getServersPage(context, "JP", skip = firstPage.nextSkip, take = 3, pagingSessionId = "m6")
        assertFalse(secondPage.hasMore)

        // Read back the persisted full-list cache via the normal warm-cache path: it must
        // contain each distinct id once (4), not the 5 raw entries fetched across both pages.
        val merged = repo.getServersForCountry(context, "JP", serverCount = 5, forceRefresh = false)
        assertEquals(4, merged.size)
        assertEquals(setOf(1, 2, 3, 4), merged.map { it.id }.toSet())
    }

    // Overlapping paging sessions of the same country+locale must be
    // fully isolated: B's skip=0 must not clobber A's accumulator, and each session persists its
    // own COMPLETE list when it reaches its final page.
    @Test
    fun paging_sessions_same_country_are_isolated_and_each_persists_its_own_complete_list() = runBlocking {
        val pageA1 = buildServersJsonWithIds(listOf(1, 2, 3)) // take=3 -> hasMore=true
        val pageB1 = buildServersJsonWithIds(listOf(11, 12, 13))
        val pageA2 = buildServersJsonWithIds(listOf(4)) // rawCount=1 < take -> A completes
        val pageB2 = buildServersJsonWithIds(listOf(14)) // B completes
        val api = FakeServersV2Api(serversPageResponses = listOf(pageA1, pageB1, pageA2, pageB2))
        val repo = ServersV2Repository(api)

        val a1 = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "A")
        assertTrue(a1.hasMore)
        val b1 = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "B")
        assertTrue(b1.hasMore)

        val a2 = repo.getServersPage(context, "JP", skip = a1.nextSkip, take = 3, pagingSessionId = "A")
        assertFalse(a2.hasMore)
        val mergedAfterA = repo.getServersForCountry(context, "JP", serverCount = 4, forceRefresh = false)
        assertEquals("session A must persist its own complete list", setOf(1, 2, 3, 4), mergedAfterA.map { it.id }.toSet())

        val b2 = repo.getServersPage(context, "JP", skip = b1.nextSkip, take = 3, pagingSessionId = "B")
        assertFalse(b2.hasMore)
        val mergedAfterB = repo.getServersForCountry(context, "JP", serverCount = 14, forceRefresh = false)
        assertEquals("session B's accumulator must have survived A's completion", setOf(11, 12, 13, 14), mergedAfterB.map { it.id }.toSet())
    }

    // Abandoning session A must release ONLY A's state: session B
    // keeps its accumulator and persists its own complete list afterwards.
    @Test
    fun abandon_session_a_does_not_touch_live_session_b() = runBlocking {
        val pageA1 = buildServersJsonWithIds(listOf(1, 2, 3))
        val pageB1 = buildServersJsonWithIds(listOf(11, 12, 13))
        val pageB2 = buildServersJsonWithIds(listOf(14)) // rawCount=1 < take -> B completes
        val api = FakeServersV2Api(serversPageResponses = listOf(pageA1, pageB1, pageB2))
        val repo = ServersV2Repository(api)

        val a1 = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "A")
        assertTrue(a1.hasMore)
        val b1 = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "B")
        assertTrue(b1.hasMore)

        repo.abandonPagingSession("A")

        val b2 = repo.getServersPage(context, "JP", skip = b1.nextSkip, take = 3, pagingSessionId = "B")
        assertFalse(b2.hasMore)
        val merged = repo.getServersForCountry(context, "JP", serverCount = 14, forceRefresh = false)
        assertEquals(
            "abandoned A must not truncate or interfere with live session B",
            setOf(11, 12, 13, 14),
            merged.map { it.id }.toSet()
        )
    }

    // abandonPagingSession() must actually release the accumulator, not just be a no-op:
    // resuming the same session after an abandon call must not resurrect the earlier pages.
    @Test
    fun abandonPagingSession_clears_accumulator_so_earlier_pages_do_not_resurface() = runBlocking {
        val page1 = buildServersJsonWithIds(listOf(1, 2, 3)) // take=3, rawCount=3 -> hasMore=true
        val page2 = buildServersJsonWithIds(listOf(4)) // rawCount=1 < take=3 -> hasMore=false
        val api = FakeServersV2Api(serversPageResponses = listOf(page1, page2))
        val repo = ServersV2Repository(api)

        val firstPage = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "m4")
        assertTrue(firstPage.hasMore)

        repo.abandonPagingSession("m4")

        val secondPage = repo.getServersPage(context, "JP", skip = firstPage.nextSkip, take = 3, pagingSessionId = "m4")
        assertFalse(secondPage.hasMore)

        // Only the second page's server should have been persisted -- if the abandoned first
        // page's accumulator had survived, this would be 4 servers (ids 1-4) instead of 1.
        val merged = repo.getServersForCountry(context, "JP", serverCount = 4, forceRefresh = false)
        assertEquals(1, merged.size)
        assertEquals(4, merged[0].id)
    }

    // The session-id requirement must be validated BEFORE any network activity:
    // a default-args accumulate caller must fail fast without paying a real request.
    @Test
    fun getServersPage_requires_session_id_before_any_network_call() = runBlocking {
        val api = FakeServersV2Api(serversJson = buildServersJsonWithIds(listOf(1, 2, 3)))
        val repo = ServersV2Repository(api)

        try {
            repo.getServersPage(context, "JP", skip = 0, take = 3)
            org.junit.Assert.fail("expected IllegalArgumentException for accumulate=true without pagingSessionId")
        } catch (expected: IllegalArgumentException) {
            // fast-fail is the contract
        }

        assertEquals(
            "no network call may happen before validation",
            0,
            api.serversCallCount
        )
    }

    // Review -- servers whose payload omits `id` (ServerV2.id defaults to 0) must not collapse
    // onto one entry or get discarded across pages: distinct connections stay, the duplicate
    // connection is dropped, and the persisted full-list cache keeps all of them.
    @Test
    fun getServersPage_zero_id_servers_are_kept_across_pages() = runBlocking {
        val page1 = buildZeroIdJson(listOf("10.0.0.1", "10.0.0.2"), total = 3)
        val page2 = buildZeroIdJson(listOf("10.0.0.1", "10.0.0.3"), total = 3)
        val api = FakeServersV2Api(serversPageResponses = listOf(page1, page2))
        val repo = ServersV2Repository(api)

        val firstPage = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "z")
        assertTrue(firstPage.hasMore)
        val secondPage = repo.getServersPage(context, "JP", skip = firstPage.nextSkip, take = 3, pagingSessionId = "z")
        assertFalse(secondPage.hasMore)

        val merged = repo.getServersForCountry(context, "JP", serverCount = 3, forceRefresh = false)
        assertEquals(
            listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"),
            merged.map { it.ip }
        )
    }

    // Review -- the zero-id fallback key must carry the FULL connection attributes: two
    // zero-id servers with the same ip and hash-colliding configs (classic `Aa` / `BB`)
    // are distinct connections and both must survive accumulation.
    @Test
    fun getServersPage_zero_id_hash_colliding_configs_are_kept_distinct() = runBlocking {
        val page1 = buildZeroIdConfigJson(ip = "10.0.0.1", config = "Aa", total = 2)
        val page2 = buildZeroIdConfigJson(ip = "10.0.0.1", config = "BB", total = 2)
        val api = FakeServersV2Api(serversPageResponses = listOf(page1, page2))
        val repo = ServersV2Repository(api)

        val firstPage = repo.getServersPage(context, "JP", skip = 0, take = 3, pagingSessionId = "k")
        assertTrue(firstPage.hasMore)
        val secondPage = repo.getServersPage(context, "JP", skip = firstPage.nextSkip, take = 3, pagingSessionId = "k")
        assertFalse(secondPage.hasMore)

        val merged = repo.getServersForCountry(context, "JP", serverCount = 2, forceRefresh = false)
        assertEquals(
            "hash-colliding configs must not collapse into one row",
            setOf("Aa", "BB"),
            merged.map { it.configData }.toSet()
        )
    }

    // Review: foreground paging session must not overwrite a newer sync's full-list cache.
    // When the selection version moves between the first and last page of a foreground
    // accumulate session, the final persist must be skipped.
    @Test
    fun foregroundPaging_skipsFullListCachePersist_whenVersionMovesBetweenPages() = runBlocking {
        val page1 = buildServersJsonWithTotal("JP", 2, 3)
        val page2 = buildServersJsonWithTotal("JP", 1, 3)
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":3}]""",
            serversPageResponses = listOf(page1, page2)
        )
        val repo = ServersV2Repository(api)

        val first = repo.getServersPage(context, "JP", skip = 0, take = 2, accumulate = true, pagingSessionId = "fp")
        assertTrue(first.hasMore)

        // A same-country sync completes while the user is mid-scroll.
        SelectedCountryVersionSignal.bump()

        val second = repo.getServersPage(context, "JP", skip = first.nextSkip, take = 2, accumulate = true, pagingSessionId = "fp")
        assertFalse(second.hasMore)

        val cacheFile = File(context.filesDir, "v2_servers_jp_${currentLocaleCode()}.json")
        assertFalse(
            "full-list cache must not be written when the selection version moved between pages",
            cacheFile.exists()
        )
    }

    private fun buildZeroIdConfigJson(ip: String, config: String, total: Int): String {
        val items = """{"ip":"$ip","countryCode":"JP","countryName":"Japan","configData":"$config"}"""
        return """{"items":[$items],"total":$total}"""
    }

    private fun buildZeroIdJson(ips: List<String>, total: Int): String {
        val items = ips.joinToString(",") { ip ->
            """{"ip":"$ip","countryCode":"JP","countryName":"Japan","configData":"CFG-$ip"}"""
        }
        return """{"items":[$items],"total":$total}"""
    }

    private fun buildServersJsonWithIds(ids: List<Int>): String {
        val items = ids.joinToString(",") { id ->
            """{"ip":"10.0.0.$id","countryCode":"JP","countryName":"Japan","configData":"CFG$id","id":$id}"""
        }
        return """{"items":[$items]}"""
    }

    /** Returns a fresh, ever-growing page of [pageSize] items with a `total` far beyond what
     * any bounded loop will reach -- simulates a wrong/hostile backend `total`. */
    private class RepeatingPageApi(
        private val pageSize: Int,
        private val hostileTotal: Int
    ) : ServersV2Api {
        private var counter = 0
        override suspend fun getCountries(locale: String): List<CountryV2> = emptyList()
        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            val items = (0 until pageSize).map {
                counter++
                ServerV2(
                    ip = "10.0.0.$counter",
                    countryCode = countryCode,
                    countryName = countryCode,
                    configData = "CFG$counter",
                    id = counter
                )
            }
            return ServersPageResponse(items = items, total = hostileTotal)
        }
    }

    private class FakeServersV2Api(
        private val countriesJson: String = "[]",
        private val serversJson: String = "{\"items\":[]}",
        private val serversPageResponses: List<String>? = null,
        var throwOnCountries: Exception? = null
    ) : ServersV2Api {
        var countriesCallCount = 0
        var serversCallCount = 0
        var lastCountriesLocale: String? = null
        var lastServersLocale: String? = null

        override suspend fun getCountries(locale: String): List<CountryV2> {
            throwOnCountries?.let { throw it }
            countriesCallCount++
            lastCountriesLocale = locale
            return Gson().fromJson(countriesJson, Array<CountryV2>::class.java).toList()
        }

        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            val pageJson = serversPageResponses?.getOrElse(serversCallCount) { "{\"items\":[]}" } ?: serversJson
            serversCallCount++
            lastServersLocale = locale
            return Gson().fromJson(pageJson, ServersPageResponse::class.java)
        }
    }
}

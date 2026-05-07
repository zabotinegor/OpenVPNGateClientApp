package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ServerRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class SequenceApi(private val responses: List<() -> String>) : VpnServersApi {
        var callCount: Int = 0
            private set
        val calledUrls: MutableList<String> = mutableListOf()

        override suspend fun getServers(url: String): ResponseBody {
            calledUrls += url
            val idx = callCount
            callCount += 1
            val block = responses.getOrElse(idx) { { throw IOException("No more responses") } }
            val body = block()
            return body.toResponseBody("text/plain".toMediaTypeOrNull())
        }
    }

    private class CacheDirOverrideContext(
        base: Context,
        private val overrideCacheDir: java.io.File
    ) : ContextWrapper(base) {
        override fun getCacheDir(): java.io.File = overrideCacheDir
    }

    private class BarrierApi(
        private val expectedCalls: Int,
        private val responseBody: String
    ) : VpnServersApi {
        private val barrier = CyclicBarrier(expectedCalls)
        private val _callCount = AtomicInteger(0)
        val callCount: Int
            get() = _callCount.get()

        override suspend fun getServers(url: String): ResponseBody {
            _callCount.incrementAndGet()
            barrier.await(5, TimeUnit.SECONDS)
            return responseBody.toResponseBody("text/plain".toMediaTypeOrNull())
        }
    }

    @Before
    fun setUp() {
        // Reset user settings and cache between tests
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("server_cache", Context.MODE_PRIVATE).edit().clear().apply()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") }?.forEach { it.delete() }
        UserSettingsStore.saveServerSource(context, ServerSource.LEGACY)
        UserSettingsStore.saveCacheTtlMs(context, UserSettingsStore.DEFAULT_CACHE_TTL_MS)
    }

    private fun sampleCsv(servers: List<Server>): String {
        val header = "TITLE, SAMPLE\nHEADER, IGNORE\n"
        val body = servers.joinToString(separator = "\n") { s ->
            listOf(
                s.name,
                s.ip,
                s.score.toString(),
                s.ping.toString(),
                s.speed.toString(),
                s.country.name,
                "CC",
                s.numVpnSessions.toString(),
                s.uptime.toString(),
                s.totalUsers.toString(),
                s.totalTraffic.toString(),
                s.logType,
                s.operator,
                s.message,
                s.configData
            ).joinToString(",")
        }
        return header + body
    }

    private fun makeServer(name: String, lineIndex: Int = 1) = Server(
        lineIndex = lineIndex,
        name = name,
        city = "City",
        country = Country("Country"),
        ping = 50,
        signalStrength = SignalStrength.STRONG,
        ip = "1.2.3.4",
        score = 100,
        speed = 1000,
        numVpnSessions = 1,
        uptime = 10,
        totalUsers = 5,
        totalTraffic = 500,
        logType = "L",
        operator = "op",
        message = "msg",
        configData = "cfg"
    )

    @Test
    fun uses_primary_when_successful() = runBlocking {
        val expected = makeServer("srv-primary")
        val api = SequenceApi(
            listOf({ sampleCsv(listOf(expected)) }, { sampleCsv(emptyList()) })
        )

        val repo = ServerRepository(api, UserSettingsStore)
        val result = repo.getServers(context, forceRefresh = true)

        assertEquals(1, api.callCount)
        assertEquals(1, result.size)
        assertEquals(expected.name, result[0].name)
        assertTrue("Primary URL should be called first", api.calledUrls.isNotEmpty())
        assertEquals("", result[0].configData) // config is lazy
    }

    @Test
    fun falls_back_when_primary_fails_and_switches_source() = runBlocking {
        val expected = makeServer("srv-fallback")
        val api = SequenceApi(
            listOf({ throw IOException("primary down") }, { sampleCsv(listOf(expected)) })
        )

        val repo = ServerRepository(api, UserSettingsStore)
        val result = repo.getServers(context, forceRefresh = true)

        assertEquals(2, api.callCount)
        assertEquals(1, result.size)
        assertEquals(expected.name, result[0].name)
        // After fallback, source should persist as VPN Gate
        assertEquals(ServerSource.VPNGATE, UserSettingsStore.load(context).serverSource)
        assertEquals("", result[0].configData)
    }

    @Test
    fun uses_cache_when_fresh() = runBlocking {
        val initial = makeServer("cached")
        val api = SequenceApi(listOf({ sampleCsv(listOf(initial)) }, { throw IOException("should not be called") }))
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals(1, api.callCount)
        assertEquals("cached", first.single().name)

        val second = repo.getServers(context, forceRefresh = false)
        assertEquals("cached", second.single().name)
        // callCount stays 1 because cache served the second call
        assertEquals(1, api.callCount)
    }

    @Test
    fun loadConfigs_returns_configs_for_requested_servers() = runBlocking {
        val srv1 = makeServer("s1", lineIndex = 1).copy(configData = "cfg1")
        val srv2 = makeServer("s2", lineIndex = 2).copy(configData = "cfg2")
        val api = SequenceApi(listOf({ sampleCsv(listOf(srv1, srv2)) }))
        val repo = ServerRepository(api, UserSettingsStore)

        val parsed = repo.getServers(context, forceRefresh = true)
        assertEquals(2, parsed.size)
        assertEquals("", parsed[0].configData)

        val configs = repo.loadConfigs(context, parsed)
        assertEquals(2, configs.size)
        assertEquals("cfg1", configs[1])
        assertEquals("cfg2", configs[2])
    }

    @Test
    fun force_refresh_bypasses_cache_and_updates_it() = runBlocking {
        val initial = makeServer("old")
        val updated = makeServer("new")
        val api = SequenceApi(
            listOf(
                { sampleCsv(listOf(initial)) },
                { sampleCsv(listOf(updated)) }
            )
        )
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("old", first.single().name)
        assertEquals(1, api.callCount)

        val second = repo.getServers(context, forceRefresh = true)
        assertEquals("new", second.single().name)
        assertEquals(2, api.callCount)

        // Cache now contains updated; a non-forced call should hit cache
        val third = repo.getServers(context, forceRefresh = false)
        assertEquals("new", third.single().name)
        assertEquals(2, api.callCount)
    }

    @Test
    fun returns_stale_cache_when_force_refresh_fails() = runBlocking {
        val initial = makeServer("stale")
        val api = SequenceApi(
            listOf(
                { sampleCsv(listOf(initial)) },
                { throw IOException("network down") }
            )
        )
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("stale", first.single().name)
        assertEquals(1, api.callCount)

        val second = repo.getServers(context, forceRefresh = true)
        // should serve stale cache despite force because network failed
        assertEquals("stale", second.single().name)
        // primary + secondary attempted on failure
        assertEquals(3, api.callCount)
    }

    @Test
    fun refreshes_when_ttl_expired() = runBlocking {
        val initial = makeServer("initial")
        val updated = makeServer("updated")
        val api = SequenceApi(
            listOf(
                { sampleCsv(listOf(initial)) },
                { sampleCsv(listOf(updated)) }
            )
        )
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("initial", first.single().name)
        assertEquals(1, api.callCount)

        // Mark cache as expired
        val prefs = context.getSharedPreferences("server_cache", Context.MODE_PRIVATE)
        val key = prefs.all.keys.firstOrNull { it.startsWith("ts_") } ?: error("ts key missing")
        prefs.edit().putLong(key, System.currentTimeMillis() - UserSettingsStore.DEFAULT_CACHE_TTL_MS - 1).apply()

        val second = repo.getServers(context, forceRefresh = false)
        assertEquals("updated", second.single().name)
        assertEquals(2, api.callCount)
    }

    @Test
    fun cache_only_ignores_ttl_and_force_refresh() = runBlocking {
        val initial = makeServer("cached")
        val updated = makeServer("new")
        val api = SequenceApi(
            listOf(
                { sampleCsv(listOf(initial)) },
                { sampleCsv(listOf(updated)) }
            )
        )
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("cached", first.single().name)
        assertEquals(1, api.callCount)

        val prefs = context.getSharedPreferences("server_cache", Context.MODE_PRIVATE)
        val key = prefs.all.keys.firstOrNull { it.startsWith("ts_") } ?: error("ts key missing")
        prefs.edit().putLong(key, System.currentTimeMillis() - UserSettingsStore.DEFAULT_CACHE_TTL_MS - 1).apply()

        val second = repo.getServers(context, forceRefresh = true, cacheOnly = true)
        assertEquals("cached", second.single().name)
        assertEquals(1, api.callCount)
    }

    @Test
    fun cache_only_throws_when_cache_missing() = runBlocking {
        val api = SequenceApi(listOf({ sampleCsv(listOf(makeServer("unused"))) }))
        val repo = ServerRepository(api, UserSettingsStore)

        try {
            repo.getServers(context, cacheOnly = true)
            fail("Expected IOException when cache-only and cache is missing")
        } catch (e: IOException) {
            // expected
        }

        assertEquals(0, api.callCount)
    }

    @Test
    fun loadConfigs_returns_empty_when_cache_missing() = runBlocking {
        val srv = makeServer("one")
        val api = SequenceApi(listOf({ sampleCsv(listOf(srv)) }))
        val repo = ServerRepository(api, UserSettingsStore)

        val parsed = repo.getServers(context, forceRefresh = true)
        // Remove cache file to simulate missing
        context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") }?.forEach { it.delete() }

        val configs = repo.loadConfigs(context, parsed)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun parses_quoted_fields_with_commas() = runBlocking {
        val csv = """
            TITLE, SAMPLE
            HEADER, IGNORE
            "srv-1","1.1.1.1","10","50","1000","Country","CC","1","2","3","4","log","op","message,with,comma","cfg,with,comma"
        """.trimIndent()
        val api = SequenceApi(listOf({ csv }))
        val repo = ServerRepository(api, UserSettingsStore)

        val servers = repo.getServers(context, forceRefresh = true)
        assertEquals(1, servers.size)
        assertEquals("message,with,comma", servers.first().message)

        val configs = repo.loadConfigs(context, servers)
        assertEquals("cfg,with,comma", configs[servers.first().lineIndex])
    }

    @Test
    fun loadConfigs_uses_last_cache_key_after_fallback_switch() = runBlocking {
        val srv = makeServer("fallback", lineIndex = 1).copy(configData = "cfg-fallback")
        val api = SequenceApi(
            listOf(
                { throw IOException("primary down") },
                { sampleCsv(listOf(srv)) }
            )
        )
        val repo = ServerRepository(api, UserSettingsStore)

        val servers = repo.getServers(context, forceRefresh = true)
        assertEquals("fallback", servers.single().name)
        assertEquals(ServerSource.VPNGATE, UserSettingsStore.load(context).serverSource)

        val configs = repo.loadConfigs(context, servers)
        assertEquals("cfg-fallback", configs[1])
    }

    @Test
    fun loadConfigs_prefers_last_cache_key_when_primary_file_exists_but_is_stale_snapshot() = runBlocking {
        val initial = makeServer("initial", lineIndex = 1).copy(configData = "cfg-default")
        val firstApi = SequenceApi(listOf({ sampleCsv(listOf(initial)) }))
        val firstRepo = ServerRepository(firstApi, UserSettingsStore)

        val firstServers = firstRepo.getServers(context, forceRefresh = true)
        assertEquals("initial", firstServers.single().name)
        assertEquals("cfg-default", firstRepo.loadConfigs(context, firstServers)[1])

        UserSettingsStore.save(
            context,
            UserSettingsStore.load(context).copy(
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://custom.example/servers.csv"
            )
        )

        val customServer = makeServer("custom", lineIndex = 1).copy(configData = "cfg-custom")
        val secondApi = SequenceApi(listOf({ sampleCsv(listOf(customServer)) }))
        val secondRepo = ServerRepository(secondApi, UserSettingsStore)

        val customServers = secondRepo.getServers(context, forceRefresh = true)
        assertEquals("custom", customServers.single().name)

        UserSettingsStore.save(
            context,
            UserSettingsStore.load(context).copy(serverSource = ServerSource.LEGACY)
        )

        val configs = secondRepo.loadConfigs(context, customServers)
        assertEquals("cfg-custom", configs[1])
    }

    @Test
    fun throws_when_both_primary_and_fallback_fail() = runBlocking {
        val api = SequenceApi(listOf({ throw IOException("fail") }, { throw IOException("fail2") }))
        val repo = ServerRepository(api, UserSettingsStore)

        try {
            repo.getServers(context, forceRefresh = true)
            fail("Expected IOException when both primary and fallback fail")
        } catch (e: IOException) {
            // expected
        }

        assertEquals(2, api.callCount)
    }

    @Test
    fun propagates_error_after_fallback_attempts() = runBlocking {
        val api = SequenceApi(listOf({ throw IllegalStateException("boom") }, { throw IOException("fallback fail") }))
        val repo = ServerRepository(api, UserSettingsStore)

        try {
            repo.getServers(context, forceRefresh = true)
            fail("Expected failure to propagate after retries")
        } catch (e: Exception) {
            // expected
        }

        // Both URLs attempted
        assertEquals(2, api.callCount)
    }

    @Test
    fun uses_last_cache_key_when_current_urls_have_no_cache_and_dns_fails() = runBlocking {
        val initial = makeServer("cached-by-default")
        val api = SequenceApi(listOf({ sampleCsv(listOf(initial)) }))
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("cached-by-default", first.single().name)
        assertEquals(1, api.callCount)

        UserSettingsStore.save(
            context,
            UserSettingsStore.load(context).copy(
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://invalid-host-for-test.example/servers.csv"
            )
        )

        val failingApi = SequenceApi(
            listOf({ throw UnknownHostException("dns failed for custom host") })
        )
        val repoWithFailingApi = ServerRepository(failingApi, UserSettingsStore)

        val loaded = repoWithFailingApi.getServers(context, forceRefresh = true)
        assertEquals("cached-by-default", loaded.single().name)
        assertEquals(1, failingApi.callCount)
    }

    @Test
    fun throws_ioexception_when_cache_write_fails_without_filenotfound_parse_crash() = runBlocking {
        val invalidCacheRoot = java.io.File(context.cacheDir, "cache-root-as-file")
        if (invalidCacheRoot.exists()) {
            if (invalidCacheRoot.isDirectory) {
                invalidCacheRoot.deleteRecursively()
            } else {
                invalidCacheRoot.delete()
            }
        }
        invalidCacheRoot.writeText("not-a-directory")

        val brokenContext = CacheDirOverrideContext(context, invalidCacheRoot)
        val api = SequenceApi(listOf({ sampleCsv(listOf(makeServer("srv-broken-cache"))) }))
        val repo = ServerRepository(api, UserSettingsStore)

        try {
            repo.getServers(brokenContext, forceRefresh = true)
            fail("Expected IOException when cache directory is invalid")
        } catch (e: IOException) {
            assertTrue(e !is java.io.FileNotFoundException)
            assertTrue((e.message ?: "").contains("cache write failed"))
        } finally {
            invalidCacheRoot.delete()
        }
    }

    @Test
    fun cache_only_uses_last_cache_key_when_current_key_missing() = runBlocking {
        val initial = makeServer("cached-by-default")
        val api = SequenceApi(listOf({ sampleCsv(listOf(initial)) }))
        val repo = ServerRepository(api, UserSettingsStore)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("cached-by-default", first.single().name)
        assertEquals(1, api.callCount)

        UserSettingsStore.save(
            context,
            UserSettingsStore.load(context).copy(
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://another-invalid-host.example/servers.csv"
            )
        )

        val cacheOnly = repo.getServers(context, cacheOnly = true)
        assertEquals("cached-by-default", cacheOnly.single().name)
        assertEquals(1, api.callCount)
    }

    @Test
    fun returns_empty_without_network_when_only_placeholder_custom_url_configured() = runBlocking {
        UserSettingsStore.save(
            context,
            UserSettingsStore.load(context).copy(
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://placeholder/api/v1/servers/active"
            )
        )

        val api = SequenceApi(listOf({ sampleCsv(listOf(makeServer("unused"))) }))
        val repo = ServerRepository(api, UserSettingsStore)

        val loaded = repo.getServers(context, forceRefresh = true)
        assertTrue(loaded.isEmpty())
        assertEquals(0, api.callCount)
    }

    @Test
    fun parallel_force_refresh_same_key_does_not_fail_cache_write() = runBlocking {
        val parallelCalls = 8
        val payload = sampleCsv(listOf(makeServer("parallel")))
        val api = BarrierApi(parallelCalls, payload)
        val repo = ServerRepository(api, UserSettingsStore)

        val results = coroutineScope {
            (1..parallelCalls).map {
                async { repo.getServers(context, forceRefresh = true) }
            }.awaitAll()
        }

        assertEquals(parallelCalls, results.size)
        assertTrue(results.all { it.singleOrNull()?.name == "parallel" })
        assertEquals(parallelCalls, api.callCount)
    }

    @Test
    fun clear_server_cache_removes_cached_files_and_metadata() = runBlocking {
        val api = SequenceApi(listOf({ sampleCsv(listOf(makeServer("cached"))) }))
        val repo = ServerRepository(api, UserSettingsStore)

        repo.getServers(context, forceRefresh = true)

        val prefsBefore = context.getSharedPreferences("server_cache", Context.MODE_PRIVATE)
        val cacheFilesBefore = context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") } ?: emptyList()
        assertTrue(prefsBefore.all.isNotEmpty())
        assertTrue(cacheFilesBefore.isNotEmpty())

        val staleTmp = java.io.File(context.cacheDir, "servers_manual_stale.csv.123.tmp")
        staleTmp.writeText("stale")
        assertTrue(staleTmp.exists())

        repo.clearServerCache(context)

        val prefsAfter = context.getSharedPreferences("server_cache", Context.MODE_PRIVATE)
        val cacheFilesAfter = context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") } ?: emptyList()
        assertTrue(prefsAfter.all.isEmpty())
        assertTrue(cacheFilesAfter.isEmpty())
        assertTrue(!staleTmp.exists())
    }

    @Test
    fun clear_server_cache_waits_for_inflight_cache_mutation_lock() = runTest {
        val mutex = Mutex()
        val api = SequenceApi(listOf({ sampleCsv(listOf(makeServer("cached"))) }))
        val repo = ServerRepository(
            api = api,
            settingsStore = UserSettingsStore,
            cacheMutationMutex = mutex
        )

        val lockHolder = async {
            mutex.withLock {
                delay(200)
            }
        }

        val clearDeferred = async {
            repo.clearServerCache(context)
        }

        delay(75)
        assertTrue("clearServerCache should wait for cache mutation lock", !clearDeferred.isCompleted)

        lockHolder.await()
        clearDeferred.await()
    }
}


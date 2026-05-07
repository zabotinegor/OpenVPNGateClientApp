package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ServersV2SyncCoordinatorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("v2_") && it.extension == "json"
        }?.forEach { it.delete() }
    }

    // UT-3.1 — delegates to repository and returns result
    @Test
    fun syncCountries_delegates_to_repository() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":14}]"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        val result = coordinator.syncCountries(context, forceRefresh = true)

        assertEquals(1, result.size)
        assertEquals("JP", result[0].code)
        assertEquals(1, api.countriesCallCount)
    }

    // UT-3.2 — cacheOnly uses file cache, no network call
    @Test
    fun syncCountries_cacheOnly_uses_existing_cache() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"DE","name":"Germany","serverCount":5}]"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        // Populate cache first
        coordinator.syncCountries(context, forceRefresh = true)
        val callsAfterInit = api.countriesCallCount

        // cacheOnly should not make a network call
        coordinator.syncCountries(context, cacheOnly = true)

        assertEquals(callsAfterInit, api.countriesCallCount)
    }

    // UT-3.3 — exception from API propagates out
    @Test(expected = IOException::class)
    fun syncCountries_propagates_exception(): Unit = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = "[]",
            throwOnCountries = IOException("oops")
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        coordinator.syncCountries(context, forceRefresh = true)
    }

    // --------------- helpers ---------------

    private class FakeServersV2Api(
        private val countriesJson: String = "[]",
        private val serversJson: String = "{\"items\":[]}",
        var throwOnCountries: Exception? = null
    ) : ServersV2Api {
        var countriesCallCount = 0

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
        ): ServersPageResponse = Gson().fromJson(serversJson, ServersPageResponse::class.java)
    }
}

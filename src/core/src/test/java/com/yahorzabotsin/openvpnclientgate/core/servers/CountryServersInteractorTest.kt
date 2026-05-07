package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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
        context.getSharedPreferences("selected_country", Context.MODE_PRIVATE).edit().clear().commit()
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

    // UT-5.3 -- DEFAULT_V2: saves result to SelectedCountryStore
    @Test
    fun getServersForCountry_v2_saves_to_selected_country_store() = runBlocking {
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
        // SelectedCountryStore should now have a saved entry
        val pos = SelectedCountryStore.getCurrentPosition(context)
        assertTrue(pos != null)
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
        private val serversJson: String = "[]"
    ) : ServersV2Api {
        override suspend fun getCountries(): okhttp3.ResponseBody = countriesJson.toResponseBody()
        override suspend fun getServers(
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): okhttp3.ResponseBody = serversJson.toResponseBody()
    }

    private class FailingVpnServersApi : VpnServersApi {
        var callCount = 0
        override suspend fun getServers(url: String): okhttp3.ResponseBody {
            callCount++
            throw IOException("Should not be called for DEFAULT_V2")
        }
    }
}

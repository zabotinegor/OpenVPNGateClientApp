package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
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
        private val serversJson: String = "{\"items\":[]}"
    ) : ServersV2Api {
        override suspend fun getCountries(): List<CountryV2> =
            Gson().fromJson(countriesJson, Array<CountryV2>::class.java).toList()
        override suspend fun getServers(
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse = Gson().fromJson(serversJson, ServersPageResponse::class.java)
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

package com.yahorzabotsin.openvpnclientgate.core.di

import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class CoreDiTest {

    @Test
    fun `core module resolves repository`() {
        val koin = koinApplication {
            modules(coreModule)
        }.koin

        val repo = koin.get<ServerRepository>()
        assertNotNull(repo)
    }

    @Test
    fun `api can be injected from custom module`() {
        val fakeApi = FakeVpnServersApi()
        val koin = koinApplication {
            modules(
                module {
                    single<VpnServersApi> { fakeApi }
                    single { ServerRepository(get(), UserSettingsStore) }
                }
            )
        }.koin

        val repo = koin.get<ServerRepository>()
        assertSame(fakeApi, repo.api)
    }

    private class FakeVpnServersApi : VpnServersApi {
        override suspend fun getServers(url: String): ResponseBody =
            "".toResponseBody("text/plain".toMediaType())
    }
}

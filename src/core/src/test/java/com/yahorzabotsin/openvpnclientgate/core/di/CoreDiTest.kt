package com.yahorzabotsin.openvpnclientgate.core.di

import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.main.UpdateCheckInteractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class CoreDiTest {

    @Test
    fun `core module resolves repository`() {
        val koin = koinApplication {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(coreModule)
        }.koin

        val repo = koin.get<ServerRepository>()
        assertNotNull(repo)
    }

    @Test
    fun `api can be injected from custom module`() {
        val fakeApi = FakeVpnServersApi()
        val koin = koinApplication {
            androidContext(ApplicationProvider.getApplicationContext())
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

    @Test
    fun `core module resolves update bindings and main view model`() {
        val koin = koinApplication {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(coreModule)
        }.koin

        assertNotNull(koin.get<UpdateCheckInteractor>())
        assertNotNull(koin.get<MainViewModel>())
    }

    private class FakeVpnServersApi : VpnServersApi {
        override suspend fun getServers(url: String): ResponseBody =
            "".toResponseBody("text/plain".toMediaType())
    }
}

package com.yahorzabotsin.openvpnclientgate.core.di

import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfoProvider
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinksProvider
import com.yahorzabotsin.openvpnclientgate.core.about.DefaultAboutInfoProvider
import com.yahorzabotsin.openvpnclientgate.core.about.DefaultAboutLinksProvider
import com.yahorzabotsin.openvpnclientgate.core.about.ElapsedRealtimeProvider
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportInteractor
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportUseCase
import com.yahorzabotsin.openvpnclientgate.core.about.SystemElapsedRealtimeProvider
import com.yahorzabotsin.openvpnclientgate.core.about.SystemYearProvider
import com.yahorzabotsin.openvpnclientgate.core.about.YearProvider
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutViewModel
import okhttp3.OkHttpClient
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

val coreModule = module {
    single {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    single {
        Retrofit.Builder()
            .baseUrl("https://openvpnclientgate.local/")
            .client(get())
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }

    single<VpnServersApi> {
        get<Retrofit>().create(VpnServersApi::class.java)
    }

    single<UserSettingsStore> { UserSettingsStore }

    single { ServerRepository(get(), get()) }

    single<YearProvider> { SystemYearProvider() }
    single<AboutInfoProvider> { DefaultAboutInfoProvider(get(), get()) }
    single<AboutLinksProvider> { DefaultAboutLinksProvider() }
    single<ElapsedRealtimeProvider> { SystemElapsedRealtimeProvider() }
    single<LogExportInteractor> { LogExportUseCase(get()) }

    viewModel { AboutViewModel(get(), get(), get(), get()) }
}

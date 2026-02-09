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
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterRepository
import com.yahorzabotsin.openvpnclientgate.core.filter.DefaultAppFilterRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.DefaultServerListInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerListInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.DefaultSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.SettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.dns.DefaultDnsSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DefaultDnsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.DefaultFilterLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.FilterLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.FilterViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.DefaultServerListLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.DefaultSettingsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.SettingsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.SettingsViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultMainLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultMainSelectionInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainSelectionInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainViewModel
import com.yahorzabotsin.openvpnclientgate.vpn.DefaultVpnConnectionStateProvider
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
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
    single<SettingsRepository> { DefaultSettingsRepository(androidContext(), get()) }
    single<DnsSettingsRepository> { DefaultDnsSettingsRepository(get()) }
    single<AppFilterRepository> { DefaultAppFilterRepository(androidContext()) }

    single { ServerRepository(get(), get()) }
    single<ServerListInteractor> { DefaultServerListInteractor(androidContext(), get()) }

    single<YearProvider> { SystemYearProvider() }
    single<AboutInfoProvider> { DefaultAboutInfoProvider(get(), get()) }
    single<AboutLinksProvider> { DefaultAboutLinksProvider() }
    single<ElapsedRealtimeProvider> { SystemElapsedRealtimeProvider() }
    single<LogExportInteractor> { LogExportUseCase(get()) }

    viewModel { AboutViewModel(get(), get(), get(), get()) }
    single<DnsLogger> { DefaultDnsLogger() }
    viewModel { DnsViewModel(get(), get()) }
    single<FilterLogger> { DefaultFilterLogger() }
    viewModel { FilterViewModel(get(), get()) }
    single<VpnConnectionStateProvider> { DefaultVpnConnectionStateProvider() }
    single<ServerListLogger> { DefaultServerListLogger() }
    viewModel { ServerListViewModel(get(), get(), get()) }
    single<SettingsLogger> { DefaultSettingsLogger() }
    viewModel { SettingsViewModel(get(), get()) }
    single<MainSelectionInteractor> { DefaultMainSelectionInteractor(androidContext(), get()) }
    single<MainLogger> { DefaultMainLogger() }
    viewModel { MainViewModel(get(), get(), get()) }
}

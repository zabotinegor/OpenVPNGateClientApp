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
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.DefaultCountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.DefaultServerListInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.DefaultServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.DefaultServersV2SyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerListInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryServerSync
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Api
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Repository
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2SyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.DefaultServerRefreshScheduler
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.PeriodicWorkEnqueuer
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerCacheTtlProvider
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshScheduler
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.SettingsServerCacheTtlProvider
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.WorkManagerPeriodicWorkEnqueuer
import com.yahorzabotsin.openvpnclientgate.core.settings.DefaultSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.SettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.dns.DefaultDnsSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ConnectionControlsUseCase
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ConnectionControlsRuntime
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ConnectionControlsSelectionStore
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.DefaultConnectionControlsRuntime
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.DefaultConnectionControlsSelectionStore
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DefaultDnsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.DefaultFilterLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.FilterLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.FilterViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.DefaultServerListLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryServersLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryServersViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.DefaultCountryServersLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.DefaultSettingsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.SettingsLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.SettingsViewModel
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
import com.yahorzabotsin.openvpnclientgate.core.ui.splash.DefaultSplashServerPreloadInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.splash.SplashServerPreloadInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultMainLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultMainConnectionInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainConnectionInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultMainSelectionInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultVersionReleaseInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainLogger
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainSelectionInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultUpdateCheckInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.UpdateCheckInteractor
import com.yahorzabotsin.openvpnclientgate.core.ui.main.VersionReleaseInteractor
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInstaller
import com.yahorzabotsin.openvpnclientgate.core.updates.DefaultAppUpdateInstaller
import com.yahorzabotsin.openvpnclientgate.core.updates.DefaultUpdateCheckRepository
import com.yahorzabotsin.openvpnclientgate.core.updates.UpdateCheckApi
import com.yahorzabotsin.openvpnclientgate.core.updates.UpdateCheckRepository
import com.yahorzabotsin.openvpnclientgate.core.versions.DefaultVersionReleaseRepository
import com.yahorzabotsin.openvpnclientgate.core.versions.VersionReleaseRepository
import com.yahorzabotsin.openvpnclientgate.core.versions.VersionsApi
import com.yahorzabotsin.openvpnclientgate.vpn.DefaultVpnConnectionStateProvider
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import okhttp3.OkHttpClient
import androidx.work.WorkManager
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

    single<ServersV2Api> {
        Retrofit.Builder()
            .baseUrl(ApiConstants.PRIMARY_SERVERS_V2_URL.trimEnd('/') + "/")
            .client(get())
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(ServersV2Api::class.java)
    }

    single { ServersV2Repository(get()) }
    single<ServersV2SyncCoordinator> { DefaultServersV2SyncCoordinator(get()) }
    single<VersionsApi> {
        get<Retrofit>().create(VersionsApi::class.java)
    }

    single<SettingsRepository> { DefaultSettingsRepository(androidContext()) }
    single<DnsSettingsRepository> { DefaultDnsSettingsRepository(androidContext()) }
    single<AppFilterRepository> { DefaultAppFilterRepository(androidContext()) }
    single<VersionReleaseRepository> { DefaultVersionReleaseRepository(androidContext(), get()) }
    single<VersionReleaseInteractor> { DefaultVersionReleaseInteractor(get()) }
    single<UpdateCheckApi> { get<Retrofit>().create(UpdateCheckApi::class.java) }
    single<UpdateCheckRepository> { DefaultUpdateCheckRepository(androidContext(), get()) }
    single<UpdateCheckInteractor> { DefaultUpdateCheckInteractor(get()) }
    single<AppUpdateInstaller> { DefaultAppUpdateInstaller(androidContext(), get()) }

    single { ServerRepository(get()) }
    single { SelectedCountryServerSync(androidContext(), get()) }
    single<ServerSelectionSyncCoordinator> { DefaultServerSelectionSyncCoordinator(androidContext(), get(), get()) }
    single<ServerListInteractor> { DefaultServerListInteractor(androidContext(), get(), get()) }
    single<CountryServersInteractor> { DefaultCountryServersInteractor(androidContext(), get(), get()) }
    single { WorkManager.getInstance(androidContext()) }
    single<PeriodicWorkEnqueuer> { WorkManagerPeriodicWorkEnqueuer(get()) }
    single<ServerCacheTtlProvider> { SettingsServerCacheTtlProvider(androidContext()) }
    single<ServerRefreshScheduler> { DefaultServerRefreshScheduler(get(), get()) }

    single<YearProvider> { SystemYearProvider() }
    single<AboutInfoProvider> { DefaultAboutInfoProvider(get(), get()) }
    single<AboutLinksProvider> { DefaultAboutLinksProvider() }
    single<ElapsedRealtimeProvider> { SystemElapsedRealtimeProvider() }
    single<LogExportInteractor> { LogExportUseCase(get()) }

    viewModel { AboutViewModel(get(), get(), get(), get(), get()) }
    single<DnsLogger> { DefaultDnsLogger() }
    viewModel { DnsViewModel(get(), get()) }
    single<FilterLogger> { DefaultFilterLogger() }
    viewModel { FilterViewModel(get(), get()) }
    single<VpnConnectionStateProvider> { DefaultVpnConnectionStateProvider() }
    single<ServerListLogger> { DefaultServerListLogger() }
    viewModel { ServerListViewModel(get(), get(), get()) }
    single<CountryServersLogger> { DefaultCountryServersLogger() }
    viewModel { CountryServersViewModel(get(), get(), get()) }
    single<SettingsLogger> { DefaultSettingsLogger() }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
    single<MainSelectionInteractor> { DefaultMainSelectionInteractor(androidContext(), get()) }
    single<SplashServerPreloadInteractor> { DefaultSplashServerPreloadInteractor(get(), get(), androidContext()) }
    single<MainConnectionInteractor> { DefaultMainConnectionInteractor(androidContext()) }
    single<MainLogger> { DefaultMainLogger() }
    single { ConnectionControlsUseCase() }
    single<ConnectionControlsRuntime> { DefaultConnectionControlsRuntime() }
    single<ConnectionControlsSelectionStore> { DefaultConnectionControlsSelectionStore() }
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get()) }
}

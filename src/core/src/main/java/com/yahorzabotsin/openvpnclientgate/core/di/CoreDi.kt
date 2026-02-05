package com.yahorzabotsin.openvpnclientgate.core.di

import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import okhttp3.OkHttpClient
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
}

package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import android.util.Log
import com.yahorzabotsin.openvpnclient.core.settings.ServerSource
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.IOException
import java.util.concurrent.TimeUnit

interface VpnServersApi {
    @GET
    suspend fun getServers(@Url url: String): String
}

class ServerRepository(
    private val api: VpnServersApi = createDefaultApi(),
    private val settingsStore: UserSettingsStore = UserSettingsStore
) {

    private companion object {
        private const val TAG = "ServerRepository"

        private fun createDefaultApi(): VpnServersApi {
            val okHttpClient = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl("https://openvpnclient.local/")
                .client(okHttpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            return retrofit.create(VpnServersApi::class.java)
        }
    }

    suspend fun getServers(context: Context): List<Server> {
        val settings = settingsStore.load(context)
        val urls = settingsStore.resolveServerUrls(settings)
        require(urls.isNotEmpty()) { "No server URLs configured" }

        Log.i(TAG, "Server fetch start. Source=${settings.serverSource}, urls_count=${urls.size}")

        var lastError: Exception? = null
        var response: String? = null
        var usedIndex = -1
        for ((index, url) in urls.withIndex()) {
            try {
                Log.d(TAG, "Requesting servers from ${if (index == 0) "PRIMARY" else "SECONDARY"}: $url")
                response = api.getServers(url)
                usedIndex = index
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                Log.w(TAG, "Server request failed for $url", e)
            }
        }

        val body = response ?: throw (lastError ?: IOException("No server response"))

        // If default source failed and we successfully used the fallback, persist the choice.
        if (settings.serverSource == ServerSource.DEFAULT && usedIndex > 0) {
            settingsStore.saveServerSource(context, ServerSource.VPNGATE)
            Log.w(TAG, "Primary failed; switched persisted source to VPN Gate (fallback).")
        } else {
            Log.i(TAG, "Server fetch succeeded from index=$usedIndex; source remains ${settings.serverSource}.")
        }

        return body.lines().drop(2).filter { it.isNotBlank() }.mapNotNull { line ->
            val values = line.split(",")
            if (values.size < 15) {
                null
            } else {
                Server(
                    name = values[0],
                    city = "",
                    country = Country(values[5], values.getOrNull(6)),
                    ping = values[3].toIntOrNull() ?: 0,
                    signalStrength = SignalStrength.STRONG,
                    ip = values[1],
                    score = values[2].toIntOrNull() ?: 0,
                    speed = values[4].toLongOrNull() ?: 0L,
                    numVpnSessions = values[7].toIntOrNull() ?: 0,
                    uptime = values[8].toLongOrNull() ?: 0L,
                    totalUsers = values[9].toLongOrNull() ?: 0L,
                    totalTraffic = values[10].toLongOrNull() ?: 0L,
                    logType = values[11],
                    operator = values[12],
                    message = values[13],
                    configData = values[14]
                )
            }
        }
    }
}

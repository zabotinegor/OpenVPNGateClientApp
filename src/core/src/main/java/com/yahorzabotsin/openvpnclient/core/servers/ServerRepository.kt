package com.yahorzabotsin.openvpnclient.core.servers

import android.util.Log
import com.yahorzabotsin.openvpnclient.core.ApiConstants
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
    private val api: VpnServersApi = createDefaultApi()
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

    suspend fun getServers(): List<Server> {
        val primaryUrl = ApiConstants.PRIMARY_SERVERS_URL
        val fallbackUrl = ApiConstants.FALLBACK_SERVERS_URL

        val response = try {
            Log.d(TAG, "Requesting servers from PRIMARY: $primaryUrl")
            api.getServers(primaryUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when (e) {
                is IOException, is HttpException -> {
                    Log.w(TAG, "Primary servers endpoint failed, falling back to VPNGate: $fallbackUrl", e)
                    api.getServers(fallbackUrl)
                }
                else -> throw e
            }
        }

        return response.lines().drop(2).filter { it.isNotBlank() }.mapNotNull { line ->
            val values = line.split(",")
            if (values.size < 15) {
                null
            } else {
                Server(
                    name = values[0],
                    city = values[5],
                    country = Country(values[5]),
                    ping = values[3].toIntOrNull() ?: 0,
                    signalStrength = SignalStrength.STRONG, // You may want to calculate this based on ping or other metrics
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

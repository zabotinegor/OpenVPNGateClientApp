package com.yahorzabotsin.openvpnclient.core.servers

import android.util.Log
import com.yahorzabotsin.openvpnclient.core.ApiConstants
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

interface PrimaryVpnApi {
    @GET(ApiConstants.PRIMARY_API_ENDPOINT)
    suspend fun getServers(): String
}

interface FallbackVpnGateApi {
    @GET(ApiConstants.FALLBACK_API_ENDPOINT)
    suspend fun getServers(): String
}

class ServerRepository {

    private companion object {
        private const val TAG = "ServerRepository"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()

    private val primaryRetrofit = Retrofit.Builder()
        .baseUrl(ApiConstants.PRIMARY_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()

    private val fallbackRetrofit = Retrofit.Builder()
        .baseUrl(ApiConstants.FALLBACK_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()

    private val primaryApi = primaryRetrofit.create(PrimaryVpnApi::class.java)
    private val fallbackApi = fallbackRetrofit.create(FallbackVpnGateApi::class.java)

    suspend fun getServers(): List<Server> {
        val response = try {
            Log.d(TAG, "Requesting servers from PRIMARY: ${ApiConstants.PRIMARY_BASE_URL}${ApiConstants.PRIMARY_API_ENDPOINT}")
            primaryApi.getServers()
        } catch (e: Exception) {
            Log.w(TAG, "Primary servers endpoint failed, falling back to VPNGate: ${ApiConstants.FALLBACK_BASE_URL}${ApiConstants.FALLBACK_API_ENDPOINT}", e)
            fallbackApi.getServers()
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

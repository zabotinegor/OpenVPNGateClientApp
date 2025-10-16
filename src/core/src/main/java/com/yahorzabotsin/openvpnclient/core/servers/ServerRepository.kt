package com.yahorzabotsin.openvpnclient.core.servers

import com.yahorzabotsin.openvpnclient.core.ApiConstants
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET

interface VpnGateApi {
    @GET(ApiConstants.API_ENDPOINT)
    suspend fun getServers(): String
}

class ServerRepository {

    private val retrofit = Retrofit.Builder()
        .baseUrl(ApiConstants.BASE_URL)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()

    private val vpnGateApi = retrofit.create(VpnGateApi::class.java)

    suspend fun getServers(): List<Server> {
        val response = vpnGateApi.getServers()
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

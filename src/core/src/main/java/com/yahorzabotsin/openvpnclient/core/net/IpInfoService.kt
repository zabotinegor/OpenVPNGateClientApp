package com.yahorzabotsin.openvpnclient.core.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class IpInfo(
    val ip: String,
    val city: String?
)

object IpInfoService {

    private const val TAG = "IpInfoService"
    private const val ENDPOINT_URL = "https://ipinfo.io/json"

    private val client: OkHttpClient by lazy { OkHttpClient() }

    suspend fun fetchPublicIpInfo(): IpInfo? {
        val body = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(ENDPOINT_URL)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "ipinfo.io request failed with code=${response.code}")
                        null
                    } else {
                        response.body?.string()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ipinfo.io request failed", e)
            null
        } ?: return null

        return parseIpInfoJson(body)
    }

    internal fun parseIpInfoJson(json: String): IpInfo? {
        val ip = """"ip"\s*:\s*"([^"]+)"""".toRegex()
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val city = """"city"\s*:\s*"([^"]*)"""".toRegex()
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        return IpInfo(ip = ip, city = city)
    }
}

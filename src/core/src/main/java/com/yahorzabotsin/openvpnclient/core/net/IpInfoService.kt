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
    private val TAG = com.yahorzabotsin.openvpnclient.core.logging.LogTags.APP + ':' + "IpInfoService"
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
        return try {
            val jsonObj = org.json.JSONObject(json)
            val ip = jsonObj.optString("ip", null)?.takeIf { it.isNotBlank() } ?: return null
            val city = jsonObj.optString("city", null)?.takeIf { it.isNotBlank() }
            IpInfo(ip = ip, city = city)
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Failed to parse ipinfo.io JSON", e)
            null
        }
    }
}


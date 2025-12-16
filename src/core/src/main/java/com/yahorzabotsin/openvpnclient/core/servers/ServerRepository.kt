package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yahorzabotsin.openvpnclient.core.settings.ServerSource
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore.DEFAULT_CACHE_TTL_MS
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

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
        private const val CACHE_PREFS = "server_cache"
        private const val KEY_PREFIX_DATA = "data_"
        private const val KEY_PREFIX_TS = "ts_"
        private const val CACHE_ENCODING_PREFIX = "gz:"
        private val gson = Gson()
        private val serversListType = object : TypeToken<List<Server>>() {}.type

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

        private fun cachePrefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)

        private fun cacheKey(urls: List<String>): String {
            val joined = urls.joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun readCache(ctx: Context, key: String): Pair<List<Server>, Long>? {
            val prefs = cachePrefs(ctx)
            val ts = prefs.getLong(KEY_PREFIX_TS + key, -1L)
            if (ts <= 0) return null
            val data = prefs.getString(KEY_PREFIX_DATA + key, null) ?: return null
            val list = runCatching { decodeServers(data) }.getOrNull() ?: return null
            return list to ts
        }

        private fun writeCache(ctx: Context, key: String, servers: List<Server>) {
            val encoded = runCatching { encodeServers(servers) }.getOrNull()
            if (encoded == null) {
                Log.w(TAG, "Skipping cache write: failed to encode servers list")
                return
            }
            cachePrefs(ctx).edit()
                .putString(KEY_PREFIX_DATA + key, encoded)
                .putLong(KEY_PREFIX_TS + key, System.currentTimeMillis())
                .apply()
        }

        private fun encodeServers(servers: List<Server>): String {
            val byteOut = ByteArrayOutputStream()
            GZIPOutputStream(byteOut).use { gzip ->
                OutputStreamWriter(gzip, Charsets.UTF_8).use { writer ->
                    gson.toJson(servers, serversListType, writer)
                }
            }
            val compressed = byteOut.toByteArray()
            val encoded = Base64.encodeToString(compressed, Base64.NO_WRAP)
            return CACHE_ENCODING_PREFIX + encoded
        }

        private fun decodeServers(raw: String): List<Server> {
            val jsonString = if (raw.startsWith(CACHE_ENCODING_PREFIX)) {
                val payload = raw.removePrefix(CACHE_ENCODING_PREFIX)
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                GZIPInputStream(ByteArrayInputStream(bytes)).use { inflater ->
                    InputStreamReader(inflater, Charsets.UTF_8).use { it.readText() }
                }
            } else {
                raw
            }
            return gson.fromJson(jsonString, serversListType)
        }
    }

    suspend fun getServers(context: Context, forceRefresh: Boolean = false): List<Server> {
        val settings = settingsStore.load(context)
        val urls = settingsStore.resolveServerUrls(settings)
        require(urls.isNotEmpty()) { "No server URLs configured" }

        val cacheKey = cacheKey(urls)
        val cached = readCache(context, cacheKey)
        val now = System.currentTimeMillis()
        val ttlMs = settings.cacheTtlMs.takeIf { it > 0 } ?: DEFAULT_CACHE_TTL_MS
        val cachedFresh = if (forceRefresh) null else cached?.let { (body, ts) -> if (now - ts <= ttlMs) body else null }

        if (cachedFresh != null) {
            Log.i(TAG, "Using cached servers (fresh). age=${cached?.let { now - it.second } ?: -1} ms, items=${cachedFresh.size}")
            return cachedFresh
        }

        Log.i(TAG, "Cache miss/stale. Fetching servers. Source=${settings.serverSource}, urls_count=${urls.size}, ttl_ms=$ttlMs, force=$forceRefresh")

        var lastError: Exception? = null
        var response: String? = null
        var usedIndex = -1
        var parsedResponse: List<Server>? = null
        for ((index, url) in urls.withIndex()) {
            try {
                Log.d(TAG, "Requesting servers from ${if (index == 0) "PRIMARY" else "SECONDARY"}")
                response = api.getServers(url)
                usedIndex = index
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                Log.w(TAG, "Server request failed (index=$index)", e)
            }
        }

        if (response != null) {
            parsedResponse = parseServers(response)
            writeCache(context, cacheKey, parsedResponse)
            Log.d(TAG, "Server response cached. items=${parsedResponse.size}, cache_key=${cacheKey.take(8)}, ttl_ms=$ttlMs")
        }

        val result = parsedResponse ?: cached?.first ?: throw (lastError ?: IOException("No server response"))
        if (response == null && cached != null) {
            Log.w(TAG, "Network failed; using stale cache. age=${now - cached.second} ms")
        }

        if (settings.serverSource == ServerSource.DEFAULT && usedIndex > 0) {
            settingsStore.saveServerSource(context, ServerSource.VPNGATE)
            Log.w(TAG, "Primary failed; switched persisted source to VPN Gate (fallback).")
        } else if (usedIndex >= 0) {
            Log.i(TAG, "Server fetch succeeded from index=$usedIndex; source remains ${settings.serverSource}.")
        }

        return result
    }

    private suspend fun parseServers(body: String): List<Server> = withContext(Dispatchers.Default) {
        body.lines().drop(2).filter { it.isNotBlank() }.mapNotNull { line ->
            val values = line.split(",", limit = 15)
            if (values.size < 15) return@mapNotNull null
            Server(
                name = values[0],
                city = "",
                country = Country(values[5], values.getOrNull(6)),
                ping = values[3].toIntOrNull() ?: 0,
                signalStrength = when (values[3].toIntOrNull() ?: 999) {
                    in 0..99 -> SignalStrength.STRONG
                    in 100..249 -> SignalStrength.MEDIUM
                    else -> SignalStrength.WEAK
                },
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

package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.DEFAULT_CACHE_TTL_MS
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.security.MessageDigest
import java.io.Reader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

interface VpnServersApi {
    @GET
    suspend fun getServers(@Url url: String): ResponseBody
}

class ServerRepository(
    private val api: VpnServersApi = createDefaultApi(),
    private val settingsStore: UserSettingsStore = UserSettingsStore
) {

    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerRepository"
        private const val CACHE_PREFS = "server_cache"
        private const val KEY_PREFIX_TS = "ts_"
        private const val KEY_LAST_CACHE = "last_cache_key"
        private const val CACHE_FILE_PREFIX = "servers_"
        private const val CACHE_FILE_SUFFIX = ".csv"
        private fun createDefaultApi(): VpnServersApi {
            val okHttpClient = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl("https://openvpnclientgate.local/")
                .client(okHttpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            return retrofit.create(VpnServersApi::class.java)
        }

        private fun cacheKey(urls: List<String>): String {
            val joined = urls.joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun cacheFile(ctx: Context, key: String): File =
            File(ctx.cacheDir, "$CACHE_FILE_PREFIX$key$CACHE_FILE_SUFFIX")
    }

    private fun readCache(context: Context, key: String): Pair<File, Long>? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val ts = prefs.getLong(KEY_PREFIX_TS + key, -1L)
        val file = cacheFile(context, key)
        if (ts <= 0 || !file.exists()) return null
        return file to ts
    }

    private fun writeCache(context: Context, key: String, body: ResponseBody) {
        val file = cacheFile(context, key)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            body.use { response ->
                tmp.outputStream().use { out ->
                    response.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Failed to move temp cache to final file")
            }
        }.onSuccess {
            context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
                .edit()
                .putLong(KEY_PREFIX_TS + key, System.currentTimeMillis())
                .putString(KEY_LAST_CACHE, key)
                .apply()
        }.onFailure { Log.w(TAG, "Failed to write cache file", it) }
    }

    private fun saveLastCacheKey(context: Context, key: String) {
        context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CACHE, key)
            .apply()
    }

    suspend fun getServers(context: Context, forceRefresh: Boolean = false, cacheOnly: Boolean = false): List<Server> =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load(context)
            val urls = settingsStore.resolveServerUrls(settings)
            require(urls.isNotEmpty()) { "No server URLs configured" }

            val cacheKey = cacheKey(urls)
            val cached = readCache(context, cacheKey)
            val now = System.currentTimeMillis()
            val ttlMs = settings.cacheTtlMs.takeIf { it > 0 } ?: DEFAULT_CACHE_TTL_MS
            val cachedFresh = if (forceRefresh) null else cached?.let { (file, ts) -> if (now - ts <= ttlMs) file else null }

            if (cacheOnly) {
                val (file, ts) = cached ?: throw IOException("Server cache is empty while VPN is connected")
                val age = now - ts
                val servers = parseServers(file)
                Log.i(TAG, "Using cached servers (cache-only). age=$age ms, items=${servers.size}")
                saveLastCacheKey(context, cacheKey)
                return@withContext servers
            }

            if (cachedFresh != null) {
                val age = cached?.let { now - it.second } ?: -1
                val servers = parseServers(cachedFresh)
                Log.i(TAG, "Using cached servers (fresh). age=$age ms, items=${servers.size}")
                saveLastCacheKey(context, cacheKey)
                return@withContext servers
            }

            Log.i(TAG, "Cache miss/stale. Fetching servers. Source=${settings.serverSource}, urls_count=${urls.size}, ttl_ms=$ttlMs, force=$forceRefresh")

            var lastError: Exception? = null
            var response: ResponseBody? = null
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
                writeCache(context, cacheKey, response)
                parsedResponse = parseServers(cacheFile(context, cacheKey))
                Log.d(TAG, "Server response cached. items=${parsedResponse?.size ?: -1}, cache_key=${cacheKey.take(8)}, ttl_ms=$ttlMs")
            }

            val result = parsedResponse ?: cached?.first?.let { parseServers(it) } ?: throw (lastError ?: IOException("No server response"))
            if (response == null && cached != null) {
                Log.w(TAG, "Network failed; using stale cache. age=${now - cached.second} ms")
            }
            saveLastCacheKey(context, cacheKey)

            if (settings.serverSource == ServerSource.DEFAULT && usedIndex > 0) {
                settingsStore.saveServerSource(context, ServerSource.VPNGATE)
                Log.w(TAG, "Primary failed; switched persisted source to VPN Gate (fallback).")
            } else if (usedIndex >= 0) {
                Log.i(TAG, "Server fetch succeeded from index=$usedIndex; source remains ${settings.serverSource}.")
            }

            return@withContext result
        }

    private suspend fun parseServers(reader: BufferedReader): List<Server> = withContext(Dispatchers.Default) {
        reader.use { r ->
            repeat(2) { r.readLine() } // Skip header lines

            val result = ArrayList<Server>()
            var lineIndex = 0
            while (true) {
                val values = readCsvRow(r, 14) ?: break
                
                if (values.isEmpty() || (values.size == 1 && values[0].isBlank())) {
                     continue
                }
                
                lineIndex++

                if (values.size < 14) continue

                val pingValue = values[3].toIntOrNull() ?: 0
                val signalStrength = when (pingValue) {
                    in 0..99 -> SignalStrength.STRONG
                    in 100..249 -> SignalStrength.MEDIUM
                    else -> SignalStrength.WEAK
                }

                result.add(
                    Server(
                        lineIndex = lineIndex,
                        name = values[0],
                        city = "",
                        country = Country(values[5], values.getOrNull(6)),
                        ping = pingValue,
                        signalStrength = signalStrength,
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
                        configData = "" // Loaded lazily
                    )
                )
            }
            result
        }
    }

    private suspend fun parseServers(file: File): List<Server> =
        parseServers(BufferedReader(FileReader(file)))

    suspend fun loadConfigs(context: Context, servers: List<Server>): Map<Int, String> =
        withContext(Dispatchers.IO) {
            if (servers.isEmpty()) return@withContext emptyMap()

            val settings = settingsStore.load(context)
            val urls = settingsStore.resolveServerUrls(settings)
            val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            val primaryKey = cacheKey(urls)
            val primaryFile = cacheFile(context, primaryKey)
            val lastKey = prefs.getString(KEY_LAST_CACHE, null)
            val lastFile = lastKey?.let { cacheFile(context, it) }
            val file = when {
                primaryFile.exists() -> primaryFile
                lastFile?.exists() == true -> lastFile
                else -> return@withContext emptyMap()
            }

            val targetIndexes = servers.map { it.lineIndex }.toSet()
            val result = HashMap<Int, String>(targetIndexes.size)

            BufferedReader(FileReader(file)).use { reader ->
                repeat(2) { reader.readLine() } // skip header
                var idx = 0
                while (true) {
                    idx++
                    if (idx !in targetIndexes) {
                        if (!skipRestOfLine(reader)) break
                        continue
                    }

                    val values = readCsvRow(reader, 15) ?: break
                    if (values.size < 15) continue
                    
                    result[idx] = values[14]
                    
                    if (result.size == targetIndexes.size) break
                }
            }
            result
        }

    private fun skipRestOfLine(reader: Reader): Boolean {
        loop@ while (true) {
            val c = reader.read()
            if (c == -1) return false
            when (c.toChar()) {
                '\n' -> break@loop
                '\r' -> {
                    reader.mark(1)
                    val next = reader.read()
                    if (next != -1 && next.toChar() != '\n') reader.reset()
                    break@loop
                }
            }
        }
        return true
    }

    private fun readCsvRow(reader: Reader, maxColumns: Int): List<String>? {
        val result = ArrayList<String>(maxColumns)
        val current = StringBuilder()
        var inQuotes = false

        // Check for EOF immediately
        reader.mark(1)
        if (reader.read() == -1) return null
        reader.reset()

        loop@ while (true) {
            val cInt = reader.read()
            if (cInt == -1) {
                if (result.isNotEmpty() || current.isNotEmpty()) {
                    result.add(current.toString())
                }
                break
            }
            val c = cInt.toChar()

            if (inQuotes) {
                if (c == '"') {
                    reader.mark(1)
                    val next = reader.read()
                    if (next != -1 && next.toChar() == '"') {
                        current.append('"')
                    } else {
                        inQuotes = false
                        if (next != -1) reader.reset()
                    }
                } else {
                    current.append(c)
                }
            } else {
                when (c) {
                    ',' -> {
                        result.add(current.toString())
                        current.clear()
                        if (result.size == maxColumns) {
                            skipRestOfLine(reader)
                            return result
                        }
                    }
                    '\r', '\n' -> {
                        if (c == '\r') {
                            reader.mark(1)
                            val next = reader.read()
                            if (next != -1 && next.toChar() != '\n') reader.reset()
                        }
                        result.add(current.toString())
                        // Line finished.
                        return result
                    }
                    '"' -> {
                        if (current.isEmpty()) inQuotes = true else current.append(c)
                    }
                    else -> current.append(c)
                }
            }
        }
        // End of file
        return if (result.isEmpty()) null else result
    }
}



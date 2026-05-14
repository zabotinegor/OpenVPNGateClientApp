package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.SharedPreferences
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

data class StoredServer(
    val city: String,
    val config: String,
    val countryCode: String? = null,
    val ip: String? = null
)

data class LastConfig(
    val country: String?,
    val config: String?,
    val ip: String?
)

object SelectedCountryStore {
    private const val PREFS_NAME = "vpn_selection_prefs"
    private const val KEY_COUNTRY = "selected_country"
    private const val KEY_SERVERS = "selected_country_servers"
    private const val KEY_INDEX = "selected_country_index"
    private const val KEY_LAST_SUCCESS_COUNTRY = "last_success_country"
    private const val KEY_LAST_SUCCESS_CONFIG = "last_success_config"
    private const val KEY_LAST_STARTED_COUNTRY = "last_started_country"
    private const val KEY_LAST_STARTED_CONFIG = "last_started_config"
    private const val KEY_LAST_SUCCESS_IP = "last_success_ip"
    private const val KEY_LAST_STARTED_IP = "last_started_ip"
    private val TAG = LogTags.APP + ":SelectedCountryStore"
    private const val KEY_JSON_CITY = "city"
    private const val KEY_JSON_CONFIG = "config"
    private const val KEY_JSON_CODE = "code"
    private const val KEY_JSON_IP = "ip"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSelection(ctx: Context, country: String, servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { s ->
            val o = JSONObject()
                .put(KEY_JSON_CITY, s.city)
                .put(KEY_JSON_CONFIG, s.configData)
                .put(KEY_JSON_CODE, s.country.code)
                .put(KEY_JSON_IP, s.ip)
            arr.put(o)
        }
        prefs(ctx).edit()
            .putString(KEY_COUNTRY, country)
            .putString(KEY_SERVERS, arr.toString())
            .putInt(KEY_INDEX, 0)
            .apply()
    }

    fun saveSelectionPreservingIndex(ctx: Context, country: String, servers: List<Server>) {
        val selectedCountry = getSelectedCountry(ctx)
        if (selectedCountry != country) return

        val previousCurrent = currentServer(ctx)
        val previousCount = getServers(ctx).size

        saveSelection(ctx, country, servers)

        if (previousCurrent != null) {
            ensureIndexForConfig(ctx, previousCurrent.config, previousCurrent.ip)
        }

        val newCount = getServers(ctx).size
        val restoredCurrent = currentServer(ctx)
        val currentRestored = previousCurrent != null && restoredCurrent != null &&
            restoredCurrent.config == previousCurrent.config &&
            restoredCurrent.ip == previousCurrent.ip
        AppLog.i(
            TAG,
            "saveSelectionPreservingIndex: country=$country, count=$previousCount->$newCount, current_restored=$currentRestored"
        )
        SelectedCountryVersionSignal.bump()
    }

    fun getSelectedCountry(ctx: Context): String? = prefs(ctx).getString(KEY_COUNTRY, null)

    fun getServers(ctx: Context): List<StoredServer> {
        val raw = prefs(ctx).getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredServer(
                    city = o.optString(KEY_JSON_CITY),
                    config = o.optString(KEY_JSON_CONFIG),
                    countryCode = o.optString(KEY_JSON_CODE, null),
                    ip = o.optString(KEY_JSON_IP, null)
                )
            }
        } catch (e: JSONException) {
            AppLog.e(TAG, "Error parsing servers JSON from SharedPreferences", e)
            emptyList()
        }
    }

    fun resetIndex(ctx: Context) { prefs(ctx).edit().putInt(KEY_INDEX, 0).apply() }

    fun prepareAutoSwitchFromStart(ctx: Context) { prefs(ctx).edit().putInt(KEY_INDEX, -1).apply() }

    private fun getIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_INDEX, 0)

    private fun setIndex(ctx: Context, index: Int) { prefs(ctx).edit().putInt(KEY_INDEX, index).apply() }

    fun setCurrentIndex(ctx: Context, index: Int) {
        val list = getServers(ctx)
        if (index in list.indices) {
            setIndex(ctx, index)
            val current = list[index]
            AppLog.d(
                TAG,
                "setCurrentIndex: index=${index + 1}/${list.size} ip=${current.ip ?: "<none>"} city=${current.city.ifBlank { "<none>" }}"
            )
        }
    }

    fun getCurrentPosition(ctx: Context): Pair<Int, Int>? {
        val list = getServers(ctx)
        if (list.isEmpty()) return null
        val idx = getIndex(ctx)
        return if (idx in list.indices) (idx + 1) to list.size else null
    }

    fun getCurrentIndex(ctx: Context): Int? {
        val list = getServers(ctx)
        val idx = getIndex(ctx)
        return if (idx in list.indices) idx else null
    }

    fun currentServer(ctx: Context): StoredServer? {
        val list = getServers(ctx)
        val idx = getIndex(ctx)
        return if (idx in list.indices) list[idx] else null
    }

    fun nextServer(ctx: Context): StoredServer? {
        val list = getServers(ctx)
        val idx = getIndex(ctx) + 1
        return if (idx in list.indices) {
            setIndex(ctx, idx)
            list[idx]
        } else null
    }

    fun nextServerCircular(ctx: Context, startIndex: Int?): StoredServer? {
        val list = getServers(ctx)
        if (list.isEmpty()) return null
        val current = getIndex(ctx).let { if (it in list.indices) it else 0 }
        val start = startIndex?.takeIf { it in list.indices } ?: current
        val next = (current + 1) % list.size
        if (next == start) return null
        setIndex(ctx, next)
        return list[next]
    }

    private fun resolveIpForConfig(ctx: Context, config: String?): String? {
        if (config.isNullOrBlank()) return null
        return getServers(ctx).firstOrNull { it.config == config }?.ip
    }

    fun getIpForConfig(ctx: Context, config: String?): String? = resolveIpForConfig(ctx, config)

    fun saveLastSuccessfulConfig(
        ctx: Context,
        country: String?,
        config: String,
        ip: String? = null,
        alignIndex: Boolean = true
    ) {
        if (config.isBlank()) return
        val ipToStore = ip ?: resolveIpForConfig(ctx, config)
        prefs(ctx).edit()
            .putString(KEY_LAST_SUCCESS_CONFIG, config)
            .putString(KEY_LAST_SUCCESS_COUNTRY, country)
            .putString(KEY_LAST_SUCCESS_IP, ipToStore)
            .apply()
        if (alignIndex) {
            ensureIndexForConfig(ctx, config, ipToStore)
        }
    }

    fun getLastSuccessfulConfigForSelected(ctx: Context): String? {
        val prefs = prefs(ctx)
        val config = prefs.getString(KEY_LAST_SUCCESS_CONFIG, null)
        val selected = getSelectedCountry(ctx)
        val country = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
        AppLog.d(TAG, "getLastSuccessfulConfigForSelected: selected=${selected ?: "<none>"} storedCountry=${country ?: "<none>"} hasConfig=${config != null}")
        if (config.isNullOrBlank() || selected.isNullOrBlank()) return null
        return if (selected == country) config else null
    }

    fun getLastSuccessfulIpForSelected(ctx: Context): String? {
        val prefs = prefs(ctx)
        val selected = getSelectedCountry(ctx)
        val country = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
        val ip = prefs.getString(KEY_LAST_SUCCESS_IP, null)
        if (ip.isNullOrBlank() || selected.isNullOrBlank()) return null
        return if (selected == country) ip else null
    }

    fun saveLastStartedConfig(ctx: Context, country: String?, config: String, ip: String? = null) {
        if (config.isBlank()) return
        val ipToStore = ip ?: resolveIpForConfig(ctx, config)
        prefs(ctx).edit()
            .putString(KEY_LAST_STARTED_CONFIG, config)
            .putString(KEY_LAST_STARTED_COUNTRY, country)
            .putString(KEY_LAST_STARTED_IP, ipToStore)
            .apply()
        ensureIndexForConfig(ctx, config, ipToStore)
    }

    fun getLastStartedConfig(ctx: Context): LastConfig? {
        val prefs = prefs(ctx)
        val cfg = prefs.getString(KEY_LAST_STARTED_CONFIG, null)
        val country = prefs.getString(KEY_LAST_STARTED_COUNTRY, null)
        val ip = prefs.getString(KEY_LAST_STARTED_IP, null)
        AppLog.d(TAG, "getLastStartedConfig: country=${country ?: "<none>"} hasConfig=${cfg != null}")
        return if (cfg.isNullOrBlank()) null else LastConfig(country, cfg, ip)
    }

    fun ensureIndexForConfig(ctx: Context, config: String?, ip: String? = null) {
        if (config.isNullOrBlank() && ip.isNullOrBlank()) return
        val list = getServers(ctx)
        if (list.isEmpty()) return
        val current = getIndex(ctx)
        if (current in list.indices &&
            list[current].config == config &&
            (ip.isNullOrBlank() || list[current].ip == ip)
        ) return

        if (!config.isNullOrBlank() && !ip.isNullOrBlank()) {
            val foundByConfigAndIp = list.indexOfFirst { it.config == config && it.ip == ip }
            if (foundByConfigAndIp >= 0) {
                setIndex(ctx, foundByConfigAndIp)
                AppLog.d(
                    TAG,
                    "ensureIndexForConfig: matched by config+ip index=${foundByConfigAndIp + 1}/${list.size} ip=${ip ?: "<none>"}"
                )
                return
            }
        }

        val foundByConfig = config?.let { cfg -> list.indexOfFirst { it.config == cfg } } ?: -1
        if (foundByConfig >= 0) {
            setIndex(ctx, foundByConfig)
            val matchedIp = list[foundByConfig].ip
            AppLog.d(
                TAG,
                "ensureIndexForConfig: matched by config index=${foundByConfig + 1}/${list.size} ip=${matchedIp ?: "<none>"}"
            )
            return
        }
        if (!ip.isNullOrBlank()) {
            val foundByIp = list.indexOfFirst { it.ip == ip }
            if (foundByIp >= 0) {
                setIndex(ctx, foundByIp)
                AppLog.d(TAG, "ensureIndexForConfig: matched by ip index=${foundByIp + 1}/${list.size} ip=$ip")
            }
        }
    }

    /**
     * Updates the persisted country name without modifying servers or index.
     * Used for relocalization when the language changes.
     */
    fun updateSelectedCountryName(ctx: Context, newCountryName: String) {
        val currentName = getSelectedCountry(ctx)
        if (currentName == newCountryName) {
            AppLog.d(TAG, "updateSelectedCountryName: no change (already '$newCountryName')")
            return
        }
        val prefs = prefs(ctx)
        val editor = prefs.edit().putString(KEY_COUNTRY, newCountryName)

        val lastSuccessCountry = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
        if (lastSuccessCountry == currentName) {
            editor.putString(KEY_LAST_SUCCESS_COUNTRY, newCountryName)
        }

        val lastStartedCountry = prefs.getString(KEY_LAST_STARTED_COUNTRY, null)
        if (lastStartedCountry == currentName) {
            editor.putString(KEY_LAST_STARTED_COUNTRY, newCountryName)
        }

        editor.apply()
        AppLog.i(TAG, "updateSelectedCountryName: '$currentName' -> '$newCountryName'")
        SelectedCountryVersionSignal.bump()
    }
}




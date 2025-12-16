package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import android.util.Log

data class StoredServer(val city: String, val config: String, val countryCode: String? = null)

object SelectedCountryStore {
    private const val PREFS_NAME = "vpn_selection_prefs"
    private const val KEY_COUNTRY = "selected_country"
    private const val KEY_SERVERS = "selected_country_servers"
    private const val KEY_INDEX = "selected_country_index"
    private const val KEY_LAST_SUCCESS_COUNTRY = "last_success_country"
    private const val KEY_LAST_SUCCESS_CONFIG = "last_success_config"
    private const val KEY_LAST_STARTED_COUNTRY = "last_started_country"
    private const val KEY_LAST_STARTED_CONFIG = "last_started_config"
    private const val TAG = "SelectedCountryStore"
    private const val KEY_JSON_CITY = "city"
    private const val KEY_JSON_CONFIG = "config"
    private const val KEY_JSON_CODE = "code"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSelection(ctx: Context, country: String, servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { s ->
            val o = JSONObject()
                .put(KEY_JSON_CITY, s.city)
                .put(KEY_JSON_CONFIG, s.configData)
                .put(KEY_JSON_CODE, s.country.code)
            arr.put(o)
        }
        prefs(ctx).edit()
            .putString(KEY_COUNTRY, country)
            .putString(KEY_SERVERS, arr.toString())
            .putInt(KEY_INDEX, 0)
            .apply()
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
                    countryCode = o.optString(KEY_JSON_CODE, null)
                )
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing servers JSON from SharedPreferences", e)
            emptyList()
        }
    }

    fun resetIndex(ctx: Context) { prefs(ctx).edit().putInt(KEY_INDEX, 0).apply() }

    fun prepareAutoSwitchFromStart(ctx: Context) { prefs(ctx).edit().putInt(KEY_INDEX, -1).apply() }

    private fun getIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_INDEX, 0)

    private fun setIndex(ctx: Context, index: Int) { prefs(ctx).edit().putInt(KEY_INDEX, index).apply() }

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

    fun saveLastSuccessfulConfig(ctx: Context, country: String?, config: String) {
        if (config.isBlank()) return
        prefs(ctx).edit()
            .putString(KEY_LAST_SUCCESS_CONFIG, config)
            .putString(KEY_LAST_SUCCESS_COUNTRY, country)
            .apply()
    }

    fun getLastSuccessfulConfigForSelected(ctx: Context): String? {
        val prefs = prefs(ctx)
        val config = prefs.getString(KEY_LAST_SUCCESS_CONFIG, null)
        val selected = getSelectedCountry(ctx)
        val country = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
        Log.d(TAG, "getLastSuccessfulConfigForSelected: selected=${selected ?: "<none>"} storedCountry=${country ?: "<none>"} hasConfig=${config != null}")
        if (config.isNullOrBlank() || selected.isNullOrBlank()) return null
        return if (selected == country) config else null
    }

    fun saveLastStartedConfig(ctx: Context, country: String?, config: String) {
        if (config.isBlank()) return
        prefs(ctx).edit()
            .putString(KEY_LAST_STARTED_CONFIG, config)
            .putString(KEY_LAST_STARTED_COUNTRY, country)
            .apply()
    }

    fun getLastStartedConfig(ctx: Context): Pair<String?, String?>? {
        val prefs = prefs(ctx)
        val cfg = prefs.getString(KEY_LAST_STARTED_CONFIG, null)
        val country = prefs.getString(KEY_LAST_STARTED_COUNTRY, null)
        Log.d(TAG, "getLastStartedConfig: country=${country ?: "<none>"} hasConfig=${cfg != null}")
        return if (cfg.isNullOrBlank()) null else (country to cfg)
    }

    fun ensureIndexForConfig(ctx: Context, config: String?) {
        if (config.isNullOrBlank()) return
        val list = getServers(ctx)
        if (list.isEmpty()) return
        val current = getIndex(ctx)
        if (current in list.indices && list[current].config == config) return
        val found = list.indexOfFirst { it.config == config }
        val newIndex = if (found >= 0) found else 0
        setIndex(ctx, newIndex)
    }
}

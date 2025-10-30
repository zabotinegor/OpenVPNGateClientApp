package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class StoredServer(val city: String, val config: String)

object SelectedCountryStore {
    private const val PREFS_NAME = "vpn_selection_prefs"
    private const val KEY_COUNTRY = "selected_country"
    private const val KEY_SERVERS = "selected_country_servers"
    private const val KEY_INDEX = "selected_country_index"
    private const val KEY_JSON_CITY = "city"
    private const val KEY_JSON_CONFIG = "config"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSelection(ctx: Context, country: String, servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { s ->
            val o = JSONObject()
                .put(KEY_JSON_CITY, s.city)
                .put(KEY_JSON_CONFIG, s.configData)
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
                StoredServer(o.optString(KEY_JSON_CITY), o.optString(KEY_JSON_CONFIG))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun resetIndex(ctx: Context) { prefs(ctx).edit().putInt(KEY_INDEX, 0).apply() }

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
}

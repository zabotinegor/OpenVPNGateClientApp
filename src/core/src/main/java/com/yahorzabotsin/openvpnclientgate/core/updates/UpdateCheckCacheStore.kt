package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import org.json.JSONObject

object UpdateCheckCacheStore {
    private const val PREFS_NAME = "update_check_cache"
    private const val KEY_PREFIX = "update_"
    private const val KEY_CACHED_AT_MS = "cachedAtMs"
    private const val MAX_CHANGELOG_LENGTH = 100_000

    fun get(
        context: Context,
        currentBuild: Long,
        releaseType: String,
        locale: String,
        sourceKey: String,
        cacheTtlMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): AppUpdateInfo? {
        val key = composeKey(currentBuild, releaseType, normalizeLocale(locale), sourceKey)
        val raw = prefs(context).getString(key, null) ?: return null
        val parsed = parse(raw) ?: return null
        val cachedAt = JSONObject(raw).optLong(KEY_CACHED_AT_MS, 0L)
        if (cacheTtlMs > 0L && cachedAt > 0L && nowEpochMs - cachedAt > cacheTtlMs) return null
        return parsed
    }

    fun put(
        context: Context,
        currentBuild: Long,
        releaseType: String,
        locale: String,
        sourceKey: String,
        value: AppUpdateInfo,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val json = JSONObject()
            .put("hasUpdate", value.hasUpdate)
            .put("currentBuild", value.currentBuild)
            .put("latestBuild", value.latestBuild ?: JSONObject.NULL)
            .put("latestVersion", value.latestVersion ?: "")
            .put("name", value.name)
            .put("changelog", value.changelog.take(MAX_CHANGELOG_LENGTH))
            .put("resolvedLocale", value.resolvedLocale ?: "")
            .put("message", value.message)
            .put(
                "asset",
                value.asset?.let {
                    JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("platform", it.platform)
                        .put("buildNumber", it.buildNumber ?: JSONObject.NULL)
                        .put("assetType", it.assetType)
                        .put("sizeBytes", it.sizeBytes)
                        .put("contentHash", it.contentHash)
                        .put("downloadProxyUrl", it.downloadProxyUrl)
                } ?: JSONObject.NULL
            )
            .put(KEY_CACHED_AT_MS, nowEpochMs)
            .toString()

        prefs(context).edit()
            .putString(composeKey(currentBuild, releaseType, normalizeLocale(locale), sourceKey), json)
            .apply()
    }

    private fun parse(raw: String): AppUpdateInfo? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val hasUpdate = root.optBoolean("hasUpdate", false)
        val currentBuild = root.optLong("currentBuild", 0L)
        val latestBuild = if (root.isNull("latestBuild")) null else root.optLong("latestBuild", 0L).takeIf { it > 0L }
        val latestVersion = root.optString("latestVersion", "").ifBlank { null }
        val name = root.optString("name", "")
        val changelog = root.optString("changelog", "")
        val resolvedLocale = root.optString("resolvedLocale", "").ifBlank { null }
        val message = root.optString("message", "")
        val assetObj = root.optJSONObject("asset")
        val asset = assetObj?.let {
            AppUpdateAsset(
                id = it.optInt("id", 0),
                name = it.optString("name", ""),
                platform = it.optString("platform", "").ifBlank { "mobile" },
                buildNumber = if (it.isNull("buildNumber")) null else it.optLong("buildNumber", 0L).takeIf { value -> value > 0L },
                assetType = it.optString("assetType", ""),
                sizeBytes = it.optLong("sizeBytes", 0L),
                contentHash = it.optString("contentHash", ""),
                downloadProxyUrl = it.optString("downloadProxyUrl", "")
            )
        }
        if (currentBuild <= 0L) return null
        return AppUpdateInfo(
            hasUpdate = hasUpdate,
            currentBuild = currentBuild,
            latestBuild = latestBuild,
            latestVersion = latestVersion,
            name = name,
            changelog = changelog,
            resolvedLocale = resolvedLocale,
            message = message,
            asset = asset
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun composeKey(
        currentBuild: Long,
        releaseType: String,
        locale: String,
        sourceKey: String
    ): String =
        "$KEY_PREFIX$currentBuild|${releaseType.lowercase()}|$locale|$sourceKey"

    private fun normalizeLocale(locale: String): String = locale.trim().replace('_', '-').lowercase()
}

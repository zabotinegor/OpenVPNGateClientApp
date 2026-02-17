package com.yahorzabotsin.openvpnclientgate.core.versions

import android.content.Context
import org.json.JSONObject

object VersionReleaseCacheStore {
    const val FALLBACK_LOCALE_TTL_MS: Long = 60 * 60 * 1000L

    data class CacheLookup(
        val value: LatestReleaseInfo,
        val matchedLocale: String,
        val matchedByFallback: Boolean
    )

    private data class CacheEntry(
        val value: LatestReleaseInfo,
        val cachedAtMs: Long
    )

    private const val PREFS_NAME = "version_release_cache"
    private const val KEY_PREFIX = "release_"
    private const val KEY_VERSION_NUMBER = "versionNumber"
    private const val KEY_NAME = "name"
    private const val KEY_CHANGELOG = "changelog"
    private const val KEY_RESOLVED_LOCALE = "resolvedLocale"
    private const val KEY_CACHED_AT_MS = "cachedAtMs"
    private const val MAX_CHANGELOG_LENGTH = 100_000

    fun get(
        context: Context,
        versionName: String,
        buildNumber: Long,
        locale: String,
        sourceKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        fallbackLocaleTtlMs: Long = FALLBACK_LOCALE_TTL_MS
    ): CacheLookup? {
        val normalizedLocale = normalizeLocale(locale)
        val exact = prefs(context).getString(composeKey(versionName, buildNumber, normalizedLocale, sourceKey), null)
        if (!exact.isNullOrBlank()) {
            return parse(exact)?.takeUnless {
                isExpiredFallback(it, requestedLocale = normalizedLocale, nowEpochMs = nowEpochMs, fallbackLocaleTtlMs = fallbackLocaleTtlMs)
            }?.let {
                CacheLookup(
                    value = it.value,
                    matchedLocale = normalizedLocale,
                    matchedByFallback = false
                )
            }
        }

        val baseLocale = toBaseLocale(normalizedLocale)
        if (baseLocale != null && !baseLocale.equals(normalizedLocale, ignoreCase = true)) {
            val base = prefs(context).getString(composeKey(versionName, buildNumber, baseLocale, sourceKey), null)
            if (!base.isNullOrBlank()) {
                return parse(base)?.takeUnless {
                    isExpiredFallback(it, requestedLocale = normalizedLocale, nowEpochMs = nowEpochMs, fallbackLocaleTtlMs = fallbackLocaleTtlMs)
                }?.let {
                    CacheLookup(
                        value = it.value,
                        matchedLocale = baseLocale,
                        matchedByFallback = true
                    )
                }
            }
        }

        return null
    }

    fun put(
        context: Context,
        versionName: String,
        buildNumber: Long,
        locale: String,
        sourceKey: String,
        value: LatestReleaseInfo,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val raw = JSONObject()
            .put(KEY_VERSION_NUMBER, value.versionNumber)
            .put(KEY_NAME, value.name)
            .put(KEY_CHANGELOG, value.changelog.take(MAX_CHANGELOG_LENGTH))
            .put(KEY_RESOLVED_LOCALE, value.resolvedLocale ?: "")
            .put(KEY_CACHED_AT_MS, nowEpochMs)
            .toString()

        prefs(context)
            .edit()
            .putString(composeKey(versionName, buildNumber, normalizeLocale(locale), sourceKey), raw)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun composeKey(versionName: String, buildNumber: Long, locale: String, sourceKey: String): String {
        val normalizedVersion = versionName.trim()
        return "$KEY_PREFIX$normalizedVersion|$buildNumber|$locale|$sourceKey"
    }

    private fun parse(raw: String): CacheEntry? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val versionNumber = root.optString(KEY_VERSION_NUMBER, "")
        val name = root.optString(KEY_NAME, "")
        val changelog = root.optString(KEY_CHANGELOG, "")
        val resolvedLocale = root.optString(KEY_RESOLVED_LOCALE, "").ifBlank { null }
        val cachedAtMs = root.optLong(KEY_CACHED_AT_MS, 0L)
        if (versionNumber.isBlank() && name.isBlank() && changelog.isBlank()) return null

        return CacheEntry(
            value = LatestReleaseInfo(
                versionNumber = versionNumber,
                name = name,
                changelog = changelog,
                resolvedLocale = resolvedLocale
            ),
            cachedAtMs = cachedAtMs
        )
    }

    private fun isExpiredFallback(
        entry: CacheEntry,
        requestedLocale: String,
        nowEpochMs: Long,
        fallbackLocaleTtlMs: Long
    ): Boolean {
        if (fallbackLocaleTtlMs <= 0L) return false
        val resolved = entry.value.resolvedLocale?.let(::normalizeLocale) ?: return false
        if (resolved == requestedLocale) return false
        if (entry.cachedAtMs <= 0L) return false
        return nowEpochMs - entry.cachedAtMs > fallbackLocaleTtlMs
    }

    private fun toBaseLocale(locale: String): String? {
        val normalized = normalizeLocale(locale)
        if (normalized.isBlank()) return null
        val dashIndex = normalized.indexOf('-')
        return if (dashIndex > 0) normalized.substring(0, dashIndex).lowercase() else normalized.lowercase()
    }

    private fun normalizeLocale(locale: String): String = locale.trim().replace('_', '-').lowercase()
}

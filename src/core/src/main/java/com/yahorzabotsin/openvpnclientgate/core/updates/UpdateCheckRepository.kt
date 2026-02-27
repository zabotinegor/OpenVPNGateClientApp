package com.yahorzabotsin.openvpnclientgate.core.updates

import android.app.UiModeManager
import android.content.Context
import android.net.Uri
import android.content.res.Configuration
import androidx.core.content.pm.PackageInfoCompat
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
import com.yahorzabotsin.openvpnclientgate.core.BuildConfig
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.http.GET
import retrofit2.http.Url
import java.security.MessageDigest
import java.util.Locale

interface UpdateCheckApi {
    @GET
    suspend fun checkUpdate(@Url url: String): ResponseBody
}

interface UpdateCheckRepository {
    suspend fun checkForUpdate(forceRefresh: Boolean = false): AppUpdateInfo?
}

class DefaultUpdateCheckRepository(
    private val appContext: Context,
    private val api: UpdateCheckApi,
    private val settingsStore: UserSettingsStore = UserSettingsStore,
    private val cacheStore: UpdateCheckCacheStore = UpdateCheckCacheStore
) : UpdateCheckRepository {
    private companion object {
        const val KEY_SUCCESS = "success"
        const val KEY_SUCCESS_ALT = "Success"
    }

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "UpdateCheckRepository"

    override suspend fun checkForUpdate(forceRefresh: Boolean): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val currentBuild = getCurrentBuildNumber()
        if (currentBuild <= 0L) {
            AppLog.w(tag, "Unable to resolve current build number")
            return@withContext null
        }

        val settings = settingsStore.load(appContext)
        val preferredLocale = resolvePreferredLocale(settings.language)
        val urls = resolveTrustedUpdateSources()
        if (urls.isEmpty()) return@withContext null
        val platform = resolvePlatform()
        val releaseType = resolveReleaseType()
        val sourceKey = sourceKey(urls)

        if (!forceRefresh) {
            cacheStore.get(
                context = appContext,
                currentBuild = currentBuild,
                platform = platform,
                releaseType = releaseType,
                locale = preferredLocale,
                sourceKey = sourceKey,
                cacheTtlMs = settings.cacheTtlMs
            )?.let { return@withContext it }
        }

        val queryUrls = urls.mapNotNull {
            toCheckUpdateUrl(
                sourceUrl = it,
                platform = platform,
                releaseType = releaseType,
                currentBuild = currentBuild,
                locale = preferredLocale
            )
        }.distinct()

        for (url in queryUrls) {
            AppLog.i(tag, "Checking updates: $url")
            runCatching {
                api.checkUpdate(url).use { body ->
                    parseCheckUpdate(body.string())
                }
            }.onSuccess { parsed ->
                if (parsed != null) {
                    AppLog.i(
                        tag,
                        "Update check result: hasUpdate=${parsed.hasUpdate}, currentBuild=${parsed.currentBuild}, latestBuild=${parsed.latestBuild}, platform=${parsed.platform}, hasAsset=${parsed.asset != null}"
                    )
                    cacheStore.put(
                        context = appContext,
                        currentBuild = currentBuild,
                        platform = platform,
                        releaseType = releaseType,
                        locale = preferredLocale,
                        sourceKey = sourceKey,
                        value = parsed
                    )
                    return@withContext parsed
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                AppLog.w(tag, "Failed to check updates from $url", error)
            }
        }

        return@withContext null
    }

    private fun getCurrentBuildNumber(): Long {
        val pInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrElse { return 0L }
        return PackageInfoCompat.getLongVersionCode(pInfo)
    }

    private fun resolvePlatform(): String {
        val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        return if (isTv) "tv" else "mobile"
    }

    private fun resolveReleaseType(): String {
        return BuildConfig.APP_RELEASE_TYPE.trim().lowercase()
    }

    private fun toCheckUpdateUrl(
        sourceUrl: String,
        platform: String,
        releaseType: String,
        currentBuild: Long,
        locale: String?
    ): String? {
        val uri = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val authority = uri.encodedAuthority ?: return null
        val basePathPrefix = extractApiBasePathPrefix(uri.encodedPath.orEmpty())
        val path = "$basePathPrefix/api/v1/versions/check-update"
        val builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(authority)
            .path(path)
            .appendQueryParameter("platform", platform)
            .appendQueryParameter("releaseType", releaseType)
            .appendQueryParameter("currentBuild", currentBuild.toString())
        locale?.trim()?.takeIf { it.isNotBlank() }?.let {
            builder.appendQueryParameter("locale", it)
        }
        return builder.build().toString()
    }

    private fun extractApiBasePathPrefix(encodedPath: String): String {
        val marker = "/api/v1/"
        val markerIndex = encodedPath.indexOf(marker)
        if (markerIndex <= 0) return ""

        val prefix = encodedPath.substring(0, markerIndex).trimEnd('/')
        if (prefix.isBlank()) return ""
        return if (prefix.startsWith('/')) prefix else "/$prefix"
    }

    private fun resolvePreferredLocale(language: LanguageOption): String =
        when (language) {
            LanguageOption.SYSTEM -> Locale.getDefault().language.ifBlank { "en" }
            LanguageOption.ENGLISH -> "en"
            LanguageOption.RUSSIAN -> "ru"
            LanguageOption.POLISH -> "pl"
        }

    private fun sourceKey(urls: List<String>): String {
        val joined = urls.joinToString("|")
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.getOrDefault(joined)
    }

    private fun resolveTrustedUpdateSources(): List<String> {
        return listOf(
            ApiConstants.PRIMARY_SERVERS_URL,
            ApiConstants.FALLBACK_SERVERS_URL
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun parseCheckUpdate(rawJson: String): AppUpdateInfo? {
        if (rawJson.isBlank()) return null
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        if (!root.optBooleanAny(KEY_SUCCESS, KEY_SUCCESS_ALT)) return null
        val data = root.optObjectAny("data", "Data") ?: return null
        val hasUpdate = data.optBooleanAny("hasUpdate", "HasUpdate")
        val currentBuild = data.optLongAny("currentBuild", "CurrentBuild")
        val latestBuild = data.optLongAny("latestBuild", "LatestBuild").takeIf { it > 0 }
        val platform = resolvePlatformValue(data)
        if (currentBuild <= 0L || platform.isBlank()) return null
        val assetData = data.optObjectAny("updateAsset", "UpdateAsset")
        val asset = assetData?.let {
            AppUpdateAsset(
                id = it.optIntAny("id", "Id"),
                name = it.optStringAny("name", "Name"),
                assetType = it.optStringAny("assetType", "AssetType"),
                sizeBytes = it.optLongAny("sizeBytes", "SizeBytes"),
                contentHash = it.optStringAny("contentHash", "ContentHash"),
                downloadProxyUrl = it.optStringAny("downloadProxyUrl", "DownloadProxyUrl")
            )
        }?.takeIf { it.downloadProxyUrl.isNotBlank() }

        return AppUpdateInfo(
            hasUpdate = hasUpdate,
            currentBuild = currentBuild,
            latestBuild = latestBuild,
            platform = platform,
            latestVersion = data.optStringAny("latestVersion", "LatestVersion").ifBlank { null },
            name = data.optStringAny("name", "Name"),
            changelog = data.optStringAny("changelog", "Changelog"),
            resolvedLocale = data.optStringAny("resolvedLocale", "ResolvedLocale").ifBlank { null },
            message = data.optStringAny("message", "Message"),
            asset = asset
        )
    }

    private fun JSONObject.optStringAny(vararg keys: String): String {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = optString(key, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JSONObject.optBooleanAny(vararg keys: String): Boolean {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            return optBoolean(key, false)
        }
        return false
    }

    private fun JSONObject.optObjectAny(vararg keys: String): JSONObject? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = optJSONObject(key)
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.optLongAny(vararg keys: String): Long {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            return optLong(key, 0L)
        }
        return 0L
    }

    private fun JSONObject.optIntAny(vararg keys: String): Int {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            return optInt(key, 0)
        }
        return 0
    }

    private fun resolvePlatformValue(data: JSONObject): String {
        val asString = data.optStringAny("platform", "Platform")
        if (asString.isNotBlank()) {
            val normalized = asString.trim().lowercase()
            return when (normalized) {
                "tv", "mobile" -> normalized
                "1" -> "tv"
                "0" -> "mobile"
                else -> "mobile"
            }
        }
        val asInt = data.optIntAny("platform", "Platform")
        return when (asInt) {
            1 -> "tv"
            else -> "mobile"
        }
    }
}

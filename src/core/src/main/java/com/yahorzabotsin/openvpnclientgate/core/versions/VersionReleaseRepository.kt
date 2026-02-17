package com.yahorzabotsin.openvpnclientgate.core.versions

import android.content.Context
import android.net.Uri
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

data class LatestReleaseInfo(
    val versionNumber: String,
    val name: String,
    val changelog: String,
    val resolvedLocale: String? = null
)

interface VersionsApi {
    @GET
    suspend fun getByVersionAndBuild(@Url url: String): ResponseBody
}

interface VersionReleaseRepository {
    suspend fun getLatestRelease(): LatestReleaseInfo?
}

class DefaultVersionReleaseRepository(
    private val appContext: Context,
    private val api: VersionsApi,
    private val settingsStore: UserSettingsStore = UserSettingsStore,
    private val cacheStore: VersionReleaseCacheStore = VersionReleaseCacheStore
) : VersionReleaseRepository {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "VersionReleaseRepository"

    override suspend fun getLatestRelease(): LatestReleaseInfo? = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentAppVersion() ?: run {
            AppLog.w(tag, "Unable to resolve current app version/build")
            return@withContext null
        }
        if (currentVersion.versionName.isBlank() || currentVersion.buildNumber <= 0L) {
            AppLog.w(tag, "Invalid current app version/build: ${currentVersion.versionName}/${currentVersion.buildNumber}")
            return@withContext null
        }

        val settings = settingsStore.load(appContext)
        val preferredLocale = resolvePreferredLocale(settings.language)
        val urls = settingsStore.resolveServerUrls(settings)
        if (urls.isEmpty()) {
            AppLog.d(tag, "No server urls resolved for source=${settings.serverSource}. What's new unavailable.")
            return@withContext null
        }
        val sourceKey = sourceKey(urls)

        cacheStore.get(
            context = appContext,
            versionName = currentVersion.versionName,
            buildNumber = currentVersion.buildNumber,
            locale = preferredLocale,
            sourceKey = sourceKey
        )?.let { cached ->
            AppLog.i(
                tag,
                "Using cached release notes for ${currentVersion.versionName}(${currentVersion.buildNumber}) locale=$preferredLocale matchedLocale=${cached.matchedLocale} fallback=${cached.matchedByFallback}"
            )
            return@withContext cached.value
        }

        AppLog.d(
            tag,
            "Release notes cache miss: version=${currentVersion.versionName}, build=${currentVersion.buildNumber}, locale=$preferredLocale, sourceKey=${sourceKey.take(8)}"
        )

        val versionUrls = urls.mapNotNull {
            toVersionByNumberAndBuildUrl(
                sourceUrl = it,
                versionName = currentVersion.versionName,
                buildNumber = currentVersion.buildNumber,
                locale = preferredLocale
            )
        }.distinct()
        for (url in versionUrls) {
            runCatching {
                api.getByVersionAndBuild(url).use { body ->
                    parseVersionByNumberAndBuild(rawJson = body.string())
                }
            }.onSuccess { parsed ->
                if (parsed != null) {
                    cacheStore.put(
                        context = appContext,
                        versionName = currentVersion.versionName,
                        buildNumber = currentVersion.buildNumber,
                        locale = preferredLocale,
                        sourceKey = sourceKey,
                        value = parsed
                    )
                    AppLog.i(
                        tag,
                        "Loaded release notes for ${currentVersion.versionName}(${currentVersion.buildNumber}) from $url; requestedLocale=$preferredLocale, resolvedLocale=${parsed.resolvedLocale ?: "<none>"}"
                    )
                    return@withContext parsed
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                AppLog.w(tag, "Failed to load release by version/build from $url", error)
            }
        }

        return@withContext null
    }

    private data class CurrentAppVersion(
        val versionName: String,
        val buildNumber: Long
    )

    private fun getCurrentAppVersion(): CurrentAppVersion? {
        val pInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrElse {
            AppLog.w(tag, "Failed to read package info for ${appContext.packageName}", it)
            return null
        }
        val versionName = pInfo.versionName ?: ""
        val buildNumber = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }
        return CurrentAppVersion(versionName = versionName, buildNumber = buildNumber)
    }

    private fun toVersionByNumberAndBuildUrl(
        sourceUrl: String,
        versionName: String,
        buildNumber: Long,
        locale: String?
    ): String? {
        val uri = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.encodedAuthority ?: return null
        val basePathPrefix = extractApiBasePathPrefix(uri.encodedPath.orEmpty())
        val encodedVersion = Uri.encode(versionName)
        val localeQuery = locale
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "?locale=${Uri.encode(it)}" }
            .orEmpty()
        return "$scheme://$authority$basePathPrefix/api/v1/versions/number/$encodedVersion/build/$buildNumber$localeQuery"
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

    private fun parseVersionByNumberAndBuild(rawJson: String): LatestReleaseInfo? {
        if (rawJson.isBlank()) return null
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        if (!root.optBooleanAny("success", "Success")) return null
        val data = root.optObjectAny("data", "Data") ?: return null

        val versionNumber = data.optStringAny("versionNumber", "VersionNumber")
        val name = data.optStringAny("name", "Name")
        val changelog = data.optStringAny("changelog", "Changelog")
        val resolvedLocale = data.optStringAny("resolvedLocale", "ResolvedLocale").ifBlank { null }
        if (versionNumber.isBlank() && changelog.isBlank() && name.isBlank()) return null

        return LatestReleaseInfo(
            versionNumber = versionNumber,
            name = name,
            changelog = changelog,
            resolvedLocale = resolvedLocale
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

}

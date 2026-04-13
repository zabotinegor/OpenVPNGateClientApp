package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.BuildConfig
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class UpdateCheckRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("update_check_cache", Context.MODE_PRIVATE).edit().clear().commit()
        setPackageInfo(versionName = "1.0", buildNumber = 1L)
    }

    @Test
    fun `checkForUpdate builds correct request url`() = runTest {
        val api = CapturingUpdateApi()
        val repository = DefaultUpdateCheckRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.RUSSIAN,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNotNull(result)
        assertEquals(
            expectedCheckUpdateUrl(
                sourceUrl = BuildConfig.PRIMARY_SERVERS_URL,
                platform = "mobile",
                releaseType = BuildConfig.APP_RELEASE_TYPE.trim().lowercase(),
                currentBuild = 1L,
                locale = "ru"
            ),
            api.requestedUrls.single()
        )
    }

    @Test
    fun `checkForUpdate ignores custom server source`() = runTest {
        val api = CapturingUpdateApi()
        val repository = DefaultUpdateCheckRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://attacker.example.com/api/v1/servers/active"
            )
        )

        repository.checkForUpdate(forceRefresh = true)

        val requestedHost = Uri.parse(api.requestedUrls.single()).host
        val primaryHost = Uri.parse(BuildConfig.PRIMARY_SERVERS_URL).host
        assertEquals(primaryHost, requestedHost)
    }

    @Test
    fun `checkForUpdate uses configured release type regardless of versionName`() = runTest {
        val configuredReleaseType = BuildConfig.APP_RELEASE_TYPE.trim().lowercase()
        val oppositeVersionName = if (configuredReleaseType == "beta") "1.0" else "1.0-beta.1"
        setPackageInfo(versionName = oppositeVersionName, buildNumber = 2L)

        val api = CapturingUpdateApi()
        val repository = DefaultUpdateCheckRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        repository.checkForUpdate(forceRefresh = true)

        val url = api.requestedUrls.single()
        assertEquals(true, url.contains("releaseType=$configuredReleaseType"))
        assertEquals(true, url.contains("currentBuild=2"))
    }

    @Test
    fun `checkForUpdate caches result and skips second network call`() = runTest {
        val api = CapturingUpdateApi()
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val first = repository.checkForUpdate(forceRefresh = false)
        val second = repository.checkForUpdate(forceRefresh = false)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1, api.callCount)
    }

    @Test
    fun `forceRefresh bypasses cache`() = runTest {
        val api = CapturingUpdateApi()
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        repository.checkForUpdate(forceRefresh = true)
        repository.checkForUpdate(forceRefresh = true)

        assertEquals(2, api.callCount)
    }

    @Test
    fun `checkForUpdate retries next source when first source fails`() = runTest {
        val primaryHost = Uri.parse(BuildConfig.PRIMARY_SERVERS_URL).host.orEmpty()
        val api = CapturingUpdateApi(
            failUrlsContaining = listOf(primaryHost)
        )
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNotNull(result)
        assertEquals(true, api.callCount >= 2)
    }

    @Test
    fun `checkForUpdate parses numeric platform and asset`() = runTest {
        val api = CapturingUpdateApi(
            responseJson = """
                {"success":true,"data":{
                  "hasUpdate":true,
                  "currentBuild":1,
                  "latestBuild":2,
                  "latestVersion":"1.1",
                  "name":"Release",
                  "changelog":"## Changes",
                  "resolvedLocale":"en",
                  "message":"Update available.",
                  "updateAsset":{
                    "id":7,
                    "name":"mobile.apk",
                    "platform":"0",
                    "buildNumber":2,
                    "assetType":"apk-mobile",
                    "sizeBytes":123,
                    "contentHash":"hash",
                    "downloadProxyUrl":"https://example.com/api/v1/download-assets/1/7"
                  }
                }}
            """.trimIndent()
        )
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNotNull(result)
        assertEquals(true, result?.hasUpdate)
        assertEquals("mobile", result?.asset?.platform)
        assertEquals(2L, result?.asset?.buildNumber)
        assertEquals("mobile.apk", result?.asset?.name)
    }

    @Test
    fun `checkForUpdate parses legacy top level asset fields for older server compatibility`() = runTest {
        val api = CapturingUpdateApi(
            responseJson = """
                {"success":true,"data":{
                  "hasUpdate":true,
                  "currentBuild":1,
                  "latestBuild":5,
                  "latestVersion":"1.5",
                  "platform":"1",
                  "assetName":"tv.apk",
                  "assetType":"apk-tv",
                  "sizeBytes":321,
                  "contentHash":"legacy-hash",
                  "downloadProxyUrl":"https://example.com/api/v1/download-assets/1/9",
                  "message":"Update available."
                }}
            """.trimIndent()
        )
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNotNull(result)
        assertEquals(true, result?.hasUpdate)
        assertEquals("tv", result?.asset?.platform)
        assertEquals(5L, result?.asset?.buildNumber)
        assertEquals("tv.apk", result?.asset?.name)
        assertEquals("https://example.com/api/v1/download-assets/1/9", result?.asset?.downloadProxyUrl)
    }

    @Test
    fun `checkForUpdate falls back to mobile for unknown string asset platform`() = runTest {
        val api = CapturingUpdateApi(
            responseJson = """
                {"success":true,"data":{
                  "hasUpdate":true,
                  "currentBuild":1,
                  "latestBuild":2,
                  "latestVersion":"1.1",
                  "message":"Update available.",
                  "updateAsset":{
                    "id":7,
                    "name":"desktop.apk",
                    "platform":"desktop",
                    "assetType":"apk-mobile",
                    "sizeBytes":123,
                    "contentHash":"hash",
                    "downloadProxyUrl":"https://example.com/api/v1/download-assets/1/7"
                  }
                }}
            """.trimIndent()
        )
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNotNull(result)
        assertEquals("mobile", result?.asset?.platform)
    }

    @Test
    fun `checkForUpdate falls back to mobile for unknown numeric asset platform`() = runTest {
        val api = CapturingUpdateApi(
            responseJson = """
                {"success":true,"data":{
                  "hasUpdate":true,
                  "currentBuild":1,
                  "latestBuild":2,
                  "latestVersion":"1.1",
                  "message":"Update available.",
                  "updateAsset":{
                    "id":7,
                    "name":"mobile.apk",
                    "platform":77,
                    "assetType":"apk-mobile",
                    "sizeBytes":123,
                    "contentHash":"hash",
                    "downloadProxyUrl":"https://example.com/api/v1/download-assets/1/7"
                  }
                }}
            """.trimIndent()
        )
        val repository = DefaultUpdateCheckRepository(context, api)
        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNotNull(result)
        assertEquals("mobile", result?.asset?.platform)
    }

    @Test
    fun `checkForUpdate returns null when package info missing`() = runTest {
        val api = CapturingUpdateApi()
        val missingPackageContext = object : ContextWrapper(context) {
            override fun getPackageName(): String = "missing.package.name"
        }
        val repository = DefaultUpdateCheckRepository(missingPackageContext, api)
        UserSettingsStore.save(
            missingPackageContext,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT
            )
        )

        val result = repository.checkForUpdate(forceRefresh = true)

        assertNull(result)
        assertEquals(0, api.callCount)
    }

    @Test
    fun `checkForUpdate uses system locale when language is system`() = runTest {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("pl", "PL"))
        try {
            val api = CapturingUpdateApi()
            val repository = DefaultUpdateCheckRepository(context, api)
            UserSettingsStore.save(
                context,
                UserSettings(
                    language = LanguageOption.SYSTEM,
                    serverSource = ServerSource.DEFAULT
                )
            )

            repository.checkForUpdate(forceRefresh = true)

            val url = api.requestedUrls.single()
            assertEquals(true, url.contains("locale=pl"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun setPackageInfo(versionName: String, buildNumber: Long) {
        val packageInfo = PackageInfo().apply {
            packageName = context.packageName
            this.versionName = versionName
            @Suppress("DEPRECATION")
            versionCode = buildNumber.toInt()
            runCatching {
                val setter = PackageInfo::class.java.getMethod("setLongVersionCode", Long::class.javaPrimitiveType)
                setter.invoke(this, buildNumber)
            }
        }
        shadowOf(context.packageManager).installPackage(packageInfo)
    }

    private fun expectedCheckUpdateUrl(
        sourceUrl: String,
        platform: String,
        releaseType: String,
        currentBuild: Long,
        locale: String?
    ): String {
        val sourceUri = Uri.parse(sourceUrl)
        val scheme = sourceUri.scheme.orEmpty()
        val authority = sourceUri.encodedAuthority.orEmpty()
        val basePathPrefix = extractApiBasePathPrefix(sourceUri.encodedPath.orEmpty())
        val localeQuery = locale?.let { "&locale=$it" }.orEmpty()
        return "$scheme://$authority$basePathPrefix/api/v1/versions/check-update?platform=$platform&releaseType=$releaseType&currentBuild=$currentBuild$localeQuery"
    }

    private fun extractApiBasePathPrefix(encodedPath: String): String {
        val marker = "/api/v1/"
        val markerIndex = encodedPath.indexOf(marker)
        if (markerIndex <= 0) return ""
        val prefix = encodedPath.substring(0, markerIndex).trimEnd('/')
        if (prefix.isBlank()) return ""
        return if (prefix.startsWith('/')) prefix else "/$prefix"
    }

    private class CapturingUpdateApi(
        private val responseJson: String = """
            {"success":true,"data":{"hasUpdate":true,"currentBuild":1,"latestBuild":2,"message":"Update available."}}
        """.trimIndent(),
        private val failUrlsContaining: List<String> = emptyList()
    ) : UpdateCheckApi {
        val requestedUrls: MutableList<String> = mutableListOf()
        var callCount: Int = 0

        override suspend fun checkUpdate(url: String): ResponseBody {
            requestedUrls += url
            callCount += 1
            if (failUrlsContaining.any { url.contains(it) }) {
                throw IllegalStateException("forced failure for $url")
            }
            return responseJson.toResponseBody("application/json".toMediaType())
        }
    }
}


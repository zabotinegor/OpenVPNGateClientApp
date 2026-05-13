package com.yahorzabotsin.openvpnclientgate.core.versions

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class VersionReleaseRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("version_release_cache", Context.MODE_PRIVATE).edit().clear().commit()
        setPackageInfo(versionName = "1.2.3", buildNumber = 42L)
    }

    @Test
    fun `getLatestRelease adds locale from app language`() = runTest {
        val api = CapturingVersionsApi()
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.RUSSIAN,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/api/v1/servers/active"
            )
        )

        val result = repository.getLatestRelease()

        assertNotNull(result)
        assertEquals(
            ApiConstants.primaryVersionByNumberAndBuildUrl("1.2.3", 42L, "ru"),
            api.requestedUrl
        )
        assertEquals(1, api.callCount)
    }

    @Test
    fun `getLatestRelease returns cached data and skips second request`() = runTest {
        val api = CapturingVersionsApi()
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/api/v1/servers/active"
            )
        )

        val first = repository.getLatestRelease()
        val second = repository.getLatestRelease()

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first, second)
        assertEquals(1, api.callCount)
    }

    @Test
    fun `getLatestRelease re-requests when language changes`() = runTest {
        val api = CapturingVersionsApi()
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.RUSSIAN,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/api/v1/servers/active"
            )
        )
        repository.getLatestRelease()

        UserSettingsStore.saveLanguage(context, LanguageOption.ENGLISH)
        repository.getLatestRelease()

        assertEquals(2, api.callCount)
        assertFalse(api.requestedUrls.none { it.contains("locale=ru") })
        assertFalse(api.requestedUrls.none { it.contains("locale=en") })
    }

    @Test
    fun `getLatestRelease re-requests when app build changes`() = runTest {
        val api = CapturingVersionsApi()
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/api/v1/servers/active"
            )
        )
        repository.getLatestRelease()

        setPackageInfo(versionName = "1.2.3", buildNumber = 43L)
        repository.getLatestRelease()

        assertEquals(2, api.callCount)
        assertFalse(api.requestedUrls.none { it.contains("/build/42?locale=en") })
        assertFalse(api.requestedUrls.none { it.contains("/build/43?locale=en") })
    }

    @Test
    fun `getLatestRelease uses base language for system locale`() = runTest {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val api = CapturingVersionsApi()
            val repository = DefaultVersionReleaseRepository(context, api)

            UserSettingsStore.save(
                context,
                UserSettings(
                    language = LanguageOption.SYSTEM,
                    serverSource = ServerSource.CUSTOM,
                    customServerUrl = "https://api.example.com/api/v1/servers/active"
                )
            )

            repository.getLatestRelease()

            assertEquals(
                ApiConstants.primaryVersionByNumberAndBuildUrl("1.2.3", 42L, "en"),
                api.requestedUrl
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `getLatestRelease ignores server source changes for cache key and host selection`() = runTest {
        val api = CapturingVersionsApi()
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api-1.example.com/api/v1/servers/active"
            )
        )
        repository.getLatestRelease()

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api-2.example.com/api/v1/servers/active"
            )
        )
        repository.getLatestRelease()

        assertEquals(1, api.callCount)
        assertEquals(listOf(ApiConstants.primaryVersionByNumberAndBuildUrl("1.2.3", 42L, "en")), api.requestedUrls)
    }

    @Test
    fun `getLatestRelease keeps requested locale cache key when backend resolves fallback locale`() = runTest {
        val api = CapturingVersionsApi(
            responseJson = """
                {"success":true,"data":{"versionNumber":"1.2.3","name":"Release","changelog":"## Added","resolvedLocale":"en"}}
            """.trimIndent()
        )
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.POLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/api/v1/servers/active"
            )
        )

        repository.getLatestRelease()
        repository.getLatestRelease()

        assertEquals(1, api.callCount)
        assertFalse(api.requestedUrls.none { it.contains("locale=pl") })
    }

    @Test
    fun `getLatestRelease keeps using the trusted primary host for custom sources`() = runTest {
        val api = CapturingVersionsApi()
        val repository = DefaultVersionReleaseRepository(context, api)

        UserSettingsStore.save(
            context,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/custom/root/api/v1/servers/active"
            )
        )

        repository.getLatestRelease()

        assertEquals(
            ApiConstants.primaryVersionByNumberAndBuildUrl("1.2.3", 42L, "en"),
            api.requestedUrl
        )
    }

    @Test
    fun `getLatestRelease returns null when package info is unavailable`() = runTest {
        val api = CapturingVersionsApi()
        val unknownPackageContext = object : ContextWrapper(context) {
            override fun getPackageName(): String = "com.yahorzabotsin.openvpnclientgate.missing"
        }
        val repository = DefaultVersionReleaseRepository(unknownPackageContext, api)

        UserSettingsStore.save(
            unknownPackageContext,
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://api.example.com/api/v1/servers/active"
            )
        )

        val result = repository.getLatestRelease()

        assertNull(result)
        assertEquals(0, api.callCount)
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

    private class CapturingVersionsApi(
        private val responseJson: String = """
            {"success":true,"data":{"versionNumber":"1.2.3","name":"Release","changelog":"## Added"}}
        """.trimIndent()
    ) : VersionsApi {
        var requestedUrl: String? = null
        val requestedUrls: MutableList<String> = mutableListOf()
        var callCount: Int = 0

        override suspend fun getByVersionAndBuild(url: String): ResponseBody {
            requestedUrl = url
            requestedUrls += url
            callCount += 1
            return responseJson.toResponseBody("application/json".toMediaType())
        }
    }
}

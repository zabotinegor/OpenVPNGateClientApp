package com.yahorzabotsin.openvpnclientgate.core.versions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VersionReleaseCacheStoreTest {
    private companion object {
        const val ENTRY_TTL_MS = 1_000L
        const val VERSION_NAME = "1.2.3"
        const val BUILD_NUMBER = 42L
        const val SOURCE_KEY = "sourceA"
        const val RELEASE_NAME = "Release"
        const val CHANGELOG = "## Added"
        const val EN_LOCALE = "en"
        const val PL_LOCALE = "pl"
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("version_release_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `get falls back from region locale to base locale`() {
        putCacheEntry(locale = EN_LOCALE)

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = VERSION_NAME,
            buildNumber = BUILD_NUMBER,
            locale = "en-US",
            sourceKey = SOURCE_KEY,
            cacheTtlMs = ENTRY_TTL_MS
        )

        assertNotNull(cached)
        assertEquals("en", cached?.matchedLocale)
        assertEquals(true, cached?.matchedByFallback)
        assertEquals("Release", cached?.value?.name)
    }

    @Test
    fun `get keeps fallback locale cache within ttl`() {
        putCacheEntry(locale = PL_LOCALE, resolvedLocale = EN_LOCALE, cachedAtMs = 1_000L)

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = VERSION_NAME,
            buildNumber = BUILD_NUMBER,
            locale = PL_LOCALE,
            sourceKey = SOURCE_KEY,
            nowEpochMs = 1_000L + VersionReleaseCacheStore.FALLBACK_LOCALE_TTL_MS - 1L,
            cacheTtlMs = Long.MAX_VALUE
        )

        assertNotNull(cached)
        assertEquals("Release", cached?.value?.name)
    }

    @Test
    fun `get drops fallback locale cache after ttl`() {
        putCacheEntry(locale = PL_LOCALE, resolvedLocale = EN_LOCALE, cachedAtMs = 1_000L)

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = VERSION_NAME,
            buildNumber = BUILD_NUMBER,
            locale = PL_LOCALE,
            sourceKey = SOURCE_KEY,
            nowEpochMs = 1_000L + VersionReleaseCacheStore.FALLBACK_LOCALE_TTL_MS + 1L,
            cacheTtlMs = Long.MAX_VALUE
        )

        assertNull(cached)
    }

    @Test
    fun `get keeps exact locale cache within entry ttl`() {
        putCacheEntry(locale = EN_LOCALE, cachedAtMs = 1_000L)

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = VERSION_NAME,
            buildNumber = BUILD_NUMBER,
            locale = EN_LOCALE,
            sourceKey = SOURCE_KEY,
            nowEpochMs = 1_999L,
            cacheTtlMs = ENTRY_TTL_MS
        )

        assertNotNull(cached)
        assertEquals("Release", cached?.value?.name)
    }

    @Test
    fun `get drops exact locale cache after entry ttl`() {
        putCacheEntry(locale = EN_LOCALE, cachedAtMs = 1_000L)

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = VERSION_NAME,
            buildNumber = BUILD_NUMBER,
            locale = EN_LOCALE,
            sourceKey = SOURCE_KEY,
            nowEpochMs = 2_001L,
            cacheTtlMs = ENTRY_TTL_MS
        )

        assertNull(cached)
    }

    private fun putCacheEntry(
        locale: String,
        resolvedLocale: String? = null,
        cachedAtMs: Long? = null
    ) {
        val value = LatestReleaseInfo(
            versionNumber = VERSION_NAME,
            name = RELEASE_NAME,
            changelog = CHANGELOG,
            resolvedLocale = resolvedLocale
        )
        if (cachedAtMs == null) {
            VersionReleaseCacheStore.put(
                context = context,
                versionName = VERSION_NAME,
                buildNumber = BUILD_NUMBER,
                locale = locale,
                sourceKey = SOURCE_KEY,
                value = value
            )
            return
        }

        VersionReleaseCacheStore.put(
            context = context,
            versionName = VERSION_NAME,
            buildNumber = BUILD_NUMBER,
            locale = locale,
            sourceKey = SOURCE_KEY,
            value = value,
            nowEpochMs = cachedAtMs
        )
    }
}

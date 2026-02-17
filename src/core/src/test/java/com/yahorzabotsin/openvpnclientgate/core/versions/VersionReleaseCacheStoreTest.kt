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

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("version_release_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `get falls back from region locale to base locale`() {
        val value = LatestReleaseInfo(
            versionNumber = "1.2.3",
            name = "Release",
            changelog = "## Added"
        )
        VersionReleaseCacheStore.put(
            context = context,
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "en",
            sourceKey = "sourceA",
            value = value
        )

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "en-US",
            sourceKey = "sourceA"
        )

        assertNotNull(cached)
        assertEquals("en", cached?.matchedLocale)
        assertEquals(true, cached?.matchedByFallback)
        assertEquals("Release", cached?.value?.name)
    }

    @Test
    fun `get keeps fallback locale cache within ttl`() {
        VersionReleaseCacheStore.put(
            context = context,
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "pl",
            sourceKey = "sourceA",
            value = LatestReleaseInfo(
                versionNumber = "1.2.3",
                name = "Release",
                changelog = "## Added",
                resolvedLocale = "en"
            ),
            nowEpochMs = 1_000L
        )

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "pl",
            sourceKey = "sourceA",
            nowEpochMs = 1_000L + VersionReleaseCacheStore.FALLBACK_LOCALE_TTL_MS - 1L
        )

        assertNotNull(cached)
        assertEquals("Release", cached?.value?.name)
    }

    @Test
    fun `get drops fallback locale cache after ttl`() {
        VersionReleaseCacheStore.put(
            context = context,
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "pl",
            sourceKey = "sourceA",
            value = LatestReleaseInfo(
                versionNumber = "1.2.3",
                name = "Release",
                changelog = "## Added",
                resolvedLocale = "en"
            ),
            nowEpochMs = 1_000L
        )

        val cached = VersionReleaseCacheStore.get(
            context = context,
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "pl",
            sourceKey = "sourceA",
            nowEpochMs = 1_000L + VersionReleaseCacheStore.FALLBACK_LOCALE_TTL_MS + 1L
        )

        assertNull(cached)
    }
}

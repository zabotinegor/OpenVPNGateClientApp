package com.yahorzabotsin.openvpnclientgate.core.updates

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
class UpdateCheckCacheStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("update_check_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `put then get returns cached value within ttl`() {
        val value = sampleInfo(
            asset = AppUpdateAsset(
                id = 10,
                name = "app.apk",
                platform = "mobile",
                buildNumber = 2L,
                assetType = "apk-mobile",
                sizeBytes = 100,
                contentHash = "hash",
                downloadProxyUrl = "https://example.com/api/v1/download-assets/1/10"
            )
        )

        UpdateCheckCacheStore.put(
            context = context,
            currentBuild = 1,
            platform = "mobile",
            releaseType = "release",
            locale = "en",
            sourceKey = "sourceA",
            value = value,
            nowEpochMs = 1_000L
        )

        val cached = UpdateCheckCacheStore.get(
            context = context,
            currentBuild = 1,
            platform = "mobile",
            releaseType = "release",
            locale = "en",
            sourceKey = "sourceA",
            cacheTtlMs = 5_000L,
            nowEpochMs = 5_000L
        )

        assertNotNull(cached)
        assertEquals(true, cached?.hasUpdate)
        assertEquals(2L, cached?.latestBuild)
        assertEquals("mobile", cached?.asset?.platform)
        assertEquals(2L, cached?.asset?.buildNumber)
        assertEquals("app.apk", cached?.asset?.name)
    }

    @Test
    fun `get returns null when ttl expired`() {
        UpdateCheckCacheStore.put(
            context = context,
            currentBuild = 1,
            platform = "mobile",
            releaseType = "release",
            locale = "en",
            sourceKey = "sourceA",
            value = sampleInfo(),
            nowEpochMs = 1_000L
        )

        val cached = UpdateCheckCacheStore.get(
            context = context,
            currentBuild = 1,
            platform = "mobile",
            releaseType = "release",
            locale = "en",
            sourceKey = "sourceA",
            cacheTtlMs = 500L,
            nowEpochMs = 2_000L
        )

        assertNull(cached)
    }

    @Test
    fun `cache key separates release type and source`() {
        UpdateCheckCacheStore.put(
            context = context,
            currentBuild = 1,
            platform = "mobile",
            releaseType = "release",
            locale = "en",
            sourceKey = "sourceA",
            value = sampleInfo()
        )

        assertNotNull(
            UpdateCheckCacheStore.get(
                context = context,
                currentBuild = 1,
                platform = "mobile",
                releaseType = "release",
                locale = "en",
                sourceKey = "sourceA",
                cacheTtlMs = Long.MAX_VALUE
            )
        )
        assertNull(
            UpdateCheckCacheStore.get(
                context = context,
                currentBuild = 1,
                platform = "mobile",
                releaseType = "beta",
                locale = "en",
                sourceKey = "sourceA",
                cacheTtlMs = Long.MAX_VALUE
            )
        )
        assertNull(
            UpdateCheckCacheStore.get(
                context = context,
                currentBuild = 1,
                platform = "mobile",
                releaseType = "release",
                locale = "en",
                sourceKey = "sourceB",
                cacheTtlMs = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `get returns null for malformed stored payload`() {
        val prefs = context.getSharedPreferences("update_check_cache", Context.MODE_PRIVATE)
        prefs.edit().putString("update_1|mobile|release|en|sourceA", "{bad json").commit()

        val cached = UpdateCheckCacheStore.get(
            context = context,
            currentBuild = 1,
            platform = "mobile",
            releaseType = "release",
            locale = "en",
            sourceKey = "sourceA",
            cacheTtlMs = Long.MAX_VALUE
        )

        assertNull(cached)
    }

    private fun sampleInfo(asset: AppUpdateAsset? = null): AppUpdateInfo =
        AppUpdateInfo(
            hasUpdate = true,
            currentBuild = 1,
            latestBuild = 2,
            latestVersion = "1.0",
            name = "Release",
            changelog = "changes",
            resolvedLocale = "en",
            message = "Update available.",
            asset = asset
        )
}


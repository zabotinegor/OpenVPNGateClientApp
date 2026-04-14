package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatePromptDedupTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("update_prompt_prefs_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `returns false for blank download url`() {
        val update = sampleUpdate(downloadProxyUrl = "")

        val result = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = update,
            keyLastPromptToken = "last_prompt_token"
        )

        assertFalse(result)
    }

    @Test
    fun `returns true then false for same token`() {
        val update = sampleUpdate(assetBuildNumber = 55L, latestBuild = 55L)

        val first = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = update,
            keyLastPromptToken = "last_prompt_token"
        )
        val second = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = update,
            keyLastPromptToken = "last_prompt_token"
        )

        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `falls back to latestBuild when assetBuildNumber is null`() {
        val update = sampleUpdate(assetBuildNumber = null, latestBuild = 77L)

        val first = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = update,
            keyLastPromptToken = "last_prompt_token"
        )
        val second = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = update,
            keyLastPromptToken = "last_prompt_token"
        )

        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `changes token when build changes`() {
        val firstUpdate = sampleUpdate(assetBuildNumber = 55L, latestBuild = 55L)
        val secondUpdate = sampleUpdate(assetBuildNumber = 56L, latestBuild = 56L)

        val first = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = firstUpdate,
            keyLastPromptToken = "last_prompt_token"
        )
        val second = UpdatePromptDedup.shouldShowOnce(
            prefs = prefs,
            update = secondUpdate,
            keyLastPromptToken = "last_prompt_token"
        )

        assertTrue(first)
        assertTrue(second)
    }

    private fun sampleUpdate(
        assetBuildNumber: Long? = 55L,
        latestBuild: Long? = 55L,
        downloadProxyUrl: String = "https://example.com/api/v1/download-assets/1/1"
    ) = MainAvailableUpdate(
        currentBuild = 1L,
        latestBuild = latestBuild,
        versionNumber = "1.0.0",
        name = "Update",
        changelog = "Changes",
        assetName = "app.apk",
        assetPlatform = "mobile",
        assetBuildNumber = assetBuildNumber,
        assetType = "apk",
        assetSizeBytes = 1L,
        assetContentHash = "hash",
        downloadProxyUrl = downloadProxyUrl,
        message = "Update available"
    )
}

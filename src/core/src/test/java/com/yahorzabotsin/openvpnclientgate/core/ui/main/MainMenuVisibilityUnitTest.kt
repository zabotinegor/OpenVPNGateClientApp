package com.yahorzabotsin.openvpnclientgate.core.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuVisibilityUnitTest {

    @Test
    fun `resolveMainMenuVisibility shows update and hides whats new when update exists`() {
        val state = MainUiState(
            whatsNew = MainWhatsNew("1.0.0", "Current", "notes", "<p>notes</p>"),
            availableUpdate = MainAvailableUpdate(
                currentBuild = 1L,
                latestBuild = 2L,
                versionNumber = "1.0.1",
                name = "Update",
                changelog = "changes",
                assetName = "app.apk",
                assetPlatform = "mobile",
                assetBuildNumber = 2L,
                assetType = "apk",
                assetSizeBytes = 1L,
                assetContentHash = "hash",
                downloadProxyUrl = "https://example.com/app.apk",
                message = "Update available"
            )
        )

        val visibility = resolveMainMenuVisibility(state)

        assertTrue(visibility.showUpdate)
        assertFalse(visibility.showWhatsNew)
    }

    @Test
    fun `resolveMainMenuVisibility shows whats new when no update and whats new exists`() {
        val state = MainUiState(
            whatsNew = MainWhatsNew("1.0.0", "Current", "notes", "<p>notes</p>"),
            availableUpdate = null
        )

        val visibility = resolveMainMenuVisibility(state)

        assertFalse(visibility.showUpdate)
        assertTrue(visibility.showWhatsNew)
    }

    @Test
    fun `resolveMainMenuVisibility hides both when update and whats new missing`() {
        val visibility = resolveMainMenuVisibility(MainUiState())

        assertFalse(visibility.showUpdate)
        assertFalse(visibility.showWhatsNew)
    }
}


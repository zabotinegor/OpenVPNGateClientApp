package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainMenuVisibilityComponentTest {

    @Test
    fun `applyMainMenuVisibility toggles nav menu items according to state`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val menu = PopupMenu(context, null).menu
        menu.add(0, R.id.nav_update, 0, "Update")
        menu.add(0, R.id.nav_whats_new, 1, "What's new")

        val updateState = MainUiState(
            whatsNew = MainWhatsNew("1.0.0", "Current", "notes", "<p>notes</p>"),
            availableUpdate = MainAvailableUpdate(
                currentBuild = 1L,
                latestBuild = 2L,
                versionNumber = "1.0.1",
                name = "Update",
                changelog = "changes",
                assetId = 1,
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
        applyMainMenuVisibility(menu, updateState)
        assertTrue(menu.findItem(R.id.nav_update).isVisible)
        assertFalse(menu.findItem(R.id.nav_whats_new).isVisible)

        val whatsNewState = MainUiState(
            whatsNew = MainWhatsNew("1.0.0", "Current", "notes", "<p>notes</p>"),
            availableUpdate = null
        )
        applyMainMenuVisibility(menu, whatsNewState)
        assertFalse(menu.findItem(R.id.nav_update).isVisible)
        assertTrue(menu.findItem(R.id.nav_whats_new).isVisible)

        applyMainMenuVisibility(menu, MainUiState())
        assertFalse(menu.findItem(R.id.nav_update).isVisible)
        assertFalse(menu.findItem(R.id.nav_whats_new).isVisible)
    }
}

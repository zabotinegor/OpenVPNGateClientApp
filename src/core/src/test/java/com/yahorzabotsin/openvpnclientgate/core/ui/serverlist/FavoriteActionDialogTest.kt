package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.app.Activity
import com.yahorzabotsin.openvpnclientgate.core.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SUB-04 (TV D-pad favorites interaction) unit tests.
 *
 * Exercises the testable seams of [FavoriteActionDialog] — the production presentation gate
 * and label resolver used by both [ServerListActivity.showFavoriteMenu] and
 * [CountryServersActivity.showFavoriteMenu] — plus the TV dialog title fallback for server
 * rows. The full themed Activities/dialog cannot be launched here because core unit tests
 * run Robolectric in legacy resources mode, which cannot resolve AppCompat/Material theme
 * resources (same constraint documented in CountryServersActivityFocusTest).
 *
 * The [FavoriteActionDialog.show] lifecycle guard IS testable here: it returns null before
 * touching AlertDialog.Builder when the host Activity is finishing/destroyed, so a plain
 * (non-AppCompat) Robolectric Activity suffices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class FavoriteActionDialogTest {

    // --- resolvePresentation: which affordance a long-press opens (AC1, AC4) ---

    @Test
    fun `tv device with valid target presents the remote-navigable dialog, not a PopupMenu`() {
        assertEquals(
            FavoriteActionDialog.Presentation.TV_DIALOG,
            FavoriteActionDialog.resolvePresentation(isTvDevice = true, canFavorite = true)
        )
    }

    @Test
    fun `touch device with valid target keeps the SUB-02 SUB-03 PopupMenu behavior unchanged`() {
        assertEquals(
            FavoriteActionDialog.Presentation.POPUP_MENU,
            FavoriteActionDialog.resolvePresentation(isTvDevice = false, canFavorite = true)
        )
    }

    @Test
    fun `invalid target shows no affordance on tv - blank country code or server id at most 0`() {
        assertEquals(
            FavoriteActionDialog.Presentation.NONE,
            FavoriteActionDialog.resolvePresentation(isTvDevice = true, canFavorite = false)
        )
    }

    @Test
    fun `invalid target shows no affordance on touch either`() {
        assertEquals(
            FavoriteActionDialog.Presentation.NONE,
            FavoriteActionDialog.resolvePresentation(isTvDevice = false, canFavorite = false)
        )
    }

    // --- actionLabelRes: dialog action reflects current favorite state (AC1) ---

    @Test
    fun `current favorite offers the remove action`() {
        assertEquals(
            R.string.favorites_remove_action,
            FavoriteActionDialog.actionLabelRes(isFavorite = true)
        )
    }

    @Test
    fun `non-favorite offers the add action`() {
        assertEquals(
            R.string.favorites_add_action,
            FavoriteActionDialog.actionLabelRes(isFavorite = false)
        )
    }

    // --- show: lifecycle guard against WindowManager.BadTokenException (async race) ---

    @Test
    fun `show returns null and shows nothing when the activity is finishing`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.finish()

        assertNull(
            FavoriteActionDialog.show(
                activity = activity,
                itemTitle = "Tokyo",
                isFavorite = false,
                onToggle = {}
            )
        )
    }

    @Test
    fun `show returns null and shows nothing when the activity is destroyed`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        controller.destroy()

        assertNull(
            FavoriteActionDialog.show(
                activity = activity,
                itemTitle = "Tokyo",
                isFavorite = false,
                onToggle = {}
            )
        )
    }

    // --- tvFavoriteDialogTitle: server-row dialog title mirrors the row title fallback ---

    @Test
    fun `server dialog title uses trimmed city when present`() {
        assertEquals(
            "Tokyo",
            CountryServersActivity.tvFavoriteDialogTitle(city = " Tokyo ", ip = "1.2.3.4")
        )
    }

    @Test
    fun `server dialog title falls back to ip when city is blank`() {
        assertEquals(
            "1.2.3.4",
            CountryServersActivity.tvFavoriteDialogTitle(city = "  ", ip = "1.2.3.4")
        )
    }
}

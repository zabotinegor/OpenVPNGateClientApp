package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.app.Activity
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.yahorzabotsin.openvpnclientgate.core.R

/**
 * Favorites toggle affordance shared by [ServerListActivity] and [CountryServersActivity].
 *
 * Mobile (touch) uses an anchored [android.widget.PopupMenu] (SUB-02/SUB-03). PopupMenu does
 * not anchor or focus well with D-pad navigation, so TV uses a self-contained, remote-navigable
 * [AlertDialog] instead (SUB-04). The favorite/not-favorite state machine and the ViewModel
 * ToggleFavorite action are identical on both surfaces; only the presentation differs.
 *
 * [resolvePresentation] and [actionLabelRes] are extracted as testable seams (mirrors the
 * TvUtils.applyFocusFirstItem pattern) because the full themed Activity
 * cannot be launched in core unit tests (legacy Robolectric resources mode cannot resolve
 * AppCompat/Material theme resources).
 */
internal object FavoriteActionDialog {

    /** Which affordance a favorites long-press should present, if any. */
    enum class Presentation { NONE, POPUP_MENU, TV_DIALOG }

    /**
     * Presentation gate shared by both list screens.
     *
     * @param canFavorite the per-screen validity guard: non-blank country code on the countries
     * screen, `server.id > 0` on the servers screen (legacy/un-synced servers are not
     * favoritable). Invalid targets show no UI at all — favoriting them would silently
     * do nothing.
     */
    fun resolvePresentation(isTvDevice: Boolean, canFavorite: Boolean): Presentation = when {
        !canFavorite -> Presentation.NONE
        isTvDevice -> Presentation.TV_DIALOG
        else -> Presentation.POPUP_MENU
    }

    /** Action label reflecting the current favorite state (the action to take, not the state). */
    @StringRes
    fun actionLabelRes(isFavorite: Boolean): Int =
        if (isFavorite) R.string.favorites_remove_action else R.string.favorites_add_action

    /**
     * Shows the TV D-pad dialog: title = the focused row's display name, a single action item
     * ("Add to favorites" / "Remove from favorites") and a Cancel button, all remote-navigable.
     * Selecting the action item dismisses the dialog and invokes [onToggle]; BACK/Cancel
     * dismisses without action.
     *
     * Returns the dialog so callers can track it and dismiss it in onDestroy (window-leak
     * guard, mirrors the activePopupMenu pattern from SUB-02/SUB-03).
     *
     * Returns `null` without showing anything when [activity] is finishing or destroyed —
     * favorite-state resolution is asynchronous, so a rapid D-pad long-press can race with
     * Activity teardown and showing a dialog then would throw WindowManager.BadTokenException.
     */
    fun show(
        activity: Activity,
        itemTitle: String?,
        isFavorite: Boolean,
        onToggle: () -> Unit
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) {
            return null
        }
        val builder = AlertDialog.Builder(activity)
        if (!itemTitle.isNullOrBlank()) {
            builder.setTitle(itemTitle)
        }
        return builder
            .setItems(arrayOf(activity.getString(actionLabelRes(isFavorite)))) { _, _ ->
                onToggle()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { dialog -> applyThemedTitleColor(activity, dialog) }
    }

    /**
     * SUB-08 defect fix: on-device verification (pixel-sampled screenshots, both themes) showed
     * `android:windowTitleStyle` in `ThemeOverlay.OpenVPNClientGate.AlertDialog` (values/themes.xml)
     * does not reliably reach this AlertDialog's title text color on this AppCompat version —
     * the title rendered invisible (white-on-light) in light theme despite the window background
     * fix (`android:windowBackground`) working correctly. Setting the color directly on the
     * inflated title view is a guaranteed, styling-only fix with no effect on presentation
     * gate/behavior. `alertTitle` is AppCompat's internal id for the AlertDialog title TextView
     * (`abc_alert_dialog_title_material.xml`); absent only if the platform view id changes,
     * hence the null-safe lookup.
     */
    private fun applyThemedTitleColor(activity: Activity, dialog: AlertDialog) {
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            ?.setTextColor(ContextCompat.getColor(activity, R.color.text_color_primary))
    }
}

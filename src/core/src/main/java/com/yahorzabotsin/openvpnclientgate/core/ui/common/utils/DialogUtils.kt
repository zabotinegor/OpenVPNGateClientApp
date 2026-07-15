package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

import android.content.Context
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.yahorzabotsin.openvpnclientgate.core.R

/**
 * SUB-08 defect fix: shared AlertDialog title-color utility for all app-wide themed dialogs.
 *
 * The app-wide `alertDialogTheme` in values/themes.xml applies to every `AlertDialog.Builder`
 * call in the app for consistent rounded corners. However, `android:windowTitleStyle` in the
 * `ThemeOverlay.OpenVPNClientGate.AlertDialog` does not reliably reach AlertDialog title text
 * color on this AppCompat version — the title renders invisible (white-on-light) in light theme
 * despite the window background fix working correctly.
 *
 * [applyThemedTitleColor] sets the color directly on the inflated title view (AppCompat's
 * internal `alertTitle` id from `abc_alert_dialog_title_material.xml`). This is a guaranteed,
 * styling-only fix with no effect on presentation gate/behavior. Called from every titled
 * AlertDialog in the app to ensure consistent, readable titles across all themes.
 */
internal object DialogUtils {
    /**
     * Apply the themed title color to an AlertDialog.
     *
     * Safe to call on any AlertDialog, even those without titles — the null-safe lookup on
     * the `alertTitle` view will find nothing and return early.
     *
     * @param dialog The AlertDialog to recolor. Must already be shown or in the process of
     * being shown for the title view to be inflated.
     */
    fun applyThemedTitleColor(dialog: AlertDialog) {
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            ?.setTextColor(resolveThemedTitleColor(dialog.context))
    }

    /**
     * Resolve the themed title color using Material's theme-attribute resolution with fallback.
     *
     * Uses MaterialColors.getColor() to resolve android.R.attr.textColorPrimary (the actual
     * theme's primary text color attribute), with a fallback to R.color.text_color_primary
     * if the attribute cannot be resolved. This ensures the dialog uses the theme's intended
     * primary text color instead of a hardcoded resource reference.
     *
     * Testable seam for [applyThemedTitleColor]'s color resolution, split out so the
     * day/night resolved value can be asserted in a unit test without needing the full
     * themed AlertDialog to render.
     */
    @ColorInt
    internal fun resolveThemedTitleColor(context: Context): Int =
        MaterialColors.getColor(
            context,
            android.R.attr.textColorPrimary,
            ContextCompat.getColor(context, R.color.text_color_primary)
        )
}

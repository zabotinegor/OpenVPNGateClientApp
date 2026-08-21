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
 *
 * `86cb88gnw` defect fix: the same theme-attribute indirection failure also leaves the
 * AlertDialog *message* body unreadable (stock system message color, unreadable in at least
 * light theme). [applyThemedMessageColor] mirrors [applyThemedTitleColor] but targets the
 * stock `android.R.id.message` view instead of AppCompat's `alertTitle`.
 *
 * ### Unit test coverage note (`86cb88gnw`)
 *
 * There is deliberately no `DialogUtilsTest` class in this module's test source set. Two
 * testing approaches were attempted for [resolveThemedPrimaryTextColor] and both reproduce the
 * same underlying constraint, one step further removed each time:
 *
 * 1. Asserting [resolveThemedPrimaryTextColor] resolves to the exact `values/colors.xml` /
 *    `values-night/colors.xml` `text_color_primary` hex under day and `+night` qualifiers
 *    throws even on the fallback-only call path — `ContextCompat.getColor(context,
 *    R.color.text_color_primary)`, no AppCompat/Material theme-attribute indirection at all:
 *    ```
 *    android.content.res.Resources$NotFoundException: Resource ID #0x7f0603f2
 *        at ...ShadowLegacyAssetManager.getResName(...)
 *        at androidx.core.content.ContextCompat.getColor(ContextCompat.java:539)
 *        at ...DialogUtils.resolveThemedPrimaryTextColor(DialogUtils.kt)
 *    ```
 * 2. Sidestepping color resolution entirely and asserting only the documented null-safety
 *    contract ("safe to call on any dialog without a message/title — the view lookup finds
 *    nothing and returns early") on an unshown, message-less `AlertDialog` fails even earlier,
 *    inside `AlertDialog.Builder`'s constructor itself — before any dialog instance exists to
 *    pass to [applyThemedMessageColor]:
 *    ```
 *    java.lang.NullPointerException: Cannot read field "packageName" because "resName" is null
 *        at org.robolectric.res.StyleResolver.getAttrValue(StyleResolver.java:28)
 *        at androidx.appcompat.app.AlertDialog.resolveDialogTheme(AlertDialog.java:115)
 *        at androidx.appcompat.app.AlertDialog$Builder.<init>(AlertDialog.java:312)
 *    ```
 *
 * Both confirm core unit tests run Robolectric in legacy resources mode, which cannot resolve
 * this module's own resource ids or construct a themed `AlertDialog` at all — consistent with
 * the constraint already documented for the title seam (see the trailing comment in
 * `FavoriteActionDialogTest`). A class with zero `@Test` methods is silently skipped by
 * Gradle's test detection rather than failing, which is a worse trap than not having the file
 * at all — hence no placeholder test class. Coverage for the actual defect (unreadable dialog
 * message text) rests on on-device screenshot verification in both themes.
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
            ?.setTextColor(resolveThemedPrimaryTextColor(dialog.context))
    }

    /**
     * Apply the themed message color to an AlertDialog.
     *
     * Safe to call on any AlertDialog, even those without a message body — the null-safe
     * lookup on the `android.R.id.message` view will find nothing and return early.
     *
     * @param dialog The AlertDialog to recolor. Must already be shown or in the process of
     * being shown for the message view to be inflated.
     */
    fun applyThemedMessageColor(dialog: AlertDialog) {
        dialog.findViewById<TextView>(android.R.id.message)
            ?.setTextColor(resolveThemedPrimaryTextColor(dialog.context))
    }

    /**
     * Resolve the themed primary text color using Material's theme-attribute resolution with
     * fallback.
     *
     * Uses MaterialColors.getColor() to resolve android.R.attr.textColorPrimary (the actual
     * theme's primary text color attribute), with a fallback to R.color.text_color_primary
     * if the attribute cannot be resolved. This ensures the dialog uses the theme's intended
     * primary text color instead of a hardcoded resource reference.
     *
     * Shared testable seam for both [applyThemedTitleColor] and [applyThemedMessageColor] —
     * title and message currently resolve to the identical color, so this is one function
     * rather than two byte-identical ones. If a future change needs the message to diverge
     * (e.g. to `android.R.attr.textColorSecondary`), split it back out then, not before.
     *
     * Split out so the day/night resolved value can be asserted in a unit test without
     * needing the full themed AlertDialog to render.
     */
    @ColorInt
    internal fun resolveThemedPrimaryTextColor(context: Context): Int =
        MaterialColors.getColor(
            context,
            android.R.attr.textColorPrimary,
            ContextCompat.getColor(context, R.color.text_color_primary)
        )
}

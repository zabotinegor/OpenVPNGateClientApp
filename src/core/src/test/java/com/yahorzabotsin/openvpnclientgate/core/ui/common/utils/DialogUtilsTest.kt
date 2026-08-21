package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

/**
 * `86cb88gnw` defect fix: coverage note for [DialogUtils.applyThemedMessageColor] /
 * [DialogUtils.resolveThemedMessageColor], the message-color counterpart of the sibling
 * SUB-08 [DialogUtils.applyThemedTitleColor] / [DialogUtils.resolveThemedTitleColor] seam (see
 * the trailing comment in `FavoriteActionDialogTest` for that seam's original writeup of this
 * module's Robolectric constraint).
 *
 * Two testing approaches were attempted here and both reproduce the same underlying constraint,
 * one step further removed each time:
 *
 * 1. Asserting [DialogUtils.resolveThemedMessageColor] resolves to the exact
 *    `values/colors.xml` / `values-night/colors.xml` `text_color_primary` hex under day and
 *    `+night` qualifiers (the approach already tried, and already documented as failing, for
 *    the title seam) throws even on the fallback-only call path — `ContextCompat.getColor(
 *    context, R.color.text_color_primary)`, no AppCompat/Material theme-attribute indirection
 *    at all:
 *    ```
 *    android.content.res.Resources$NotFoundException: Resource ID #0x7f0603f2
 *        at ...ShadowLegacyAssetManager.getResName(...)
 *        at androidx.core.content.ContextCompat.getColor(ContextCompat.java:539)
 *        at ...DialogUtils.resolveThemedMessageColor(DialogUtils.kt:93)
 *    ```
 * 2. Sidestepping color resolution entirely and asserting only the documented null-safety
 *    contract ("safe to call on any dialog without a message/title — the view lookup finds
 *    nothing and returns early") on an unshown, message-less `AlertDialog` fails even earlier,
 *    inside `AlertDialog.Builder`'s constructor itself — before any dialog instance exists to
 *    pass to [DialogUtils.applyThemedMessageColor]:
 *    ```
 *    java.lang.NullPointerException: Cannot read field "packageName" because "resName" is null
 *        at org.robolectric.res.StyleResolver.getAttrValue(StyleResolver.java:28)
 *        at androidx.appcompat.app.AlertDialog.resolveDialogTheme(AlertDialog.java:115)
 *        at androidx.appcompat.app.AlertDialog$Builder.<init>(AlertDialog.java:312)
 *    ```
 *
 * Both confirm core unit tests run Robolectric in legacy resources mode, which cannot resolve
 * this module's own resource ids or construct a themed `AlertDialog` at all — consistent with
 * (and now proven one layer deeper than) the constraint already documented for the title seam.
 * [DialogUtils.resolveThemedMessageColor] is kept as a production seam (harmless,
 * self-documenting, mirrors [DialogUtils.resolveThemedTitleColor]) for if/when this module's
 * test resource/theme setup is fixed, but no assertion-based unit test exists for it here,
 * consistent with the sibling title seam. Coverage for the actual defect (unreadable dialog
 * message text) rests on on-device screenshot verification in both themes, planned as a
 * separate Manual QA step per this story's test-scenario notes.
 */
class DialogUtilsTest

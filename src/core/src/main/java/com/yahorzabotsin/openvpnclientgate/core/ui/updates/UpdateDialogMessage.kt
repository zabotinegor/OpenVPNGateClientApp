package com.yahorzabotsin.openvpnclientgate.core.ui.updates

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.R

internal fun buildUpdateDialogMessage(
    context: Context,
    latestVersion: String?,
    backendMessage: String?
): String {
    return buildUpdateDialogMessage(
        localizedMessage = context.getString(R.string.update_available_message),
        latestVersion = latestVersion,
        latestVersionFormatter = { version ->
            context.getString(R.string.update_latest_version_format, version)
        },
        backendMessage = backendMessage
    )
}

internal fun buildUpdateDialogMessage(
    localizedMessage: String,
    latestVersion: String?,
    latestVersionFormatter: (String) -> String,
    backendMessage: String?
): String {
    // Backend message may come in another language; UI always uses current app locale.
    @Suppress("UNUSED_VARIABLE")
    val ignoredBackendMessage = backendMessage
    val version = latestVersion?.takeIf { it.isNotBlank() } ?: return localizedMessage
    return buildString {
        append(localizedMessage)
        append("\n")
        append(latestVersionFormatter(version))
    }
}

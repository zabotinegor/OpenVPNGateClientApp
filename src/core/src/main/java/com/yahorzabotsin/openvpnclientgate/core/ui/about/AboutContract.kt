package com.yahorzabotsin.openvpnclientgate.core.ui.about

import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfo
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinks
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText

data class AboutUiState(
    val info: AboutInfo = AboutInfo(
        versionName = "",
        versionCode = 0,
        packageName = "",
        engineName = "",
        engineLicense = "",
        copyrightOwner = "",
        year = 0
    ),
    val links: AboutLinks = AboutLinks(
        website = "",
        email = "",
        telegram = "",
        github = "",
        githubEngine = "",
        androidStore = "",
        privacyPolicy = "",
        termsOfUse = "",
        gplv2 = "",
        icsGithub = ""
    ),
    val isExportingLogs: Boolean = false,
    val lastActionAtMs: Long = 0L
)

enum class AboutRowId {
    WEBSITE,
    EMAIL,
    TELEGRAM,
    GITHUB,
    GITHUB_ENGINE,
    STORE,
    PRIVACY,
    TERMS,
    LICENSE,
    ICS_GITHUB,
    LOGS,
    CHECK_UPDATES
}

sealed interface AboutAction {
    data class RowClick(val id: AboutRowId) : AboutAction
    data class RowLongClick(val id: AboutRowId) : AboutAction
}

sealed interface AboutEffect {
    data class OpenUrl(val url: String) : AboutEffect
    data class OpenEmail(val email: String) : AboutEffect
    data class OpenStore(val webUrl: String) : AboutEffect
    data class CopyToClipboard(val labelResId: Int, val text: String) : AboutEffect
    data class ShowToast(val text: UiText, val duration: ToastDuration = ToastDuration.SHORT) : AboutEffect
    data class ShareLogArchive(val filePath: String) : AboutEffect
    data class PromptUpdate(val update: com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInfo) : AboutEffect
}

enum class ToastDuration {
    SHORT,
    LONG
}

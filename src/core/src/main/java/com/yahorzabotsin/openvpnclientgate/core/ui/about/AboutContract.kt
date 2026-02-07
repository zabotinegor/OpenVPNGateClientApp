package com.yahorzabotsin.openvpnclientgate.core.ui.about

import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfo
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinks

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
        googlePlay = "",
        privacyPolicy = "",
        termsOfUse = "",
        gplv2 = "",
        icsGithub = ""
    ),
    val isExportingLogs: Boolean = false
)

sealed interface AboutCommand {
    data class OpenUrl(val url: String) : AboutCommand
    data class OpenEmail(val email: String) : AboutCommand
    data class OpenPlay(val webUrl: String) : AboutCommand
    data class CopyToClipboard(val labelResId: Int, val text: String) : AboutCommand
    data class ShowToast(val text: UiText, val duration: ToastDuration = ToastDuration.SHORT) : AboutCommand
    data class ShareLogArchive(val filePath: String) : AboutCommand
}

enum class ToastDuration {
    SHORT,
    LONG
}

sealed interface UiText {
    data class Res(val resId: Int, val args: List<Any> = emptyList()) : UiText
    data class Plain(val value: String) : UiText
}

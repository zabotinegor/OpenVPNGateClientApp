package com.yahorzabotsin.openvpnclientgate.core.about

data class AboutLinks(
    val website: String,
    val email: String,
    val telegram: String,
    val github: String,
    val githubEngine: String,
    val androidStore: String,
    val privacyPolicy: String,
    val termsOfUse: String,
    val gplv2: String,
    val icsGithub: String
)

interface AboutLinksProvider {
    fun get(): AboutLinks
}

class DefaultAboutLinksProvider : AboutLinksProvider {
    override fun get(): AboutLinks = AboutLinks(
        website = AboutMeta.WEBSITE,
        email = AboutMeta.EMAIL,
        telegram = AboutMeta.TELEGRAM,
        github = AboutMeta.GITHUB,
        githubEngine = AboutMeta.GITHUB_ENGINE,
        androidStore = AboutMeta.ANDROID_STORE,
        privacyPolicy = AboutMeta.PRIVACY_POLICY,
        termsOfUse = AboutMeta.TERMS_OF_USE,
        gplv2 = AboutMeta.GPLV2_URL,
        icsGithub = AboutMeta.ICS_OPENVPN_GITHUB
    )
}

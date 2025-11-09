package com.yahorzabotsin.openvpnclient.core.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.about.AboutMeta
import com.yahorzabotsin.openvpnclient.core.databinding.ContentAboutBinding

class AboutActivity : BaseTemplateActivity(R.string.menu_about) {
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        ContentAboutBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = templateBinding.contentContainer

        val versionView = root.findViewById<TextView>(R.id.tv_version)
        val packageView = root.findViewById<TextView>(R.id.tv_package)
        val engineView = root.findViewById<TextView>(R.id.tv_engine)
        val copyrightView = root.findViewById<TextView>(R.id.tv_copyright)

        val websiteRow = root.findViewById<View>(R.id.row_website)
        val emailRow = root.findViewById<View>(R.id.row_email)
        val telegramRow = root.findViewById<View>(R.id.row_telegram)
        val githubRow = root.findViewById<View>(R.id.row_github)
        val githubEngineRow = root.findViewById<View>(R.id.row_github_engine)
        val playRow = root.findViewById<View>(R.id.row_play)
        val privacyRow = root.findViewById<View>(R.id.row_privacy)
        val termsRow = root.findViewById<View>(R.id.row_terms)
        val licenseRow = root.findViewById<View>(R.id.row_license)

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName ?: ""
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()

        versionView.text = getString(R.string.about_version_format, versionName, versionCode)
        packageView.text = getString(R.string.about_package_format, packageName)
        engineView.text = getString(R.string.about_engine_format, AboutMeta.ENGINE_NAME, AboutMeta.ENGINE_LICENSE)

        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        copyrightView.text = getString(R.string.about_copyright_format, year, AboutMeta.COPYRIGHT_OWNER)

        setupRow(websiteRow, AboutMeta.WEBSITE) { openUrl(AboutMeta.WEBSITE) }
        setupRow(emailRow, AboutMeta.EMAIL) { openEmail(AboutMeta.EMAIL) }
        // Show email address inline for clarity
        (emailRow as? TextView)?.let { tv ->
            if (AboutMeta.EMAIL.isNotBlank()) {
                tv.text = getString(R.string.about_email) + ": " + AboutMeta.EMAIL
            }
        }
        setupRow(telegramRow, AboutMeta.TELEGRAM) { openUrl(AboutMeta.TELEGRAM) }
        setupRow(githubRow, AboutMeta.GITHUB) { openUrl(AboutMeta.GITHUB) }
        setupRow(githubEngineRow, AboutMeta.GITHUB_ENGINE) { openUrl(AboutMeta.GITHUB_ENGINE) }
        setupRow(playRow, AboutMeta.GOOGLE_PLAY) { openPlay(AboutMeta.GOOGLE_PLAY) }
        setupRow(privacyRow, AboutMeta.PRIVACY_POLICY) { openUrl(AboutMeta.PRIVACY_POLICY) }
        setupRow(termsRow, AboutMeta.TERMS_OF_USE) { openUrl(AboutMeta.TERMS_OF_USE) }
        licenseRow.setOnClickListener {
            // Open engine repo and GPL text in browser tabs
            openUrl(AboutMeta.ENGINE_URL)
            openUrl(AboutMeta.GPLV2_URL)
        }
    }

    private fun setupRow(view: View, value: String, onClick: () -> Unit) {
        val hasValue = value.isNotBlank()
        view.isVisible = hasValue
        if (hasValue) view.setOnClickListener { onClick() }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun openEmail(email: String) {
        if (email.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
        }
        startActivity(intent)
    }

    private fun openPlay(webUrl: String) {
        if (webUrl.isBlank()) return
        val marketUri = Uri.parse("market://details?id=$packageName")
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri)
        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            openUrl(webUrl)
        }
    }
}

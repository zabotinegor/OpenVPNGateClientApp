package com.yahorzabotsin.openvpnclient.core.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.about.AboutMeta
import com.yahorzabotsin.openvpnclient.core.databinding.ContentAboutBinding

class AboutActivity : BaseTemplateActivity(R.string.menu_about) {
    private var lastActionAt: Long = 0
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
        val icsGithubRow = root.findViewById<View>(R.id.row_ics_github)

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName ?: ""
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()

        versionView.text = getString(R.string.about_version_format, versionName, versionCode)
        packageView.text = getString(R.string.about_package_format, packageName)
        engineView.text = getString(R.string.about_engine_format, AboutMeta.ENGINE_NAME, AboutMeta.ENGINE_LICENSE)

        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        copyrightView.text = getString(R.string.about_copyright_format, year, AboutMeta.COPYRIGHT_OWNER)

        setupRow(websiteRow, AboutMeta.WEBSITE, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.WEBSITE) }
        setupRow(emailRow, AboutMeta.EMAIL, copyLabel = getString(R.string.copy_label_email)) { openEmail(AboutMeta.EMAIL) }
        (emailRow as? TextView)?.let { tv ->
            if (AboutMeta.EMAIL.isNotBlank()) {
                tv.text = getString(R.string.about_email) + ": " + AboutMeta.EMAIL
            }
        }
        setupRow(telegramRow, AboutMeta.TELEGRAM, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.TELEGRAM) }
        setupRow(githubRow, AboutMeta.GITHUB, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.GITHUB) }
        setupRow(githubEngineRow, AboutMeta.GITHUB_ENGINE, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.GITHUB_ENGINE) }
        setupRow(playRow, AboutMeta.GOOGLE_PLAY, copyLabel = getString(R.string.copy_label_link)) { openPlay(AboutMeta.GOOGLE_PLAY) }
        setupRow(privacyRow, AboutMeta.PRIVACY_POLICY, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.PRIVACY_POLICY) }
        setupRow(termsRow, AboutMeta.TERMS_OF_USE, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.TERMS_OF_USE) }
        licenseRow.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastActionAt < 1200) return@setOnClickListener
            lastActionAt = now
            openUrl(AboutMeta.GPLV2_URL)
        }

        setupRow(icsGithubRow, AboutMeta.ICS_OPENVPN_GITHUB, copyLabel = getString(R.string.copy_label_link)) {
            openUrl(AboutMeta.ICS_OPENVPN_GITHUB)
        }
    }

    private fun setupRow(view: View, value: String, copyLabel: String? = null, onClick: () -> Unit) {
        val hasValue = value.isNotBlank()
        view.isVisible = hasValue
        if (hasValue) {
            view.setOnClickListener { onClick() }
            view.setOnLongClickListener {
                val textToCopy = if (view.id == R.id.row_email) value else value
                copyToClipboard(copyLabel ?: getString(R.string.copy_label_link), textToCopy)
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)
        val isHttp = uri.scheme == "http" || uri.scheme == "https"
        val browse = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

        val exported = packageManager
            .queryIntentActivities(browse, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }
            .filter { it.exported }

        val isTv = getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        try {
            when {
                isTv && isHttp -> openInWebView(url)
                exported.isEmpty() && isHttp -> openInWebView(url)
                exported.isEmpty() -> startActivity(browse)
                exported.size == 1 -> {
                    browse.setPackage(exported[0].packageName)
                    startActivity(browse)
                }
                else -> startActivity(Intent.createChooser(browse, getString(R.string.intent_open_with)))
            }
        } catch (_: SecurityException) {
            if (isHttp) openInWebView(url) else startActivity(Intent.createChooser(browse, getString(R.string.intent_open_with)))
        } catch (_: ActivityNotFoundException) {
            if (isHttp) openInWebView(url)
        }
    }

    private fun openInWebView(url: String) {
        val wv = Intent(this, WebViewActivity::class.java)
        wv.putExtra(WebViewActivity.EXTRA_URL, url)
        startActivity(wv)
    }

    private fun openEmail(email: String) {
        if (email.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:$email") }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.intent_send_email)))
        } catch (_: ActivityNotFoundException) { }
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

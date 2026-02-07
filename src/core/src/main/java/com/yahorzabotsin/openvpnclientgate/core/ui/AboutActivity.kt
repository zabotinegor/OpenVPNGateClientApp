package com.yahorzabotsin.openvpnclientgate.core.ui

import android.content.ActivityNotFoundException
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentAboutBinding
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutCommand
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutUiState
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutViewModel
import com.yahorzabotsin.openvpnclientgate.core.ui.about.ToastDuration
import com.yahorzabotsin.openvpnclientgate.core.ui.about.UiText
import kotlinx.coroutines.launch
import java.io.File
import org.koin.androidx.viewmodel.ext.android.viewModel

class AboutActivity : BaseTemplateActivity(R.string.menu_about) {
    private lateinit var bindingContent: ContentAboutBinding
    private val viewModel: AboutViewModel by viewModel()
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        bindingContent = ContentAboutBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindEvents()
        observeViewModel()
    }

    private fun bindEvents() {
        bindingContent.rowWebsite.setOnClickListener { viewModel.onWebsiteClick() }
        bindingContent.rowEmail.setOnClickListener { viewModel.onEmailClick() }
        bindingContent.rowTelegram.setOnClickListener { viewModel.onTelegramClick() }
        bindingContent.rowGithub.setOnClickListener { viewModel.onGithubClick() }
        bindingContent.rowGithubEngine.setOnClickListener { viewModel.onGithubEngineClick() }
        bindingContent.rowPlay.setOnClickListener { viewModel.onPlayClick() }
        bindingContent.rowPrivacy.setOnClickListener { viewModel.onPrivacyClick() }
        bindingContent.rowTerms.setOnClickListener { viewModel.onTermsClick() }
        bindingContent.rowLicense.setOnClickListener { viewModel.onLicenseClick() }
        bindingContent.rowIcsGithub.setOnClickListener { viewModel.onIcsGithubClick() }
        bindingContent.rowLogs.setOnClickListener { viewModel.onLogsClick() }

        bindingContent.rowWebsite.setOnLongClickListener { viewModel.onWebsiteLongClick(); true }
        bindingContent.rowEmail.setOnLongClickListener { viewModel.onEmailLongClick(); true }
        bindingContent.rowTelegram.setOnLongClickListener { viewModel.onTelegramLongClick(); true }
        bindingContent.rowGithub.setOnLongClickListener { viewModel.onGithubLongClick(); true }
        bindingContent.rowGithubEngine.setOnLongClickListener { viewModel.onGithubEngineLongClick(); true }
        bindingContent.rowPlay.setOnLongClickListener { viewModel.onPlayLongClick(); true }
        bindingContent.rowPrivacy.setOnLongClickListener { viewModel.onPrivacyLongClick(); true }
        bindingContent.rowTerms.setOnLongClickListener { viewModel.onTermsLongClick(); true }
        bindingContent.rowLicense.setOnLongClickListener { viewModel.onLicenseLongClick(); true }
        bindingContent.rowIcsGithub.setOnLongClickListener { viewModel.onIcsGithubLongClick(); true }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.commands.collect { handleCommand(it) }
                }
            }
        }
    }

    private fun render(state: AboutUiState) {
        val info = state.info
        val links = state.links

        bindingContent.tvVersion.text = getString(R.string.about_version_format, info.versionName, info.versionCode)
        bindingContent.tvPackage.text = getString(R.string.about_package_format, info.packageName)
        bindingContent.tvEngine.text = getString(R.string.about_engine_format, info.engineName, info.engineLicense)
        bindingContent.tvCopyright.text =
            getString(R.string.about_copyright_format, info.year, info.copyrightOwner)

        bindingContent.rowWebsite.isVisible = links.website.isNotBlank()
        bindingContent.rowEmail.isVisible = links.email.isNotBlank()
        bindingContent.rowTelegram.isVisible = links.telegram.isNotBlank()
        bindingContent.rowGithub.isVisible = links.github.isNotBlank()
        bindingContent.rowGithubEngine.isVisible = links.githubEngine.isNotBlank()
        bindingContent.rowPlay.isVisible = links.googlePlay.isNotBlank()
        bindingContent.rowPrivacy.isVisible = links.privacyPolicy.isNotBlank()
        bindingContent.rowTerms.isVisible = links.termsOfUse.isNotBlank()
        bindingContent.rowLicense.isVisible = links.gplv2.isNotBlank()
        bindingContent.rowIcsGithub.isVisible = links.icsGithub.isNotBlank()

        if (links.email.isNotBlank()) {
            bindingContent.rowEmail.text = getString(R.string.about_email) + ": " + links.email
        }
    }

    private fun handleCommand(command: AboutCommand) {
        when (command) {
            is AboutCommand.OpenUrl -> openUrl(command.url)
            is AboutCommand.OpenEmail -> openEmail(command.email)
            is AboutCommand.OpenPlay -> openPlay(command.webUrl)
            is AboutCommand.CopyToClipboard -> copyToClipboard(getString(command.labelResId), command.text)
            is AboutCommand.ShowToast -> showToast(command.text, command.duration)
            is AboutCommand.ShareLogArchive -> shareLogArchive(File(command.filePath))
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    private fun showToast(text: UiText, duration: ToastDuration) {
        val resolved = when (text) {
            is UiText.Plain -> text.value
            is UiText.Res -> {
                if (text.args.isEmpty()) getString(text.resId)
                else getString(text.resId, *text.args.toTypedArray())
            }
        }
        val toastDuration = when (duration) {
            ToastDuration.SHORT -> Toast.LENGTH_SHORT
            ToastDuration.LONG -> Toast.LENGTH_LONG
        }
        Toast.makeText(this, resolved, toastDuration).show()
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
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.email_app_not_found), Toast.LENGTH_SHORT).show()
        }
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

    private fun shareLogArchive(file: File) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.about_share_logs)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.about_share_not_available), Toast.LENGTH_SHORT).show()
        }
    }
}


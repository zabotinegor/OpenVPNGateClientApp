package com.yahorzabotsin.openvpnclientgate.core.ui.about

import android.content.ActivityNotFoundException
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentAboutBinding
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.WebViewActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.MarkdownRenderer
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.updates.buildUpdateDialogMessage
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInstallResult
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInstaller
import com.yahorzabotsin.openvpnclientgate.core.updates.UpdateInstallProgressDialog
import kotlinx.coroutines.launch
import java.io.File
import android.provider.Settings
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AboutActivity : AppCompatActivity() {
    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var bindingContent: ContentAboutBinding
    private val viewModel: AboutViewModel by viewModel()
    private val appUpdateInstaller: AppUpdateInstaller by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_about, null)
        bindingContent = ContentAboutBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
        bindEvents()
        observeViewModel()
    }

    private fun bindEvents() {
        bindingContent.rowWebsite.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.WEBSITE)) }
        bindingContent.rowEmail.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.EMAIL)) }
        bindingContent.rowTelegram.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.TELEGRAM)) }
        bindingContent.rowGithub.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.GITHUB)) }
        bindingContent.rowGithubEngine.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.GITHUB_ENGINE)) }
        bindingContent.rowPlay.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.STORE)) }
        bindingContent.rowPrivacy.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.PRIVACY)) }
        bindingContent.rowTerms.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.TERMS)) }
        bindingContent.rowLicense.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.LICENSE)) }
        bindingContent.rowIcsGithub.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.ICS_GITHUB)) }
        bindingContent.rowLogs.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.LOGS)) }
        bindingContent.rowCheckUpdates.setOnClickListener { viewModel.onAction(AboutAction.RowClick(AboutRowId.CHECK_UPDATES)) }

        bindingContent.rowWebsite.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.WEBSITE)); true
        }
        bindingContent.rowEmail.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.EMAIL)); true
        }
        bindingContent.rowTelegram.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.TELEGRAM)); true
        }
        bindingContent.rowGithub.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.GITHUB)); true
        }
        bindingContent.rowGithubEngine.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.GITHUB_ENGINE)); true
        }
        bindingContent.rowPlay.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.STORE)); true
        }
        bindingContent.rowPrivacy.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.PRIVACY)); true
        }
        bindingContent.rowTerms.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.TERMS)); true
        }
        bindingContent.rowLicense.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.LICENSE)); true
        }
        bindingContent.rowIcsGithub.setOnLongClickListener {
            viewModel.onAction(AboutAction.RowLongClick(AboutRowId.ICS_GITHUB)); true
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.effects.collect { handleEffect(it) }
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
        bindingContent.rowPlay.isVisible = links.androidStore.isNotBlank()
        bindingContent.rowPrivacy.isVisible = links.privacyPolicy.isNotBlank()
        bindingContent.rowTerms.isVisible = links.termsOfUse.isNotBlank()
        bindingContent.rowLicense.isVisible = links.gplv2.isNotBlank()
        bindingContent.rowIcsGithub.isVisible = links.icsGithub.isNotBlank()

        if (links.email.isNotBlank()) {
            bindingContent.rowEmail.text = getString(R.string.about_email) + ": " + links.email
        }
    }

    private fun handleEffect(effect: AboutEffect) {
        when (effect) {
            is AboutEffect.OpenUrl -> openUrl(effect.url)
            is AboutEffect.OpenEmail -> openEmail(effect.email)
            is AboutEffect.OpenStore -> openStore(effect.webUrl)
            is AboutEffect.CopyToClipboard -> copyToClipboard(getString(effect.labelResId), effect.text)
            is AboutEffect.ShowToast -> showToast(effect.text, effect.duration)
            is AboutEffect.ShareLogArchive -> shareLogArchive(File(effect.filePath))
            is AboutEffect.PromptUpdate -> showUpdateDialog(effect.update)
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

    private fun openStore(webUrl: String) {
        if (webUrl.isBlank()) return
        openUrl(webUrl)
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

    private fun showUpdateDialog(update: com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInfo) {
        val message = buildUpdateDialogMessage(this, update.latestVersion, update.message)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_update) { _, _ -> startUpdateInstall(update) }
            .setNegativeButton(android.R.string.cancel, null)
        if (update.changelog.isNotBlank()) {
            dialog.setNeutralButton(R.string.update_whats_new) { _, _ ->
                val intent = Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_TITLE, getString(R.string.update_whats_new))
                    putExtra(WebViewActivity.EXTRA_HTML, MarkdownRenderer.renderDocument(update.changelog))
                }
                startActivity(intent)
            }
        }
        dialog.show()
    }

    private fun startUpdateInstall(update: com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInfo) {
        lifecycleScope.launch {
            val progressDialog = UpdateInstallProgressDialog(this@AboutActivity)
            progressDialog.show()
            try {
                when (val result = appUpdateInstaller.start(update) { progress ->
                    progressDialog.update(progress)
                }) {
                    AppUpdateInstallResult.Started ->
                        Toast.makeText(this@AboutActivity, getString(R.string.update_install_started), Toast.LENGTH_SHORT).show()
                    AppUpdateInstallResult.MissingInstallPermission -> {
                        Toast.makeText(this@AboutActivity, getString(R.string.update_install_permission_needed), Toast.LENGTH_LONG).show()
                        openUnknownSourcesSettings()
                    }
                    is AppUpdateInstallResult.Failure ->
                        Toast.makeText(
                            this@AboutActivity,
                            getString(R.string.update_install_failed_format, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                }
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    private fun openUnknownSourcesSettings() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}



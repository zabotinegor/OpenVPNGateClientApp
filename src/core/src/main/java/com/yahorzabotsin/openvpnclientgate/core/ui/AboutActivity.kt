package com.yahorzabotsin.openvpnclientgate.core.ui

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
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.about.AboutMeta
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentAboutBinding
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AboutActivity : BaseTemplateActivity(R.string.menu_about) {
    private var lastActionAt: Long = 0
    private var isExportingLogs = false
    companion object {
        private const val CLICK_DEBOUNCE_MS = 500L
    }
    private lateinit var bindingContent: ContentAboutBinding
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        bindingContent = ContentAboutBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val versionView = bindingContent.tvVersion
        val packageView = bindingContent.tvPackage
        val engineView = bindingContent.tvEngine
        val copyrightView = bindingContent.tvCopyright

        val websiteRow = bindingContent.rowWebsite
        val emailRow = bindingContent.rowEmail
        val telegramRow = bindingContent.rowTelegram
        val githubRow = bindingContent.rowGithub
        val githubEngineRow = bindingContent.rowGithubEngine
        val playRow = bindingContent.rowPlay
        val privacyRow = bindingContent.rowPrivacy
        val termsRow = bindingContent.rowTerms
        val licenseRow = bindingContent.rowLicense
        val icsGithubRow = bindingContent.rowIcsGithub
        val logsRow = bindingContent.rowLogs

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName ?: ""
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()

        versionView.text = getString(R.string.about_version_format, versionName, versionCode)
        packageView.text = getString(R.string.about_package_format, packageName)
        engineView.text = getString(R.string.about_engine_format, AboutMeta.ENGINE_NAME, AboutMeta.ENGINE_LICENSE)

        val year = java.time.Year.now().value
        copyrightView.text = getString(R.string.about_copyright_format, year, AboutMeta.COPYRIGHT_OWNER)

        setupRow(websiteRow, AboutMeta.WEBSITE, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.WEBSITE) }
        setupRow(
            emailRow,
            AboutMeta.EMAIL,
            copyLabel = getString(R.string.copy_label_email),
            onClick = { openEmail(AboutMeta.EMAIL) },
            setText = { tv, v -> tv.text = getString(R.string.about_email) + ": " + v }
        )
        setupRow(telegramRow, AboutMeta.TELEGRAM, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.TELEGRAM) }
        setupRow(githubRow, AboutMeta.GITHUB, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.GITHUB) }
        setupRow(githubEngineRow, AboutMeta.GITHUB_ENGINE, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.GITHUB_ENGINE) }
        setupRow(playRow, AboutMeta.GOOGLE_PLAY, copyLabel = getString(R.string.copy_label_link)) { openPlay(AboutMeta.GOOGLE_PLAY) }
        setupRow(privacyRow, AboutMeta.PRIVACY_POLICY, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.PRIVACY_POLICY) }
        setupRow(termsRow, AboutMeta.TERMS_OF_USE, copyLabel = getString(R.string.copy_label_link)) { openUrl(AboutMeta.TERMS_OF_USE) }
        setupRow(licenseRow, AboutMeta.GPLV2_URL, copyLabel = getString(R.string.copy_label_link)) {
            openUrl(AboutMeta.GPLV2_URL)
        }

        setupRow(icsGithubRow, AboutMeta.ICS_OPENVPN_GITHUB, copyLabel = getString(R.string.copy_label_link)) {
            openUrl(AboutMeta.ICS_OPENVPN_GITHUB)
        }

        logsRow.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastActionAt < CLICK_DEBOUNCE_MS) return@setOnClickListener
            lastActionAt = now
            exportLogcatArchive()
        }
    }

    private fun setupRow(
        view: View,
        value: String,
        copyLabel: String? = null,
        setText: ((TextView, String) -> Unit)? = null,
        onClick: () -> Unit
    ) {
        val hasValue = value.isNotBlank()
        view.isVisible = hasValue
        if (hasValue) {
            if (view is TextView) {
                setText?.invoke(view, value)
            }
            view.setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastActionAt < CLICK_DEBOUNCE_MS) return@setOnClickListener
                lastActionAt = now
                onClick()
            }
            view.setOnLongClickListener {
                val textToCopy = value
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

    private fun exportLogcatArchive() {
        if (isExportingLogs) return
        isExportingLogs = true
        Toast.makeText(this, getString(R.string.about_logs_export_started), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputDir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
            if (!outputDir.exists()) outputDir.mkdirs()

            val logFile = File(outputDir, "logcat_${timestamp}.txt")
            val zipFile = File(outputDir, "logcat_${timestamp}.zip")

            val uid = applicationInfo.uid
            val tagPrefix = LogTags.APP
            val attempts = listOf(
                LogcatAttempt(withSince = true, useUid = true),
                LogcatAttempt(withSince = true, useUid = false),
                LogcatAttempt(withSince = false, useUid = true),
                LogcatAttempt(withSince = false, useUid = false)
            )
            var ok = false
            var failureReason = "unknown"
            for (attempt in attempts) {
                val result = runLogcatToFile(logFile, attempt, uid, tagPrefix)
                if (result.success) {
                    ok = true
                    break
                }
                failureReason = result.reason
            }

            val resultPath = if (ok && logFile.length() > 0) {
                try {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        File(logFile.name).name.also { entryName ->
                            zos.putNextEntry(ZipEntry(entryName))
                            logFile.inputStream().use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                    logFile.delete()
                    zipFile.absolutePath
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }

            withContext(Dispatchers.Main) {
                isExportingLogs = false
                if (resultPath.isNotBlank()) {
                    shareLogArchive(zipFile)
                    Toast.makeText(
                        this@AboutActivity,
                        getString(R.string.about_logs_export_done_format, resultPath),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val msg = getString(R.string.about_logs_export_failed_format, failureReason)
                    Toast.makeText(this@AboutActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class LogcatAttempt(
        val withSince: Boolean,
        val useUid: Boolean
    )

    private data class LogcatResult(
        val success: Boolean,
        val reason: String
    )

    private fun runLogcatToFile(target: File, attempt: LogcatAttempt, uid: Int, tagPrefix: String): LogcatResult {
        val logcatPath = if (File("/system/bin/logcat").canExecute()) "/system/bin/logcat" else "logcat"
        val args = mutableListOf(logcatPath, "-d", "-v", "time")
        if (attempt.useUid) {
            args.addAll(listOf("--uid", uid.toString()))
        }
        if (attempt.withSince) {
            args.addAll(listOf("-T", "5d"))
        }
        val rawFile = File(target.parentFile, "${target.nameWithoutExtension}_raw.txt")
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        rawFile.outputStream().use { output ->
            process.inputStream.use { input ->
                input.copyTo(output)
            }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            rawFile.delete()
            return LogcatResult(false, "logcat exit $exitCode")
        }
        val head = try {
            rawFile.bufferedReader().use { it.readLine().orEmpty() }
        } catch (_: Exception) {
            ""
        }
        if (head.startsWith("logcat:") || head.contains("invalid")) {
            rawFile.delete()
            return LogcatResult(false, head.ifBlank { "logcat error" })
        }
        val tagToken = tagPrefix.take(23)
        val tagRegex = Regex("\\s[VDIWEF]\\s+${Regex.escape(tagToken)}")
        var hasLines = false
        var hasAnyLines = false
        target.bufferedWriter().use { writer ->
            rawFile.bufferedReader().forEachLine { line ->
                hasAnyLines = true
                if (tagRegex.containsMatchIn(line) || line.contains(tagToken)) {
                    writer.write(line)
                    writer.newLine()
                    hasLines = true
                }
            }
        }
        if (!hasAnyLines) {
            rawFile.delete()
            target.delete()
            return LogcatResult(false, "empty logcat output")
        }
        if (hasLines) {
            rawFile.delete()
            return LogcatResult(true, "ok")
        }
        if (attempt.useUid) {
            rawFile.copyTo(target, overwrite = true)
            rawFile.delete()
            return LogcatResult(true, "no tag match; used uid-only logs")
        }
        rawFile.delete()
        if (!hasLines) {
            target.delete()
        }
        return LogcatResult(false, "no tag matches")
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


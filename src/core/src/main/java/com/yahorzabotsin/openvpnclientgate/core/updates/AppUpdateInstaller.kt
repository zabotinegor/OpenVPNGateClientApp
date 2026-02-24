package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface AppUpdateInstallResult {
    data object Started : AppUpdateInstallResult
    data object MissingInstallPermission : AppUpdateInstallResult
    data class Failure(val reason: String) : AppUpdateInstallResult
}

interface AppUpdateInstaller {
    suspend fun start(updateInfo: AppUpdateInfo): AppUpdateInstallResult
}

class DefaultAppUpdateInstaller(
    private val appContext: Context,
    private val httpClient: OkHttpClient
) : AppUpdateInstaller {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "AppUpdateInstaller"

    override suspend fun start(updateInfo: AppUpdateInfo): AppUpdateInstallResult = withContext(Dispatchers.IO) {
        val asset = updateInfo.asset ?: return@withContext AppUpdateInstallResult.Failure("No update asset available")
        if (asset.downloadProxyUrl.isBlank()) {
            return@withContext AppUpdateInstallResult.Failure("Download URL is empty")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            return@withContext AppUpdateInstallResult.MissingInstallPermission
        }

        val updatesDir = File(appContext.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        val fileName = sanitizeFileName(asset.name.ifBlank { "app-update.apk" })
        val apkFile = File(updatesDir, fileName)

        val request = Request.Builder().url(asset.downloadProxyUrl).get().build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching AppUpdateInstallResult.Failure("Download failed: ${response.code}")
                }
                val body = response.body ?: return@runCatching AppUpdateInstallResult.Failure("Empty response body")
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val apkUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile
                )
                launchInstaller(apkUri)
                AppUpdateInstallResult.Started
            }
        }.getOrElse { error ->
            AppLog.w(tag, "Update install failed", error)
            AppUpdateInstallResult.Failure(error.message ?: "Unknown error")
        }
    }

    private fun launchInstaller(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withExt = if (sanitized.lowercase().endsWith(".apk")) sanitized else "$sanitized.apk"
        return withExt.take(120)
    }
}

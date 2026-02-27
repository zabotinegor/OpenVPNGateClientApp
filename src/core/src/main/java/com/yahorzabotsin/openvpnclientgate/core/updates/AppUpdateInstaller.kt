package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

sealed interface AppUpdateInstallResult {
    data object Started : AppUpdateInstallResult
    data object MissingInstallPermission : AppUpdateInstallResult
    data class Failure(val reason: String) : AppUpdateInstallResult
}

data class AppUpdateInstallProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val percent: Int?
)

interface AppUpdateInstaller {
    suspend fun start(
        updateInfo: AppUpdateInfo,
        onProgress: (AppUpdateInstallProgress) -> Unit = {}
    ): AppUpdateInstallResult
}

class DefaultAppUpdateInstaller(
    private val appContext: Context,
    private val httpClient: OkHttpClient
) : AppUpdateInstaller {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "AppUpdateInstaller"

    override suspend fun start(
        updateInfo: AppUpdateInfo,
        onProgress: (AppUpdateInstallProgress) -> Unit
    ): AppUpdateInstallResult = withContext(Dispatchers.IO) {
        val asset = updateInfo.asset ?: return@withContext AppUpdateInstallResult.Failure("No update asset available")
        if (asset.downloadProxyUrl.isBlank()) {
            return@withContext AppUpdateInstallResult.Failure("Download URL is empty")
        }
        val downloadUri = runCatching { Uri.parse(asset.downloadProxyUrl) }.getOrNull()
        if (downloadUri?.scheme?.equals("https", ignoreCase = true) != true) {
            return@withContext AppUpdateInstallResult.Failure("Download URL must use HTTPS")
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
                val reportedTotal = body.contentLength().takeIf { it > 0 }
                val totalBytes = when {
                    asset.sizeBytes > 0 -> asset.sizeBytes
                    reportedTotal != null -> reportedTotal
                    else -> 0L
                }
                val expectedHash = normalizeExpectedHash(asset.contentHash)
                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloadedBytes += read
                            onProgress(
                                AppUpdateInstallProgress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes.takeIf { it > 0 },
                                    percent = if (totalBytes > 0) {
                                        ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    } else {
                                        null
                                    }
                                )
                            )
                        }
                    }
                }
                if (asset.sizeBytes > 0 && downloadedBytes != asset.sizeBytes) {
                    return@runCatching AppUpdateInstallResult.Failure(
                        "Size mismatch: expected ${asset.sizeBytes}, got $downloadedBytes"
                    )
                }

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedHash != null && !actualHash.equals(expectedHash, ignoreCase = true)) {
                    return@runCatching AppUpdateInstallResult.Failure("Hash mismatch")
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
            if (error is CancellationException) throw error
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

    private fun normalizeExpectedHash(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val withoutPrefix = value.removePrefix("sha256:").removePrefix("SHA256:")
        val normalized = withoutPrefix.lowercase().replace(Regex("[^0-9a-f]"), "")
        return normalized.takeIf { it.length == 64 }
    }
}

package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

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
        asset: AppUpdateAsset,
        onProgress: (AppUpdateInstallProgress) -> Unit = {}
    ): AppUpdateInstallResult
}

/**
 * Owns only app-private update APKs. The installer is a Koin singleton, so the mutex also
 * serializes Update actions from the main screen and the About screen in this process.
 */
class DefaultAppUpdateInstaller(
    private val appContext: Context,
    private val httpClient: OkHttpClient
) : AppUpdateInstaller {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "AppUpdateInstaller"
    private val cacheMutex = Mutex()

    override suspend fun start(
        asset: AppUpdateAsset,
        onProgress: (AppUpdateInstallProgress) -> Unit
    ): AppUpdateInstallResult = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            installLocked(asset, onProgress)
        }
    }

    private suspend fun installLocked(
        asset: AppUpdateAsset,
        onProgress: (AppUpdateInstallProgress) -> Unit
    ): AppUpdateInstallResult {
        if (asset.downloadProxyUrl.isBlank()) {
            return AppUpdateInstallResult.Failure("Download URL is empty")
        }
        val downloadUri = runCatching { Uri.parse(asset.downloadProxyUrl) }.getOrNull()
        if (downloadUri?.scheme?.equals("https", ignoreCase = true) != true) {
            return AppUpdateInstallResult.Failure("Download URL must use HTTPS")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            return AppUpdateInstallResult.MissingInstallPermission
        }
        if (!asset.assetType.startsWith("apk", ignoreCase = true)) {
            return AppUpdateInstallResult.Failure("Non-APK assets not supported")
        }
        if (asset.sizeBytes > MAX_APK_BYTES) {
            return AppUpdateInstallResult.Failure("APK exceeds allowed cache size")
        }

        val updatesDir = File(appContext.cacheDir, "updates")
        if (!updatesDir.isDirectory && !updatesDir.mkdirs()) {
            return AppUpdateInstallResult.Failure("Unable to create update cache")
        }
        val apkFile = cacheFileFor(updatesDir, asset)
        val partialFile = File(updatesDir, apkFile.name + ".part")
        val expectedHash = normalizeExpectedHash(asset.contentHash)
        val coroutineContext = currentCoroutineContext()
        var cancelWatcher: Job? = null
        pruneCache(updatesDir, apkFile)

        try {
            if (expectedHash != null && isVerifiedCachedApk(apkFile, asset, expectedHash, coroutineContext)) {
                apkFile.setLastModified(System.currentTimeMillis())
                onProgress(AppUpdateInstallProgress(apkFile.length(), apkFile.length(), 100))
                // The owning screen may have been destroyed while the cached file was hashed.
                coroutineContext.ensureActive()
                return launchCachedApk(apkFile, retainOnFailure = true)
            }

            // Filename alone never authenticates an APK. Unverified or incomplete files must
            // not be reused, including old APKs written before this cache policy existed.
            apkFile.delete()
            partialFile.delete()

            val request = Request.Builder().url(asset.downloadProxyUrl).get().build()
            // Blocking OkHttp I/O does not observe coroutine cancellation by itself; cancelling the
            // call unblocks execute() and body reads immediately.
            val call = httpClient.newCall(request)
            cancelWatcher = CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            val downloadResult = call.execute().use { response ->
                coroutineContext.ensureActive()
                if (!response.isSuccessful) {
                    return@use AppUpdateInstallResult.Failure("Download failed: ${response.code}")
                }
                val body = response.body ?: return@use AppUpdateInstallResult.Failure("Empty response body")
                val reportedTotal = body.contentLength().takeIf { it > 0 }
                val totalBytes = if (asset.sizeBytes > 0) asset.sizeBytes else reportedTotal
                if (totalBytes != null && totalBytes > MAX_APK_BYTES) {
                    return@use AppUpdateInstallResult.Failure("APK exceeds allowed cache size")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    partialFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            coroutineContext.ensureActive()
                            if (read <= 0) break
                            downloadedBytes += read
                            if (downloadedBytes > MAX_APK_BYTES) {
                                throw IOException("APK exceeds allowed cache size")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onProgress(
                                AppUpdateInstallProgress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    percent = totalBytes?.takeIf { it > 0 }?.let {
                                        ((downloadedBytes * 100) / it).toInt().coerceIn(0, 100)
                                    }
                                )
                            )
                        }
                    }
                }

                if (asset.sizeBytes > 0 && downloadedBytes != asset.sizeBytes) {
                    return@use AppUpdateInstallResult.Failure(
                        "Size mismatch: expected ${asset.sizeBytes}, got $downloadedBytes"
                    )
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedHash != null && !actualHash.equals(expectedHash, ignoreCase = true)) {
                    return@use AppUpdateInstallResult.Failure("Hash mismatch")
                }
                if (downloadedBytes == 0L) {
                    return@use AppUpdateInstallResult.Failure("Empty APK download")
                }
                if (!partialFile.renameTo(apkFile)) {
                    throw IOException("Unable to finalize APK download")
                }
                AppUpdateInstallResult.Started
            }

            if (downloadResult != AppUpdateInstallResult.Started) {
                return downloadResult
            }
            pruneCache(updatesDir, apkFile)
            coroutineContext.ensureActive()
            // Keep verified, complete APKs if Android's package installer could not be launched.
            // Without a trustworthy hash, never retain a failed install for later reuse.
            return launchCachedApk(apkFile, retainOnFailure = expectedHash != null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A cancelled OkHttp call surfaces as IOException; report it as coroutine cancellation.
            if (!coroutineContext.isActive) throw CancellationException("Update cancelled").apply { initCause(error) }
            AppLog.w(tag, "Update install failed", error)
            return AppUpdateInstallResult.Failure(error.message ?: "Unknown error")
        } finally {
            cancelWatcher?.cancel()
            // Cancellation can happen mid-stream, including from progress callbacks.
            partialFile.delete()
        }
    }

    private fun launchCachedApk(apkFile: File, retainOnFailure: Boolean): AppUpdateInstallResult {
        return try {
            val apkUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            AppUpdateInstallResult.Started
        } catch (error: Exception) {
            if (!retainOnFailure) apkFile.delete()
            AppLog.w(tag, "Unable to launch package installer", error)
            AppUpdateInstallResult.Failure(error.message ?: "Unable to launch package installer")
        }
    }

    private fun cacheFileFor(updatesDir: File, asset: AppUpdateAsset): File {
        // Include all release-defining metadata so equally named assets cannot overwrite each other.
        val identity = listOf(
            asset.id.toString(), asset.platform.lowercase(), asset.buildNumber.toString(),
            asset.assetType.lowercase(), asset.sizeBytes.toString(),
            asset.contentHash.lowercase(), asset.downloadProxyUrl, asset.name
        ).joinToString("\u0000")
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(updatesDir, "$key-${sanitizeFileName(asset.name.ifBlank { "app-update.apk" }).take(80)}")
    }

    private fun isVerifiedCachedApk(
        file: File,
        asset: AppUpdateAsset,
        expectedHash: String,
        coroutineContext: CoroutineContext
    ): Boolean {
        if (!file.isFile || file.length() == 0L || file.length() > MAX_APK_BYTES) return false
        if (asset.sizeBytes > 0 && file.length() != asset.sizeBytes) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedHash, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    private fun pruneCache(directory: File, protectedFile: File) {
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            if (file != protectedFile && file.name.endsWith(".part")) {
                file.delete()
            } else if (file.isApk() &&
                ((file != protectedFile && file.length() > MAX_APK_BYTES) ||
                    now - file.lastModified() > MAX_CACHE_AGE_MS)
            ) {
                // Age expiry also applies to the requested APK, so reuse cannot keep it alive forever;
                // an expired one is simply downloaded again.
                file.delete()
            }
        }
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.isApk() && it != protectedFile }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_CACHED_APKS - 1)
            .forEach { it.delete() }
    }

    private fun File.isApk(): Boolean = name.endsWith(".apk", ignoreCase = true)

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withExt = if (sanitized.lowercase().endsWith(".apk")) sanitized else "$sanitized.apk"
        return withExt.take(80).let { if (it.endsWith(".apk")) it else it.take(76) + ".apk" }
    }

    private fun normalizeExpectedHash(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val withoutPrefix = value.removePrefix("sha256:").removePrefix("SHA256:")
        val normalized = withoutPrefix.lowercase().replace(Regex("[^0-9a-f]"), "")
        return normalized.takeIf { it.length == 64 }
    }

    private companion object {
        const val MAX_CACHED_APKS = 3
        const val MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_APK_BYTES = 250L * 1024 * 1024
    }
}

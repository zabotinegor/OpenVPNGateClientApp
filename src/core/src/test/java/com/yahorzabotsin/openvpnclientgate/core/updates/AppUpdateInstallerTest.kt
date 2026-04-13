package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class AppUpdateInstallerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @Test
    fun `start returns failure when download url blank`() = runTest {
        val installer = DefaultAppUpdateInstaller(context, successClient("apk".toByteArray()))

        val result = installer.start(
            defaultAsset().copy(
                sizeBytes = 1,
                contentHash = "",
                downloadProxyUrl = ""
            )
        )

        assertEquals(AppUpdateInstallResult.Failure("Download URL is empty"), result)
    }

    @Test
    fun `start returns failure when download url is not https`() = runTest {
        val installer = DefaultAppUpdateInstaller(context, successClient("apk".toByteArray()))

        val result = installer.start(
            defaultAsset().copy(downloadProxyUrl = "http://example.com/update.apk")
        )

        assertEquals(AppUpdateInstallResult.Failure("Download URL must use HTTPS"), result)
    }

    @Test
    fun `start returns failure when backend responds non success`() = runTest {
        val installer = DefaultAppUpdateInstaller(context, errorClient(500))

        val result = installer.start(defaultAsset())

        assertEquals(AppUpdateInstallResult.Failure("Download failed: 500"), result)
    }

    @Test
    fun `start writes apk to cache before launching installer`() = runTest {
        val installer = DefaultAppUpdateInstaller(context, successClient("apk-binary".toByteArray()))

        val result = installer.start(
            AppUpdateAsset(
                id = 1,
                name = "My Update 1.0.apk",
                platform = "mobile",
                buildNumber = 2L,
                assetType = "apk-mobile",
                sizeBytes = 10,
                contentHash = "hash",
                downloadProxyUrl = "https://example.com/api/v1/download-assets/1/1"
            )
        )

        assertTrue(result is AppUpdateInstallResult.Failure)
        val failureReason = (result as AppUpdateInstallResult.Failure).reason
        assertTrue(failureReason.contains("fileprovider", ignoreCase = true))

        val updatesDir = File(context.cacheDir, "updates")
        val files = updatesDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files[0].name.endsWith(".apk"))
        assertTrue(files[0].length() > 0)
    }

    @Test
    fun `start returns failure when downloaded size mismatches asset metadata`() = runTest {
        val installer = DefaultAppUpdateInstaller(context, successClient("apk".toByteArray()))
        val result = installer.start(
            defaultAsset().copy(sizeBytes = 10)
        )

        assertEquals(
            AppUpdateInstallResult.Failure("Size mismatch: expected 10, got 3"),
            result
        )
    }

    @Test
    fun `start returns failure when downloaded hash mismatches asset metadata`() = runTest {
        val payload = "apk-binary".toByteArray()
        val installer = DefaultAppUpdateInstaller(context, successClient(payload))
        val result = installer.start(
            defaultAsset().copy(
                sizeBytes = payload.size.toLong(),
                contentHash = "sha256:${"0".repeat(64)}"
            )
        )

        assertEquals(AppUpdateInstallResult.Failure("Hash mismatch"), result)
    }

    @Test
    fun `start reports download progress`() = runTest {
        val payload = ByteArray(16 * 1024) { 1 }
        val installer = DefaultAppUpdateInstaller(context, successClient(payload))
        val progressValues = mutableListOf<AppUpdateInstallProgress>()

        installer.start(
            defaultAsset().copy(
                sizeBytes = payload.size.toLong(),
                contentHash = "sha256:${sha256(payload)}"
            )
        ) { progressValues += it }

        assertTrue(progressValues.isNotEmpty())
        assertEquals(100, progressValues.last().percent)
        assertEquals(payload.size.toLong(), progressValues.last().downloadedBytes)
    }

    @Test
    fun `start accepts hash in uppercase and with sha256 prefix`() = runTest {
        val payload = "apk-binary".toByteArray()
        val installer = DefaultAppUpdateInstaller(context, successClient(payload))

        val result = installer.start(
            defaultAsset().copy(
                sizeBytes = payload.size.toLong(),
                contentHash = "SHA256:${sha256(payload).uppercase()}"
            )
        )

        assertTrue(result is AppUpdateInstallResult.Failure)
        assertTrue((result as AppUpdateInstallResult.Failure).reason.contains("fileprovider", ignoreCase = true))
    }

    @Test
    fun `start ignores invalid hash format`() = runTest {
        val payload = "apk-binary".toByteArray()
        val installer = DefaultAppUpdateInstaller(context, successClient(payload))

        val result = installer.start(
            defaultAsset().copy(
                sizeBytes = payload.size.toLong(),
                contentHash = "not-a-sha256",
            )
        )

        assertTrue(result is AppUpdateInstallResult.Failure)
        assertTrue((result as AppUpdateInstallResult.Failure).reason.contains("fileprovider", ignoreCase = true))
    }

    @Test(expected = CancellationException::class)
    fun `start rethrows cancellation exception`() = runTest {
        val installer = DefaultAppUpdateInstaller(
            context,
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { throw CancellationException("cancelled") })
                .build()
        )

        installer.start(defaultAsset())
    }

    @Test
    fun `start returns failure on non cancellation io exception`() = runTest {
        val installer = DefaultAppUpdateInstaller(
            context,
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { throw IOException("network down") })
                .build()
        )

        val result = installer.start(defaultAsset())

        assertTrue(result is AppUpdateInstallResult.Failure)
        assertTrue((result as AppUpdateInstallResult.Failure).reason.contains("network down"))
    }

    private fun defaultAsset() = AppUpdateAsset(
        id = 1,
        name = "app.apk",
        platform = "mobile",
        buildNumber = 2L,
        assetType = "apk-mobile",
        sizeBytes = 10,
        contentHash = "hash",
        downloadProxyUrl = "https://example.com/api/v1/download-assets/1/1"
    )

    private fun sha256(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun successClient(payload: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody(null))
                    .build()
            })
            .build()

    private fun errorClient(code: Int): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("ERR")
                    .body("".toResponseBody(null))
                    .build()
            })
            .build()
}

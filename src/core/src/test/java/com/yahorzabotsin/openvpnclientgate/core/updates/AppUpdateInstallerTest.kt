package com.yahorzabotsin.openvpnclientgate.core.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    fun `start cleans cached apk when installer launch fails`() = runTest {
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
        assertEquals(0, files.size)
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


    @Test
    fun `verified APK is reused on repeated Update without another GET`() = runTest {
        val payload = "signed-apk-binary".toByteArray()
        val requests = AtomicInteger(0)
        val installer = DefaultAppUpdateInstaller(context, countingClient(payload, requests))
        val asset = defaultAsset().copy(
            sizeBytes = payload.size.toLong(),
            contentHash = "sha256:${sha256(payload)}"
        )

        val first = installer.start(asset)
        val second = installer.start(asset)

        // Robolectric's package installer cannot resolve the test FileProvider.
        assertTrue(first is AppUpdateInstallResult.Failure)
        assertTrue(second is AppUpdateInstallResult.Failure)
        assertEquals(1, requests.get())
        assertEquals(1, File(context.cacheDir, "updates").listFiles().orEmpty().count { it.extension == "apk" })
    }

    @Test
    fun `tampered complete APK is rejected and downloaded again`() = runTest {
        val payload = "signed-apk-binary".toByteArray()
        val requests = AtomicInteger(0)
        val installer = DefaultAppUpdateInstaller(context, countingClient(payload, requests))
        val asset = defaultAsset().copy(
            sizeBytes = payload.size.toLong(),
            contentHash = sha256(payload)
        )
        installer.start(asset)
        val cached = File(context.cacheDir, "updates").listFiles().orEmpty().single { it.extension == "apk" }
        cached.writeBytes(ByteArray(payload.size) { 0 })

        installer.start(asset)

        assertEquals(2, requests.get())
        assertTrue(cached.readBytes().contentEquals(payload))
    }

    @Test
    fun `different release identities never reuse same-named APK`() = runTest {
        val payload = "signed-apk-binary".toByteArray()
        val requests = AtomicInteger(0)
        val installer = DefaultAppUpdateInstaller(context, countingClient(payload, requests))
        val asset = defaultAsset().copy(
            sizeBytes = payload.size.toLong(),
            contentHash = sha256(payload)
        )
        installer.start(asset)
        installer.start(asset.copy(id = 42, buildNumber = 3))

        assertEquals(2, requests.get())
    }

    @Test
    fun `invalid checksum never authorizes cached APK reuse`() = runTest {
        val requests = AtomicInteger(0)
        val payload = "unsigned-apk".toByteArray()
        val installer = DefaultAppUpdateInstaller(context, countingClient(payload, requests))
        val asset = defaultAsset().copy(sizeBytes = payload.size.toLong(), contentHash = "invalid")
        installer.start(asset)
        installer.start(asset)

        assertEquals(2, requests.get())
    }

    @Test
    fun `cancellation during stream removes partially written APK`() = runTest {
        val payload = ByteArray(64 * 1024) { 2 }
        val installer = DefaultAppUpdateInstaller(context, successClient(payload))
        try {
            installer.start(
                defaultAsset().copy(sizeBytes = payload.size.toLong(), contentHash = sha256(payload))
            ) { throw CancellationException("Screen closed") }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation must not leave an installable or reusable partial artifact.
        }

        assertEquals(0, File(context.cacheDir, "updates").listFiles().orEmpty().size)
    }

    @Test
    fun `stale APKs and abandoned partial downloads are pruned`() = runTest {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        repeat(5) { idx ->
            File(dir, "old-$idx.apk").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                setLastModified(System.currentTimeMillis() - (10L + idx) * 24 * 60 * 60 * 1000)
            }
        }
        File(dir, "abandoned.apk.part").writeBytes(byteArrayOf(7))

        val payload = "verified-apk".toByteArray()
        val installer = DefaultAppUpdateInstaller(context, successClient(payload))
        installer.start(defaultAsset().copy(sizeBytes = payload.size.toLong(), contentHash = sha256(payload)))

        assertTrue(dir.listFiles().orEmpty().none { it.extension == "part" })
        assertTrue(dir.listFiles().orEmpty().count { it.extension == "apk" } <= 3)
    }

    @Test
    fun `concurrent Update actions share one verified download`() = runTest {
        val payload = "signed-apk-binary".toByteArray()
        val requests = AtomicInteger(0)
        val installer = DefaultAppUpdateInstaller(context, countingClient(payload, requests))
        val asset = defaultAsset().copy(sizeBytes = payload.size.toLong(), contentHash = sha256(payload))

        val first = async { installer.start(asset) }
        val second = async { installer.start(asset) }
        first.await()
        second.await()

        assertEquals(1, requests.get())
    }

    @Test
    fun `cancelling the coroutine cancels a blocked OkHttp read`() = runBlocking {
        val readStarted = CountDownLatch(1)
        val callCanceled = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val call = chain.call()
                val source = object : ForwardingSource(Buffer()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        readStarted.countDown()
                        while (!call.isCanceled()) Thread.sleep(10)
                        callCanceled.set(true)
                        throw IOException("Canceled")
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(source.buffer().asResponseBody(null, 1024))
                    .build()
            })
            .build()
        val installer = DefaultAppUpdateInstaller(context, client)
        val job = launch(Dispatchers.Default) {
            installer.start(defaultAsset().copy(sizeBytes = 1024, contentHash = ""))
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(callCanceled.get())
        assertEquals(0, File(context.cacheDir, "updates").listFiles().orEmpty().size)
    }

    private fun countingClient(payload: ByteArray, requests: AtomicInteger): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requests.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody(null))
                    .build()
            })
            .build()

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

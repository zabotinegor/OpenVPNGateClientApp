package com.yahorzabotsin.openvpnclientgate.core.about

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.logging.AppFileLogStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LogExportUseCaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun exportRemovesOnlyFilesOlderThanFiveDays() {
        val now = Instant.parse("2026-02-10T12:00:00Z")
        val nowMs = now.toEpochMilli()
        val logsDir = File(context.cacheDir, "logs").apply {
            deleteRecursively()
            mkdirs()
        }
        val oldFile = File(logsDir, "logcat_old.zip").apply {
            writeText("old")
            setLastModified(nowMs - 6L * 24L * 60L * 60L * 1000L)
        }
        val freshFile = File(logsDir, "logcat_recent.zip").apply {
            writeText("fresh")
            setLastModified(nowMs - 1L * 24L * 60L * 60L * 1000L)
        }

        val output = "${formatTs(now.minusSeconds(60))} I OpenVPNGateApp: hello\n"
        val useCase = LogExportUseCase(
            context = context,
            nowMsProvider = { nowMs },
            processFactory = { FakeProcess(output, 0) }
        )

        val result = runBlocking { useCase.export() }

        assertTrue(result is LogExportResult.Success)
        assertFalse(oldFile.exists())
        assertTrue(freshFile.exists())
    }

    @Test
    fun exportContainsOnlyLastFiveDays() {
        val now = Instant.parse("2026-02-10T12:00:00Z")
        val nowMs = now.toEpochMilli()
        File(context.cacheDir, "logs").deleteRecursively()
        val oldTs = formatTs(now.minusSeconds(7L * 24L * 60L * 60L))
        val freshTs = formatTs(now.minusSeconds(2L * 24L * 60L * 60L))
        val output = buildString {
            appendLine("$oldTs I OpenVPNGateApp: old")
            appendLine("old stack line")
            appendLine("$freshTs I OpenVPNGateApp: fresh")
            appendLine("fresh stack line")
        }

        val useCase = LogExportUseCase(
            context = context,
            nowMsProvider = { nowMs },
            processFactory = { FakeProcess(output, 0) }
        )

        val result = runBlocking { useCase.export() }
        assertTrue(result is LogExportResult.Success)
        val zipFile = (result as LogExportResult.Success).file
        val content = unzipSingleFile(zipFile)

        assertFalse(content.contains("old"))
        assertFalse(content.contains("old stack line"))
        assertTrue(content.contains("fresh"))
        assertTrue(content.contains("fresh stack line"))
    }

    @Test
    fun exportUsesPersistentAppLogsWhenLogcatIsUnavailable() {
        val now = Instant.parse("2026-02-10T12:00:00Z")
        val nowMs = now.toEpochMilli()
        File(context.cacheDir, "logs").deleteRecursively()
        File(context.filesDir, "logs").deleteRecursively()

        val appLogs = AppFileLogStore(context, nowMsProvider = { nowMs })
        appLogs.write(android.util.Log.INFO, "OpenVPNGateApp:Test", "persistent line")

        val useCase = LogExportUseCase(
            context = context,
            nowMsProvider = { nowMs },
            processFactory = { FakeProcess("logcat: permission denied", 1) }
        )

        val result = runBlocking { useCase.export() }
        assertTrue(result is LogExportResult.Success)
        val zipFile = (result as LogExportResult.Success).file
        val content = unzipSingleFile(zipFile)
        assertTrue(content.contains("persistent line"))
    }

    private fun formatTs(instant: Instant): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(instant)

    private fun unzipSingleFile(zip: File): String {
        ZipInputStream(zip.inputStream()).use { zis ->
            val entry = zis.nextEntry ?: return ""
            if (entry.isDirectory) return ""
            return zis.bufferedReader().readText()
        }
    }

    private class FakeProcess(
        outputText: String,
        private val exitCode: Int
    ) : Process() {
        private val input = ByteArrayInputStream(outputText.toByteArray())

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun exitValue(): Int = exitCode
        override fun destroy() {}
    }
}

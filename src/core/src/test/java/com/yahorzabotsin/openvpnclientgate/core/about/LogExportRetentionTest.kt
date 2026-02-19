package com.yahorzabotsin.openvpnclientgate.core.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempDirectory

class LogExportRetentionTest {

    @Test
    fun cleanupDeletesOnlyFilesOlderThanRetention() {
        val dir = createTempDirectory("log-retention").toFile()
        val nowMs = Instant.parse("2026-02-10T12:00:00Z").toEpochMilli()
        val oldFile = File(dir, "logcat_old.zip").apply {
            writeText("old")
            setLastModified(nowMs - 6L * 24L * 60L * 60L * 1000L)
        }
        val freshFile = File(dir, "logcat_fresh.zip").apply {
            writeText("fresh")
            setLastModified(nowMs - 2L * 24L * 60L * 60L * 1000L)
        }
        val unrelated = File(dir, "notes.txt").apply {
            writeText("keep")
            setLastModified(nowMs - 30L * 24L * 60L * 60L * 1000L)
        }

        LogExportRetention.cleanupOldExportFiles(dir, nowMs) { _, _ -> }

        assertFalse(oldFile.exists())
        assertTrue(freshFile.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun filterKeepsOnlyLastFiveDaysAndContinuationLines() {
        val dir = createTempDirectory("log-filter").toFile()
        val now = Instant.parse("2026-02-10T12:00:00Z")
        val nowMs = now.toEpochMilli()
        val source = File(dir, "source.txt")
        val target = File(dir, "target.txt")

        val oldTs = formatLogcatTimestamp(now.minusSeconds(7L * 24L * 60L * 60L))
        val freshTs = formatLogcatTimestamp(now.minusSeconds(2L * 24L * 60L * 60L))
        source.writeText(
            buildString {
                appendLine("$oldTs I OpenVPNGateApp: old line")
                appendLine("old continuation")
                appendLine("$freshTs I OpenVPNGateApp: fresh line")
                appendLine("fresh continuation")
            }
        )

        val hasData = LogExportRetention.filterFileToLastDays(source, target, nowMs)
        val output = target.readText()

        assertTrue(hasData)
        assertFalse(output.contains("old line"))
        assertFalse(output.contains("old continuation"))
        assertTrue(output.contains("fresh line"))
        assertTrue(output.contains("fresh continuation"))
    }

    private fun formatLogcatTimestamp(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }
}

package com.yahorzabotsin.openvpnclientgate.core.about

import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal object LogExportRetention {
    const val RETENTION_DAYS = 5L
    private val retentionDuration: Duration = Duration.ofDays(RETENTION_DAYS)
    private val futureTolerance: Duration = Duration.ofMinutes(1)
    private val yearRolloverTolerance: Duration = Duration.ofDays(1)
    private val timestampRegex = Regex("^(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.\\d{3}.*$")

    fun cleanupOldExportFiles(
        outputDir: File,
        nowMs: Long,
        onDeleteFailure: (file: File, error: Throwable) -> Unit
    ) {
        val cutoffMs = nowMs - retentionDuration.toMillis()
        outputDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (!isExportLogFile(file.name)) return@forEach
            if (file.lastModified() >= cutoffMs) return@forEach
            runCatching { file.delete() }
                .onFailure { error ->
                    onDeleteFailure(file, error)
                }
                .onSuccess { deleted ->
                    if (!deleted) {
                        onDeleteFailure(file, IOException("Failed to delete old export file: ${file.name}"))
                    }
                }
        }
    }

    fun filterFileToLastDays(source: File, target: File, nowMs: Long, days: Long = RETENTION_DAYS): Boolean {
        if (!source.exists()) return false
        val now = Instant.ofEpochMilli(nowMs)
        val cutoff = now.minus(Duration.ofDays(days))
        var hasMatched = false
        var includeContinuation = false
        val temp = File(target.parentFile, "${target.nameWithoutExtension}_timefiltered.tmp")

        temp.bufferedWriter().use { writer ->
            source.bufferedReader().forEachLine { line ->
                val ts = parseTimestamp(line, now)
                if (ts != null) {
                    includeContinuation = !ts.isBefore(cutoff) && !ts.isAfter(now.plus(futureTolerance))
                    if (includeContinuation) {
                        writer.write(line)
                        writer.newLine()
                        hasMatched = true
                    }
                } else if (includeContinuation) {
                    writer.write(line)
                    writer.newLine()
                    hasMatched = true
                }
            }
        }

        if (hasMatched) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            return true
        }

        temp.delete()
        return false
    }

    private fun isExportLogFile(name: String): Boolean {
        val isLogExport = name.startsWith("logcat_") &&
            (name.endsWith(".txt") || name.endsWith(".zip"))
        val isRawLog = name.endsWith("_raw.txt")
        return isLogExport || isRawLog
    }

    private fun parseTimestamp(line: String, now: Instant): Instant? {
        val match = timestampRegex.find(line) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].toIntOrNull() ?: return null
        val second = match.groupValues[5].toIntOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val nowDate = LocalDateTime.ofInstant(now, zone)
        val thisYear = runCatching {
            LocalDateTime.of(nowDate.year, month, day, hour, minute, second).atZone(zone).toInstant()
        }.getOrNull() ?: return null
        if (thisYear.isAfter(now.plus(yearRolloverTolerance))) {
            return runCatching {
                LocalDateTime.of(nowDate.year - 1, month, day, hour, minute, second).atZone(zone).toInstant()
            }.getOrNull()
        }
        return thisYear
    }
}

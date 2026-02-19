package com.yahorzabotsin.openvpnclientgate.core.logging

import android.content.Context
import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportRetention
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AppFileLogStore(
    context: Context,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val TAG = "AppFileLogStore"
    }

    private val logDir = File(context.filesDir, "logs")
    private val lock = Any()
    private val lineFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val fileNameRegex = Regex("^app_(\\d{4}-\\d{2}-\\d{2})\\.log$")
    private var lastCleanupDate: LocalDate? = null

    fun write(priority: Int, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                if (!logDir.exists()) logDir.mkdirs()
                val zone = ZoneId.systemDefault()
                val now = Instant.ofEpochMilli(nowMsProvider())
                val currentDate = now.atZone(zone).toLocalDate()
                ensureDailyCleanupLocked(currentDate)
                val fileName = "app_${fileDateFormatter.format(currentDate)}.log"
                val target = File(logDir, fileName)
                FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    val ts = lineFormatter.format(now.atZone(zone).toLocalDateTime())
                    val level = when (priority) {
                        Log.VERBOSE -> "V"
                        Log.DEBUG -> "D"
                        Log.INFO -> "I"
                        Log.WARN -> "W"
                        Log.ERROR -> "E"
                        Log.ASSERT -> "A"
                        else -> priority.toString()
                    }
                    message.lineSequence().forEach { line ->
                        writer.append(ts)
                        writer.append(' ')
                        writer.append(level)
                        writer.append(' ')
                        writer.append(tag)
                        writer.append(": ")
                        writer.append(line)
                        writer.newLine()
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to write app log", error)
            }
        }
    }

    fun appendLastDaysTo(target: File, days: Long = LogExportRetention.RETENTION_DAYS, nowMs: Long = nowMsProvider()): Boolean {
        synchronized(lock) {
            if (!logDir.exists()) return false
            val zone = ZoneId.systemDefault()
            val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            ensureDailyCleanupLocked(now)
            val cutoffDate = now.minusDays(days)
            val sourceFiles = logDir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile }
                ?.mapNotNull { file ->
                    parseLogFileDate(file.name)?.let { date -> file to date }
                }
                ?.filter { (_, date) -> !date.isBefore(cutoffDate) }
                ?.sortedBy { (_, date) -> date }
                ?.map { (file, _) -> file }
                ?.toList()
                ?: emptyList()
            if (sourceFiles.isEmpty()) return false

            var appended = false
            val targetDir = target.parentFile ?: return false
            if (!targetDir.exists()) targetDir.mkdirs()
            FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                sourceFiles.forEach { source ->
                    source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            writer.appendLine(line)
                            appended = true
                        }
                    }
                }
            }
            return appended
        }
    }

    private fun ensureDailyCleanupLocked(nowDate: LocalDate) {
        if (lastCleanupDate == nowDate) return
        cleanupOldLogsLocked(nowDate)
        lastCleanupDate = nowDate
    }

    private fun cleanupOldLogsLocked(nowDate: LocalDate) {
        if (!logDir.exists()) return
        val cutoffDate = nowDate.minusDays(LogExportRetention.RETENTION_DAYS)
        logDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val date = parseLogFileDate(file.name) ?: return@forEach
            if (date.isBefore(cutoffDate)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun parseLogFileDate(fileName: String): LocalDate? {
        val match = fileNameRegex.matchEntire(fileName) ?: return null
        return runCatching { LocalDate.parse(match.groupValues[1], fileDateFormatter) }.getOrNull()
    }
}

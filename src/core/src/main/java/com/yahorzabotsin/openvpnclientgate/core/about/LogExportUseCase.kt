package com.yahorzabotsin.openvpnclientgate.core.about

import android.content.Context
import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface LogExportResult {
    data class Success(val file: File, val path: String) : LogExportResult
    data class Failure(val reason: String) : LogExportResult
}

interface LogExportInteractor {
    suspend fun export(): LogExportResult
}

class LogExportUseCase(
    private val context: Context
) : LogExportInteractor {
    private companion object {
        private val TAG = LogTags.APP + ':' + "LogExport"
    }

    override suspend fun export(): LogExportResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputDir = File(context.cacheDir, "logs")
        if (!outputDir.exists()) outputDir.mkdirs()
        cleanupOldExports(outputDir)

        val logFile = File(outputDir, "logcat_${timestamp}.txt")
        val zipFile = File(outputDir, "logcat_${timestamp}.zip")

        val uid = context.applicationInfo.uid
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
                    logFile.name.also { entryName ->
                        zos.putNextEntry(ZipEntry(entryName))
                        logFile.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
                logFile.delete()
                zipFile.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Failed to archive logs", e)
                logFile.delete()
                zipFile.delete()
                ""
            }
        } else {
            logFile.delete()
            zipFile.delete()
            ""
        }

        if (resultPath.isNotBlank()) {
            LogExportResult.Success(zipFile, resultPath)
        } else {
            LogExportResult.Failure(failureReason)
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

    private fun cleanupOldExports(outputDir: File) {
        outputDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach

            val name = file.name
            val isLogExport = name.startsWith("logcat_") &&
                (name.endsWith(".txt") || name.endsWith(".zip"))
            val isRawLog = name.endsWith("_raw.txt")

            if (!isLogExport && !isRawLog) return@forEach

            runCatching { file.delete() }
                .onFailure { error ->
                    Log.w(TAG, "Failed to delete old export file: ${file.absolutePath}", error)
                }
        }
    }
}

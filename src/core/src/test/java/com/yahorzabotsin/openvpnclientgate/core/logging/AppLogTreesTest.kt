package com.yahorzabotsin.openvpnclientgate.core.logging

import android.app.Application
import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppLogTreesTest {

    @Test
    fun releaseTree_doesNotPersistDebugOrVerboseLogs() {
        val app: Application = RuntimeEnvironment.getApplication()
        clearLogs(app)
        val store = AppFileLogStore(app, nowMsProvider = { 1_800_000_000_000L })
        val tree = AppReleaseTree(store)
        val exportFile = File(app.cacheDir, "release_debug_export.log")
        if (exportFile.exists()) exportFile.delete()

        tree.log(Log.DEBUG, "TestTag", "debug https://example.com/path?token=abc", null)

        val appended = store.appendLastDaysTo(exportFile)
        assertFalse(appended)
    }

    @Test
    fun releaseTree_redactsUrlBeforePersistingInfoLogs() {
        val app: Application = RuntimeEnvironment.getApplication()
        clearLogs(app)
        val store = AppFileLogStore(app, nowMsProvider = { 1_900_000_000_000L })
        val tree = AppReleaseTree(store)
        val exportFile = File(app.cacheDir, "release_info_export.log")
        if (exportFile.exists()) exportFile.delete()

        tree.log(
            Log.INFO,
            "TestTag",
            "source url=https://user:pass@example.com:8443/path?token=abc",
            null
        )

        val appended = store.appendLastDaysTo(exportFile)
        val contents = if (exportFile.exists()) exportFile.readText(Charsets.UTF_8) else ""

        assertTrue(appended)
        assertTrue(contents.isNotBlank())
        assertFalse(contents.contains("/path?token=abc"))
        assertFalse(contents.contains("user:pass"))
        assertFalse(contents.contains("token=abc"))
    }

    private fun clearLogs(app: Application) {
        val logsDir = File(app.filesDir, "logs")
        if (logsDir.exists()) {
            logsDir.listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
        }
    }
}

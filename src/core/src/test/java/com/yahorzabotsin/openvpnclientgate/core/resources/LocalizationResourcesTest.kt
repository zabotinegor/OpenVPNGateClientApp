package com.yahorzabotsin.openvpnclientgate.core.resources

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalizationResourcesTest {

    @Test
    fun mainStatusStopFailed_existsInRuAndPlResources() {
        val resDir = resolveCoreResDir()
        val ru = File(resDir, "values-ru/strings.xml")
        val pl = File(resDir, "values-pl/strings.xml")

        assertTrue("values-ru/strings.xml must exist", ru.exists())
        assertTrue("values-pl/strings.xml must exist", pl.exists())

        assertTrue(
            "Russian strings must contain main_status_stop_failed",
            ru.readText().contains("name=\"main_status_stop_failed\"")
        )
        assertTrue(
            "Polish strings must contain main_status_stop_failed",
            pl.readText().contains("name=\"main_status_stop_failed\"")
        )
    }

    private fun resolveCoreResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("core/src/main/res"),
            File("src/core/src/main/res"),
            File("../core/src/main/res")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Cannot locate core resource directory from working dir: ${File(".").absolutePath}")
    }
}

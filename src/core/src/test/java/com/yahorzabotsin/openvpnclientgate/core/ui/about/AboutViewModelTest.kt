package com.yahorzabotsin.openvpnclientgate.core.ui.about

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfo
import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfoProvider
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinks
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinksProvider
import com.yahorzabotsin.openvpnclientgate.core.about.ElapsedRealtimeProvider
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportResult
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init loads state from providers`() = runTest {
        val vm = AboutViewModel(
            infoProvider = FakeInfoProvider(),
            linksProvider = FakeLinksProvider(),
            logExportUseCase = FakeLogExportInteractor(LogExportResult.Failure("fail")),
            elapsedRealtimeProvider = FakeElapsedRealtime()
        )

        val state = vm.state.value
        assertEquals("1.0", state.info.versionName)
        assertEquals(1, state.info.versionCode)
        assertEquals("pkg", state.info.packageName)
        assertEquals("site", state.links.website)
    }

    @Test
    fun `row click emits open url effect`() = runTest {
        val vm = AboutViewModel(
            infoProvider = FakeInfoProvider(),
            linksProvider = FakeLinksProvider(),
            logExportUseCase = FakeLogExportInteractor(LogExportResult.Failure("fail")),
            elapsedRealtimeProvider = FakeElapsedRealtime()
        )
        val commands = mutableListOf<AboutCommand>()
        val job = launch { vm.commands.take(1).toList(commands) }
        runCurrent()

        vm.onWebsiteClick()
        advanceUntilIdle()

        assertTrue(commands.first() is AboutCommand.OpenUrl)
        job.cancel()
    }

    @Test
    fun `row long click emits copy and toast`() = runTest {
        val vm = AboutViewModel(
            infoProvider = FakeInfoProvider(),
            linksProvider = FakeLinksProvider(),
            logExportUseCase = FakeLogExportInteractor(LogExportResult.Failure("fail")),
            elapsedRealtimeProvider = FakeElapsedRealtime()
        )
        val commands = mutableListOf<AboutCommand>()
        val job = launch { vm.commands.take(2).toList(commands) }
        runCurrent()

        vm.onEmailLongClick()
        advanceUntilIdle()

        assertTrue(commands[0] is AboutCommand.CopyToClipboard)
        assertEquals(R.string.copy_label_email, (commands[0] as AboutCommand.CopyToClipboard).labelResId)
        assertTrue(commands[1] is AboutCommand.ShowToast)
        job.cancel()
    }

    @Test
    fun `export logs success emits share and toast`() = runTest {
        val file = File("dummy.zip")
        val vm = AboutViewModel(
            infoProvider = FakeInfoProvider(),
            linksProvider = FakeLinksProvider(),
            logExportUseCase = FakeLogExportInteractor(LogExportResult.Success(file, "/tmp/dummy.zip")),
            elapsedRealtimeProvider = FakeElapsedRealtime()
        )
        val commands = mutableListOf<AboutCommand>()
        val job = launch { vm.commands.take(3).toList(commands) }
        runCurrent()

        vm.onLogsClick()
        advanceUntilIdle()

        assertTrue(commands.any { it is AboutCommand.ShareLogArchive })
        assertTrue(commands.any { it is AboutCommand.ShowToast })
        job.cancel()
    }

    private class FakeInfoProvider : AboutInfoProvider {
        override fun load(): AboutInfo = AboutInfo(
            versionName = "1.0",
            versionCode = 1,
            packageName = "pkg",
            engineName = "engine",
            engineLicense = "license",
            copyrightOwner = "owner",
            year = 2025
        )
    }

    private class FakeLinksProvider : AboutLinksProvider {
        override fun get(): AboutLinks = AboutLinks(
            website = "site",
            email = "mail",
            telegram = "",
            github = "",
            githubEngine = "",
            googlePlay = "",
            privacyPolicy = "",
            termsOfUse = "",
            gplv2 = "",
            icsGithub = ""
        )
    }

    private class FakeLogExportInteractor(private val result: LogExportResult) : LogExportInteractor {
        override suspend fun export(): LogExportResult = result
    }

    private class FakeElapsedRealtime : ElapsedRealtimeProvider {
        private var now = 0L
        override fun elapsedRealtimeMs(): Long {
            now += 1000
            return now
        }
    }
}

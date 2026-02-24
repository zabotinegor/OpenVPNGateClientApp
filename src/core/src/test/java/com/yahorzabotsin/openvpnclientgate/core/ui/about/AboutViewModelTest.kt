package com.yahorzabotsin.openvpnclientgate.core.ui.about

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfo
import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfoProvider
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinks
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinksProvider
import com.yahorzabotsin.openvpnclientgate.core.about.ElapsedRealtimeProvider
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportResult
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportInteractor
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateAsset
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInfo
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.main.UpdateCheckInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val vm = createViewModel()

        val state = vm.state.value
        assertEquals("1.0", state.info.versionName)
        assertEquals(1, state.info.versionCode)
        assertEquals("pkg", state.info.packageName)
        assertEquals("site", state.links.website)
    }

    @Test
    fun `row click emits open url effect`() = runTest {
        val vm = createViewModel()
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.WEBSITE))
        advanceUntilIdle()

        assertTrue(effects.first() is AboutEffect.OpenUrl)
        job.cancel()
    }

    @Test
    fun `row long click emits copy and toast`() = runTest {
        val vm = createViewModel()
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(2).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowLongClick(AboutRowId.EMAIL))
        advanceUntilIdle()

        assertTrue(effects[0] is AboutEffect.CopyToClipboard)
        assertEquals(R.string.copy_label_email, (effects[0] as AboutEffect.CopyToClipboard).labelResId)
        assertTrue(effects[1] is AboutEffect.ShowToast)
        job.cancel()
    }

    @Test
    fun `export logs success emits share and toast`() = runTest {
        val file = File("dummy.zip")
        val vm = createViewModel(
            logExportUseCase = FakeLogExportInteractor(LogExportResult.Success(file, "/tmp/dummy.zip"))
        )
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(3).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.LOGS))
        advanceUntilIdle()

        assertTrue(effects.any { it is AboutEffect.ShareLogArchive })
        assertTrue(effects.any { it is AboutEffect.ShowToast })
        job.cancel()
    }

    @Test
    fun `check updates emits failed toast when backend response is null`() = runTest {
        val updateInteractor = FakeUpdateInteractor(update = null)
        val vm = createViewModel(updateCheckInteractor = updateInteractor)
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.CHECK_UPDATES))
        advanceUntilIdle()

        val effect = effects.first() as AboutEffect.ShowToast
        assertEquals(UiText.Res(R.string.update_check_failed), effect.text)
        assertEquals(ToastDuration.LONG, effect.duration)
        assertTrue(updateInteractor.forceRefreshCalls.all { it })
        job.cancel()
    }

    @Test
    fun `check updates emits up to date toast when update is not available`() = runTest {
        val updateInteractor = FakeUpdateInteractor(update = sampleUpdate(hasUpdate = false))
        val vm = createViewModel(updateCheckInteractor = updateInteractor)
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.CHECK_UPDATES))
        advanceUntilIdle()

        val effect = effects.first() as AboutEffect.ShowToast
        assertEquals(UiText.Res(R.string.update_up_to_date), effect.text)
        assertEquals(ToastDuration.SHORT, effect.duration)
        assertTrue(updateInteractor.forceRefreshCalls.all { it })
        job.cancel()
    }

    @Test
    fun `check updates emits no asset toast when update has no downloadable asset`() = runTest {
        val updateInteractor = FakeUpdateInteractor(update = sampleUpdate(asset = null))
        val vm = createViewModel(updateCheckInteractor = updateInteractor)
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.CHECK_UPDATES))
        advanceUntilIdle()

        val effect = effects.first() as AboutEffect.ShowToast
        assertEquals(UiText.Res(R.string.update_available_no_asset), effect.text)
        assertEquals(ToastDuration.LONG, effect.duration)
        assertTrue(updateInteractor.forceRefreshCalls.all { it })
        job.cancel()
    }

    @Test
    fun `check updates emits no asset toast when asset url is blank`() = runTest {
        val assetWithoutUrl = sampleAsset().copy(downloadProxyUrl = "")
        val updateInteractor = FakeUpdateInteractor(update = sampleUpdate(asset = assetWithoutUrl))
        val vm = createViewModel(updateCheckInteractor = updateInteractor)
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.CHECK_UPDATES))
        advanceUntilIdle()

        val effect = effects.first() as AboutEffect.ShowToast
        assertEquals(UiText.Res(R.string.update_available_no_asset), effect.text)
        assertEquals(ToastDuration.LONG, effect.duration)
        assertTrue(updateInteractor.forceRefreshCalls.all { it })
        job.cancel()
    }

    @Test
    fun `check updates emits prompt when update and asset are available`() = runTest {
        val update = sampleUpdate()
        val updateInteractor = FakeUpdateInteractor(update = update)
        val vm = createViewModel(updateCheckInteractor = updateInteractor)
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.CHECK_UPDATES))
        advanceUntilIdle()

        val effect = effects.first() as AboutEffect.PromptUpdate
        assertEquals(update, effect.update)
        assertTrue(updateInteractor.forceRefreshCalls.all { it })
        job.cancel()
    }

    @Test
    fun `long click on check updates does nothing`() = runTest {
        val vm = createViewModel()
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowLongClick(AboutRowId.CHECK_UPDATES))
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        job.cancel()
    }

    @Test
    fun `debounce blocks rapid repeated actions`() = runTest {
        val elapsed = ControlledElapsedRealtime(1000L, 1200L)
        val vm = createViewModel(elapsedRealtimeProvider = elapsed)
        val effects = mutableListOf<AboutEffect>()
        val job = launch { vm.effects.take(2).toList(effects) }
        runCurrent()

        vm.onAction(AboutAction.RowClick(AboutRowId.WEBSITE))
        vm.onAction(AboutAction.RowClick(AboutRowId.WEBSITE))
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects.first() is AboutEffect.OpenUrl)
        job.cancel()
    }

    @Test
    fun `export logs failure resets exporting flag`() = runTest {
        val vm = createViewModel(
            logExportUseCase = FakeLogExportInteractor(LogExportResult.Failure("boom"))
        )
        vm.onAction(AboutAction.RowClick(AboutRowId.LOGS))
        advanceUntilIdle()
        assertFalse(vm.state.value.isExportingLogs)
    }

    private fun createViewModel(
        infoProvider: AboutInfoProvider = FakeInfoProvider(),
        linksProvider: AboutLinksProvider = FakeLinksProvider(),
        logExportUseCase: LogExportInteractor = FakeLogExportInteractor(LogExportResult.Failure("fail")),
        elapsedRealtimeProvider: ElapsedRealtimeProvider = FakeElapsedRealtime(),
        updateCheckInteractor: UpdateCheckInteractor = FakeUpdateInteractor()
    ): AboutViewModel = AboutViewModel(
        infoProvider = infoProvider,
        linksProvider = linksProvider,
        logExportUseCase = logExportUseCase,
        elapsedRealtimeProvider = elapsedRealtimeProvider,
        updateCheckInteractor = updateCheckInteractor
    )

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
            androidStore = "",
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

    private class ControlledElapsedRealtime(private vararg val values: Long) : ElapsedRealtimeProvider {
        private var index = 0
        override fun elapsedRealtimeMs(): Long {
            val value = if (index < values.size) values[index] else values.lastOrNull() ?: 0L
            index += 1
            return value
        }
    }

    private class FakeUpdateInteractor(
        private val update: AppUpdateInfo? = null
    ) : UpdateCheckInteractor {
        val forceRefreshCalls = mutableListOf<Boolean>()

        override suspend fun check(forceRefresh: Boolean): AppUpdateInfo? {
            forceRefreshCalls += forceRefresh
            return update
        }
    }

    private fun sampleUpdate(
        hasUpdate: Boolean = true,
        asset: AppUpdateAsset? = sampleAsset()
    ) = AppUpdateInfo(
        hasUpdate = hasUpdate,
        currentBuild = 1L,
        latestBuild = 2L,
        platform = "mobile",
        latestVersion = "1.1",
        name = "Release 1.1",
        changelog = "Changes",
        resolvedLocale = "en",
        message = "Update available.",
        asset = asset
    )

    private fun sampleAsset() = AppUpdateAsset(
        id = 1,
        name = "app.apk",
        assetType = "apk-mobile",
        sizeBytes = 100,
        contentHash = "hash",
        downloadProxyUrl = "https://example.com/download.apk"
    )
}

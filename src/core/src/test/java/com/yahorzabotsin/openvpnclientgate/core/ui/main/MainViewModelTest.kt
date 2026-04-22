package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateAsset
import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInfo
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.versions.LatestReleaseInfo
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `load initial selection updates state with api refresh when vpn connected`() = runTest {
        val interactor = FakeMainSelectionInteractor(
            initialSelection = InitialSelection(
                country = "France",
                city = "Paris",
                config = "config",
                countryCode = "FR",
                ip = "1.2.3.4"
            )
        )
        val viewModel = createViewModel(
            selectionInteractor = interactor,
            connectionState = ConnectionState.CONNECTED
        )

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        assertTrue(interactor.lastCacheOnly == false)
        val selected = viewModel.state.value.selectedServer
        assertEquals("France", selected?.country)
        assertEquals("config", selected?.config)
        assertEquals(false, selected?.fromUserSelection)
    }

    @Test
    fun `load initial selection keeps whats new and update independent`() = runTest {
        val versionReleaseInteractor = FakeVersionReleaseInteractor(latest = sampleRelease())
        val updateCheckInteractor = FakeUpdateCheckInteractor(latest = sampleUpdate())
        val viewModel = createViewModel(
            versionReleaseInteractor = versionReleaseInteractor,
            updateCheckInteractor = updateCheckInteractor
        )

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        assertEquals("1.0.0", viewModel.state.value.whatsNew?.versionNumber)
        assertEquals("1.2.3", viewModel.state.value.availableUpdate?.versionNumber)
        assertEquals(1, versionReleaseInteractor.callCount)
        assertEquals(1, updateCheckInteractor.callCount)
    }

    @Test
    fun `load update availability maps asset metadata to state`() = runTest {
        val asset = AppUpdateAsset(
            id = 7,
            name = "OpenVPNGateClient_mobile.apk",
            platform = "mobile",
            buildNumber = 123L,
            assetType = "apk-mobile",
            sizeBytes = 987654L,
            contentHash = "abc123",
            downloadProxyUrl = "https://example.com/api/v1/download-assets/10/7"
        )
        val viewModel = createViewModel(
            updateCheckInteractor = FakeUpdateCheckInteractor(latest = sampleUpdate(asset = asset))
        )

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val update = viewModel.state.value.availableUpdate
        assertEquals(asset.name, update?.assetName)
        assertEquals(asset.buildNumber, update?.assetBuildNumber)
        assertEquals(asset.assetType, update?.assetType)
        assertEquals(asset.sizeBytes, update?.assetSizeBytes)
        assertEquals(asset.contentHash, update?.assetContentHash)
        assertEquals(asset.downloadProxyUrl, update?.downloadProxyUrl)
    }

    @Test
    fun `whats new menu emits web destination effect`() = runTest {
        val viewModel = createViewModel(
            versionReleaseInteractor = FakeVersionReleaseInteractor(latest = sampleRelease())
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(2).toList(effects)
        }

        viewModel.onAction(MainAction.NavigationItemSelected(R.id.nav_whats_new))
        advanceUntilIdle()

        assertTrue(
            effects.any {
                it is MainEffect.OpenDestination &&
                    it.destination is MainDestination.WhatsNew &&
                    (it.destination as MainDestination.WhatsNew).data.versionNumber == "1.0.0"
            }
        )
        assertTrue(effects.any { it is MainEffect.CloseDrawer })
        job.cancel()
    }

    @Test
    fun `update menu emits prompt effect when update is available`() = runTest {
        val viewModel = createViewModel(
            updateCheckInteractor = FakeUpdateCheckInteractor(latest = sampleUpdate())
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(3).toList(effects)
        }

        viewModel.onAction(MainAction.NavigationItemSelected(R.id.nav_update))
        advanceUntilIdle()

        val promptEffects = effects.filterIsInstance<MainEffect.PromptUpdate>()
        assertEquals(2, promptEffects.size)
        assertTrue(promptEffects.any { it.update.versionNumber == "1.2.3" && !it.oneTimeOnly })
        assertTrue(effects.any { it is MainEffect.CloseDrawer })
        job.cancel()
    }

    @Test
    fun `refresh update availability calls only update interactor`() = runTest {
        val versionReleaseInteractor = FakeVersionReleaseInteractor(latest = sampleRelease())
        val updateCheckInteractor = FakeUpdateCheckInteractor(latest = sampleUpdate())
        val viewModel = createViewModel(
            versionReleaseInteractor = versionReleaseInteractor,
            updateCheckInteractor = updateCheckInteractor
        )

        viewModel.onAction(MainAction.RefreshUpdateAvailability)
        advanceUntilIdle()

        assertEquals(0, versionReleaseInteractor.callCount)
        assertEquals(1, updateCheckInteractor.callCount)
    }

    @Test
    fun `foreground sync action calls server sync coordinator`() = runTest {
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val viewModel = createViewModel(serverSyncCoordinator = syncCoordinator)

        viewModel.onAction(MainAction.SyncServersForForeground)
        advanceUntilIdle()

        assertEquals(1, syncCoordinator.callCount)
        assertEquals(false, syncCoordinator.lastForceRefresh)
    }

    @Test
    fun `foreground sync action is debounced for consecutive calls`() = runTest {
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val viewModel = createViewModel(serverSyncCoordinator = syncCoordinator)

        viewModel.onAction(MainAction.SyncServersForForeground)
        viewModel.onAction(MainAction.SyncServersForForeground)
        advanceUntilIdle()

        assertEquals(1, syncCoordinator.callCount)
    }
    @Test
    fun `load initial selection debounces immediate foreground sync`() = runTest {
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val viewModel = createViewModel(serverSyncCoordinator = syncCoordinator)

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        viewModel.onAction(MainAction.SyncServersForForeground)
        advanceUntilIdle()

        assertEquals(1, syncCoordinator.callCount)
    }

    @Test
    fun `connection click without selected server emits toast effect`() = runTest {
        val viewModel = createViewModel(connectionState = ConnectionState.DISCONNECTED)

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = true,
                hasVpnPermission = true
            )
        )
        advanceUntilIdle()

        val effect = effects.first() as MainEffect.ShowToast
        assertEquals(UiText.Res(R.string.select_server_first), effect.text)
        job.cancel()
    }

    @Test
    fun `selection with same config but different ip emits stop when connected`() = runTest {
        val viewModel = createViewModel(
            selectionInteractor = FakeMainSelectionInteractor(
                initialSelection = InitialSelection(
                    country = "Canada",
                    city = "A",
                    config = "shared-config",
                    countryCode = "CA",
                    ip = "1.1.1.1"
                )
            ),
            connectionState = ConnectionState.CONNECTED
        )

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.OnServerSelectionResult(
                SelectedServerResult(
                    country = "Canada",
                    countryCode = "CA",
                    city = "B",
                    config = "shared-config",
                    ip = "2.2.2.2"
                )
            )
        )
        advanceUntilIdle()

        assertTrue(effects.first() is MainEffect.StopVpn)
        assertEquals("2.2.2.2", viewModel.state.value.selectedServer?.ip)
        job.cancel()
    }

    @Test
    fun `foreground sync preserves pending user selection override`() = runTest {
        val viewModel = createViewModel(
            selectionInteractor = FakeMainSelectionInteractor(
                initialSelection = InitialSelection(
                    country = "France",
                    city = "Paris",
                    config = "config",
                    countryCode = "FR",
                    ip = "1.2.3.4"
                )
            )
        )

        viewModel.onAction(
            MainAction.OnServerSelectionResult(
                SelectedServerResult(
                    country = "Germany",
                    countryCode = "DE",
                    city = "Berlin",
                    config = "user-config",
                    ip = "8.8.8.8"
                )
            )
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.pendingUserSelectionOverride)
        assertEquals("user-config", viewModel.state.value.selectedServer?.config)

        viewModel.onAction(MainAction.SyncServersForForeground)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.pendingUserSelectionOverride)
        assertEquals("user-config", viewModel.state.value.selectedServer?.config)
    }

    @Test
    fun `server menu and successful selection reopens drawer and applies user config`() = runTest {
        val viewModel = createViewModel(connectionState = ConnectionState.CONNECTED)

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(4).toList(effects)
        }

        viewModel.onAction(MainAction.NavigationItemSelected(R.id.nav_server))
        viewModel.onAction(
            MainAction.OnServerSelectionResult(
                SelectedServerResult(
                    country = "Germany",
                    countryCode = "DE",
                    city = "Berlin",
                    config = "cfg",
                    ip = "8.8.8.8"
                )
            )
        )
        advanceUntilIdle()

        assertTrue(effects.any { it is MainEffect.OpenDestination && it.destination == MainDestination.ServerList })
        assertTrue(effects.any { it is MainEffect.CloseDrawer })
        assertTrue(effects.any { it is MainEffect.ReopenDrawer })
        assertEquals(true, viewModel.state.value.selectedServer?.fromUserSelection)
        job.cancel()
    }

    @Test
    fun `multi window mode updates details visibility in state`() = runTest {
        val viewModel = createViewModel()

        viewModel.onAction(MainAction.OnMultiWindowModeChanged(isInMultiWindowMode = true))
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isDetailsVisible)

        viewModel.onAction(MainAction.OnMultiWindowModeChanged(isInMultiWindowMode = false))
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isDetailsVisible)
    }

    @Test
    fun `connection click with missing notification permission emits request`() = runTest {
        val viewModel = createViewModel(
            selectionInteractor = FakeMainSelectionInteractor(
                initialSelection = InitialSelection(
                    country = "France",
                    city = "Paris",
                    config = "config",
                    countryCode = "FR",
                    ip = "1.2.3.4"
                )
            )
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = false,
                hasVpnPermission = true
            )
        )
        advanceUntilIdle()

        assertTrue(effects.first() is MainEffect.RequestNotificationPermission)
        job.cancel()
    }

    @Test
    fun `connection click with missing vpn permission emits request`() = runTest {
        val viewModel = createViewModel(
            selectionInteractor = FakeMainSelectionInteractor(
                initialSelection = InitialSelection(
                    country = "France",
                    city = "Paris",
                    config = "config",
                    countryCode = "FR",
                    ip = "1.2.3.4"
                )
            )
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = true,
                hasVpnPermission = false
            )
        )
        advanceUntilIdle()

        assertTrue(effects.first() is MainEffect.RequestVpnPermission)
        job.cancel()
    }

    @Test
    fun `connection click with selected server and permissions emits start vpn`() = runTest {
        val connectionInteractor = FakeMainConnectionInteractor()
        val viewModel = createViewModel(
            selectionInteractor = FakeMainSelectionInteractor(
                initialSelection = InitialSelection(
                    country = "France",
                    city = "Paris",
                    config = "config",
                    countryCode = "FR",
                    ip = "1.2.3.4"
                )
            ),
            connectionInteractor = connectionInteractor,
            connectionState = ConnectionState.DISCONNECTED
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = true,
                hasVpnPermission = true
            )
        )
        advanceUntilIdle()

        val effect = effects.first() as MainEffect.StartVpn
        assertEquals("config", effect.config)
        assertEquals("France", effect.country)
        assertTrue(connectionInteractor.prepareCalled)
        job.cancel()
    }

    @Test
    fun `connection click when connected emits stop vpn`() = runTest {
        val viewModel = createViewModel(connectionState = ConnectionState.CONNECTED)

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = true,
                hasVpnPermission = true
            )
        )
        advanceUntilIdle()

        assertTrue(effects.first() is MainEffect.StopVpn)
        job.cancel()
    }

    @Test
    fun `update without asset is hidden and nav update does not emit install`() = runTest {
        val viewModel = createViewModel(
            updateCheckInteractor = FakeUpdateCheckInteractor(latest = sampleUpdate(asset = null))
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        assertNull(viewModel.state.value.availableUpdate)

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(MainAction.NavigationItemSelected(R.id.nav_update))
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects.first() is MainEffect.CloseDrawer)
        job.cancel()
    }

    @Test
    fun `prompt update is emitted only once per view model`() = runTest {
        val updateInteractor = FakeUpdateCheckInteractor(latest = sampleUpdate())
        val viewModel = createViewModel(updateCheckInteractor = updateInteractor)

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(2).toList(effects)
        }

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()
        viewModel.onAction(MainAction.RefreshUpdateAvailability)
        advanceUntilIdle()

        val promptEffects = effects.filterIsInstance<MainEffect.PromptUpdate>()
        assertEquals(1, promptEffects.size)
        assertTrue(promptEffects.first().oneTimeOnly)
        assertEquals(2, updateInteractor.callCount)
        job.cancel()
    }

    @Test
    fun `unknown navigation emits feature toast`() = runTest {
        val viewModel = createViewModel()
        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(2).toList(effects)
        }

        viewModel.onAction(MainAction.NavigationItemSelected(-1))
        advanceUntilIdle()

        assertTrue(
            effects.any {
                it is MainEffect.ShowToast && it.text == UiText.Res(R.string.feature_in_development)
            }
        )
        assertTrue(effects.any { it is MainEffect.CloseDrawer })
        job.cancel()
    }

    @Test
    fun `on server selection without reopen drawer requests focus`() = runTest {
        val viewModel = createViewModel()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.OnServerSelectionResult(
                SelectedServerResult(
                    country = "Canada",
                    countryCode = "CA",
                    city = "Toronto",
                    config = "cfg",
                    ip = "3.3.3.3"
                )
            )
        )
        advanceUntilIdle()

        assertTrue(effects.first() is MainEffect.RequestPrimaryFocus)
        assertFalse(viewModel.state.value.reopenDrawerAfterReturn)
        job.cancel()
    }

    private fun createViewModel(
        selectionInteractor: MainSelectionInteractor = FakeMainSelectionInteractor(),
        serverSyncCoordinator: ServerSelectionSyncCoordinator = FakeServerSelectionSyncCoordinator(),
        versionReleaseInteractor: VersionReleaseInteractor = FakeVersionReleaseInteractor(),
        updateCheckInteractor: UpdateCheckInteractor = FakeUpdateCheckInteractor(),
        connectionInteractor: MainConnectionInteractor = FakeMainConnectionInteractor(),
        connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        logger: MainLogger = FakeMainLogger()
    ): MainViewModel {
        return MainViewModel(
            selectionInteractor = selectionInteractor,
            serverSyncCoordinator = serverSyncCoordinator,
            versionReleaseInteractor = versionReleaseInteractor,
            updateCheckInteractor = updateCheckInteractor,
            connectionInteractor = connectionInteractor,
            connectionStateProvider = FakeConnectionProvider(connectionState),
            logger = logger
        )
    }

    private class FakeMainSelectionInteractor(
        private val initialSelection: InitialSelection? = null
    ) : MainSelectionInteractor {
        var lastCacheOnly: Boolean? = null

        override suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection? {
            lastCacheOnly = cacheOnly
            return initialSelection
        }
    }

    private class FakeVersionReleaseInteractor(
        private val latest: LatestReleaseInfo? = null
    ) : VersionReleaseInteractor {
        var callCount: Int = 0

        override suspend fun loadLatestRelease(): LatestReleaseInfo? {
            callCount += 1
            return latest
        }
    }

    private class FakeUpdateCheckInteractor(
        private val latest: AppUpdateInfo? = null
    ) : UpdateCheckInteractor {
        var callCount: Int = 0

        override suspend fun check(forceRefresh: Boolean): AppUpdateInfo? {
            callCount += 1
            return latest
        }
    }

    private class FakeConnectionProvider(initial: ConnectionState) : VpnConnectionStateProvider {
        private val stateFlow = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = stateFlow
        override fun isConnected(): Boolean = stateFlow.value == ConnectionState.CONNECTED
    }

    private class FakeMainConnectionInteractor : MainConnectionInteractor {
        var prepareCalled: Boolean = false

        override fun prepareStart(
            selectedServer: MainSelectedServer?,
            preferUserSelection: Boolean
        ): PreparedConnectionStart? {
            prepareCalled = true
            return selectedServer?.let {
                PreparedConnectionStart(
                    config = it.config,
                    country = it.country,
                    ip = it.ip
                )
            }
        }

        override fun shouldStopForUserSelection(
            state: ConnectionState,
            previousConfig: String?,
            newConfig: String?,
            previousIp: String?,
            newIp: String?
        ): Boolean {
            val isVpnActive = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
            if (!isVpnActive) return false

            val configChanged = !previousConfig.isNullOrBlank() &&
                !newConfig.isNullOrBlank() &&
                previousConfig != newConfig
            val ipChanged = previousIp != newIp
            return configChanged || ipChanged
        }
    }

    private class FakeMainLogger : MainLogger {
        override fun logInitialSelectionLoaded(selection: InitialSelection) = Unit
        override fun logInitialSelectionError(error: Exception) = Unit
        override fun logWhatsNewLoaded(release: MainWhatsNew) = Unit
        override fun logWhatsNewUnavailable() = Unit
        override fun logWhatsNewLoadError(error: Exception) = Unit
        override fun logUpdateLoaded(update: MainAvailableUpdate) = Unit
        override fun logUpdateUnavailable() = Unit
        override fun logUpdateLoadError(error: Exception) = Unit
        override fun logServerSelectionApplied(selection: SelectedServerResult) = Unit
        override fun logIncompleteServerSelection(selection: SelectedServerResult) = Unit
    }

    private class FakeServerSelectionSyncCoordinator : ServerSelectionSyncCoordinator {
        var callCount: Int = 0
        var lastForceRefresh: Boolean? = null

        override suspend fun sync(
            forceRefresh: Boolean,
            cacheOnly: Boolean,
            clearCacheBeforeRefresh: Boolean
        ): List<Server> {
            callCount += 1
            lastForceRefresh = forceRefresh
            return emptyList()
        }
    }

    private fun sampleRelease() = LatestReleaseInfo(
        versionNumber = "1.0.0",
        name = "Current release",
        changelog = "## Added\n- Item",
        resolvedLocale = "en"
    )

    private fun sampleUpdate(asset: AppUpdateAsset? = sampleAsset()) = AppUpdateInfo(
        hasUpdate = true,
        currentBuild = 100,
        latestBuild = 123,
        latestVersion = "1.2.3",
        name = "Release 1.2.3",
        changelog = "## Added\n- item",
        resolvedLocale = "en",
        message = "Update available.",
        asset = asset
    )

    private fun sampleAsset() = AppUpdateAsset(
            id = 1,
            name = "mobile.apk",
            platform = "mobile",
            buildNumber = 123L,
            assetType = "apk-mobile",
            sizeBytes = 100L,
            contentHash = "hash",
            downloadProxyUrl = "https://example.com/api/v1/download-assets/1/1"
        )
}

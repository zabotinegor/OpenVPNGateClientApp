package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.SettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshFeatureFlags
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshScheduler
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `init loads settings into state`() = runTest {
        val initial = UserSettings(
            language = LanguageOption.RUSSIAN,
            theme = ThemeOption.DARK,
            serverSource = ServerSource.VPNGATE,
            customServerUrl = "https://custom.example",
            cacheTtlMs = 10 * 60 * 1000L,
            autoSwitchWithinCountry = false,
            statusStallTimeoutSeconds = 7
        )
        val repo = FakeSettingsRepository(initial)
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(initial.language, state.language)
        assertEquals(initial.theme, state.theme)
        assertEquals(initial.serverSource, state.serverSource)
        assertEquals(initial.customServerUrl, state.customServerUrl)
        assertEquals(initial.cacheTtlMs, state.cacheTtlMs)
        assertEquals(initial.autoSwitchWithinCountry, state.autoSwitchWithinCountry)
        assertEquals(initial.statusStallTimeoutSeconds, state.statusStallTimeoutSeconds)
        assertEquals(initial.language, logger.opened?.language)
    }

    @Test
    fun `language change saves and emits effects`() = runTest {
        val repo = FakeSettingsRepository(UserSettings())
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        val effects = mutableListOf<SettingsEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.effects.take(2).toList(effects)
        }

        vm.onAction(SettingsAction.SelectLanguage(LanguageOption.POLISH))
        advanceUntilIdle()

        assertEquals(LanguageOption.POLISH, vm.state.value.language)
        assertEquals(LanguageOption.POLISH, repo.savedLanguage)
        assertEquals(
            listOf(SettingsEffect.ApplyThemeAndLocale, SettingsEffect.StopControllerIfIdle),
            effects
        )
        job.cancel()
    }

    @Test
    fun `cache ttl input validates and saves minutes`() = runTest {
        val repo = FakeSettingsRepository(UserSettings())
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        vm.onAction(SettingsAction.SetCacheTtlInput("0"))
        advanceUntilIdle()
        assertEquals(UserSettings().cacheTtlMs, vm.state.value.cacheTtlMs)

        vm.onAction(SettingsAction.SetCacheTtlInput("25"))
        advanceUntilIdle()
        assertEquals(25 * 60 * 1000L, vm.state.value.cacheTtlMs)
        assertEquals(25 * 60 * 1000L, repo.savedCacheTtlMs)
    }

    @Test
    fun `cache ttl change triggers scheduler reschedule`() = runTest {
        val repo = FakeSettingsRepository(UserSettings())
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        assertEquals(0, scheduler.schedulePeriodicRefreshCallCount)

        vm.onAction(SettingsAction.SetCacheTtlInput("30"))
        advanceUntilIdle()

        assertEquals(1, scheduler.schedulePeriodicRefreshCallCount)
        assertEquals(30 * 60 * 1000L, repo.savedCacheTtlMs)
    }

    @Test
    fun `status timeout stores raw input when invalid`() = runTest {
        val repo = FakeSettingsRepository(UserSettings(statusStallTimeoutSeconds = 5))
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        vm.onAction(SettingsAction.SetStatusStallTimeoutInput("abc"))
        advanceUntilIdle()

        assertEquals("abc", vm.state.value.statusStallTimeoutInput)
        assertEquals(5, vm.state.value.statusStallTimeoutSeconds)
        assertEquals(null, repo.savedStatusStallTimeoutSeconds)
    }

    @Test
    fun `cache ttl stores raw input when invalid`() = runTest {
        val repo = FakeSettingsRepository(UserSettings(cacheTtlMs = 20 * 60 * 1000L))
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        vm.onAction(SettingsAction.SetCacheTtlInput("0"))
        advanceUntilIdle()

        assertEquals("0", vm.state.value.cacheTtlInput)
        assertEquals(20 * 60 * 1000L, vm.state.value.cacheTtlMs)
        assertEquals(null, repo.savedCacheTtlMs)
    }

    @Test
    fun `custom url keeps raw input and saves trimmed`() = runTest {
        val repo = FakeSettingsRepository(
            UserSettings(serverSource = ServerSource.CUSTOM, customServerUrl = "https://example.com")
        )
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        vm.onAction(SettingsAction.SetCustomServerUrl("https://example.com "))
        advanceUntilIdle()

        assertEquals("https://example.com ", vm.state.value.customServerUrl)
        assertEquals(null, repo.savedCustomServerUrl)

        vm.onAction(SettingsAction.SetCustomServerUrl("https://example.com/a "))
        advanceUntilIdle()

        assertEquals("https://example.com/a ", vm.state.value.customServerUrl)
        assertEquals("https://example.com/a", repo.savedCustomServerUrl)
        assertEquals(1, syncCoordinator.callCount)
    }

    @Test
    fun `server source change triggers forced sync without pre-clear`() = runTest {
        val repo = FakeSettingsRepository(UserSettings(serverSource = ServerSource.LEGACY))
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        vm.onAction(SettingsAction.SelectServerSource(ServerSource.VPNGATE))
        advanceUntilIdle()

        assertEquals(1, syncCoordinator.callCount)
        assertEquals(true, syncCoordinator.lastForceRefresh)
        assertEquals(false, syncCoordinator.lastClearCacheBeforeRefresh)
    }

    @Test
    fun `server source change uses cache only when vpn is connected`() = runTest {
        val repo = FakeSettingsRepository(UserSettings(serverSource = ServerSource.LEGACY))
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(
            repo,
            logger,
            scheduler,
            syncCoordinator,
            FakeConnectionProvider(ConnectionState.CONNECTED)
        )
        advanceUntilIdle()

        vm.onAction(SettingsAction.SelectServerSource(ServerSource.VPNGATE))
        advanceUntilIdle()

        assertEquals(1, syncCoordinator.callCount)
        assertEquals(
            ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(true),
            syncCoordinator.lastCacheOnly
        )
    }

    @Test
    fun `language change triggers relocalization for DEFAULT_V2`() = runTest {
        val repo = FakeSettingsRepository(
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.DEFAULT_V2
            )
        )
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        assertEquals(0, syncCoordinator.relocalizationCallCount)

        vm.onAction(SettingsAction.SelectLanguage(LanguageOption.RUSSIAN))

        // Default launch schedules work; relocalization runs after dispatcher advances.
        assertEquals(0, syncCoordinator.relocalizationCallCount)

        advanceUntilIdle()

        assertEquals(1, syncCoordinator.relocalizationCallCount)
    }

    @Test
    fun `language change does not trigger relocalization for non-DEFAULT_V2 sources`() = runTest {
        val repo = FakeSettingsRepository(
            UserSettings(
                language = LanguageOption.ENGLISH,
                serverSource = ServerSource.LEGACY
            )
        )
        val logger = FakeSettingsLogger()
        val scheduler = FakeServerRefreshScheduler()
        val syncCoordinator = FakeServerSelectionSyncCoordinator()
        val vm = SettingsViewModel(repo, logger, scheduler, syncCoordinator, FakeConnectionProvider())
        advanceUntilIdle()

        assertEquals(0, syncCoordinator.relocalizationCallCount)

        vm.onAction(SettingsAction.SelectLanguage(LanguageOption.RUSSIAN))
        advanceUntilIdle()

        assertEquals(0, syncCoordinator.relocalizationCallCount)
    }

    private class FakeSettingsRepository(initial: UserSettings) : SettingsRepository {
        private var stored: UserSettings = initial

        var savedLanguage: LanguageOption? = null
        var savedTheme: ThemeOption? = null
        var savedServerSource: ServerSource? = null
        var savedCustomServerUrl: String? = null
        var savedCacheTtlMs: Long? = null
        var savedAutoSwitchWithinCountry: Boolean? = null
        var savedStatusStallTimeoutSeconds: Int? = null

        override fun load(): UserSettings = stored

        override fun saveLanguage(language: LanguageOption) {
            stored = stored.copy(language = language)
            savedLanguage = language
        }

        override fun saveTheme(theme: ThemeOption) {
            stored = stored.copy(theme = theme)
            savedTheme = theme
        }

        override fun saveServerSource(source: ServerSource) {
            stored = stored.copy(serverSource = source)
            savedServerSource = source
        }

        override fun saveCustomServerUrl(url: String) {
            stored = stored.copy(customServerUrl = url)
            savedCustomServerUrl = url
        }

        override fun saveCacheTtlMs(ttlMs: Long) {
            stored = stored.copy(cacheTtlMs = ttlMs)
            savedCacheTtlMs = ttlMs
        }

        override fun saveAutoSwitchWithinCountry(enabled: Boolean) {
            stored = stored.copy(autoSwitchWithinCountry = enabled)
            savedAutoSwitchWithinCountry = enabled
        }

        override fun saveStatusStallTimeoutSeconds(seconds: Int) {
            stored = stored.copy(statusStallTimeoutSeconds = seconds)
            savedStatusStallTimeoutSeconds = seconds
        }
    }

    private class FakeSettingsLogger : SettingsLogger {
        var opened: SettingsUiState? = null
        var languageChanges: Pair<LanguageOption, LanguageOption>? = null
        var themeChanges: Pair<ThemeOption, ThemeOption>? = null
        var serverSourceChanges: Pair<ServerSource, ServerSource>? = null
        var customUrl: String? = null
        var autoSwitch: Boolean? = null
        var statusTimeout: Int? = null
        var cacheTtlMs: Long? = null

        override fun logScreenOpened(state: SettingsUiState) {
            opened = state
        }

        override fun logLanguageChanged(old: LanguageOption, selected: LanguageOption) {
            languageChanges = old to selected
        }

        override fun logThemeChanged(old: ThemeOption, selected: ThemeOption) {
            themeChanges = old to selected
        }

        override fun logServerSourceChanged(old: ServerSource, selected: ServerSource) {
            serverSourceChanges = old to selected
        }

        override fun logCustomServerUrlChanged(value: String) {
            customUrl = value
        }

        override fun logAutoSwitchChanged(enabled: Boolean) {
            autoSwitch = enabled
        }

        override fun logStatusStallTimeoutChanged(seconds: Int) {
            statusTimeout = seconds
        }

        override fun logCacheTtlChanged(ttlMs: Long) {
            cacheTtlMs = ttlMs
        }
    }

    private class FakeServerRefreshScheduler : ServerRefreshScheduler {
        var schedulePeriodicRefreshCallCount = 0

        override fun schedulePeriodicRefresh() {
            schedulePeriodicRefreshCallCount++
        }
    }

    private class FakeServerSelectionSyncCoordinator : ServerSelectionSyncCoordinator {
        var callCount = 0
        var lastForceRefresh: Boolean? = null
        var lastCacheOnly: Boolean? = null
        var lastClearCacheBeforeRefresh: Boolean? = null

        override suspend fun sync(
            forceRefresh: Boolean,
            cacheOnly: Boolean,
            clearCacheBeforeRefresh: Boolean
        ): List<Server> {
            callCount += 1
            lastForceRefresh = forceRefresh
            lastCacheOnly = cacheOnly
            lastClearCacheBeforeRefresh = clearCacheBeforeRefresh
            return emptyList()
        }

        var relocalizationCallCount = 0

        override suspend fun syncSelectedCountryServersForRelocalization(
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ) {
            relocalizationCallCount += 1
        }
    }

    private class FakeConnectionProvider(initial: ConnectionState = ConnectionState.DISCONNECTED) : VpnConnectionStateProvider {
        private val flow = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = flow
        override fun isConnected(): Boolean = flow.value == ConnectionState.CONNECTED
    }
}

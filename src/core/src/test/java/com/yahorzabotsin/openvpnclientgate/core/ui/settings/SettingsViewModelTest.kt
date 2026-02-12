package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.SettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val vm = SettingsViewModel(repo, logger)
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
        val vm = SettingsViewModel(repo, logger)
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
        val vm = SettingsViewModel(repo, logger)
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
    fun `status timeout stores raw input when invalid`() = runTest {
        val repo = FakeSettingsRepository(UserSettings(statusStallTimeoutSeconds = 5))
        val logger = FakeSettingsLogger()
        val vm = SettingsViewModel(repo, logger)
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
        val vm = SettingsViewModel(repo, logger)
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
        val vm = SettingsViewModel(repo, logger)
        advanceUntilIdle()

        vm.onAction(SettingsAction.SetCustomServerUrl("https://example.com "))
        advanceUntilIdle()

        assertEquals("https://example.com ", vm.state.value.customServerUrl)
        assertEquals(null, repo.savedCustomServerUrl)

        vm.onAction(SettingsAction.SetCustomServerUrl("https://example.com/a "))
        advanceUntilIdle()

        assertEquals("https://example.com/a ", vm.state.value.customServerUrl)
        assertEquals("https://example.com/a", repo.savedCustomServerUrl)
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
}

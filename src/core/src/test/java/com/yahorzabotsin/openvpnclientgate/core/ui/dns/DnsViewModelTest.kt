package com.yahorzabotsin.openvpnclientgate.core.ui.dns

import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `init loads items and emits focus effect`() = runTest {
        val repo = FakeDnsSettingsRepository(DnsOption.SERVER)
        val logger = FakeDnsLogger()
        val vm = DnsViewModel(repo, logger)

        val effects = mutableListOf<DnsEffect>()
        val job = launch { vm.effects.take(1).toList(effects) }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(DnsOptions.providers.size, state.items.size)
        assertEquals(DnsOption.SERVER, state.selectedOption)
        assertTrue(effects.first() is DnsEffect.FocusSelected)
        assertEquals(DnsOption.SERVER, (effects.first() as DnsEffect.FocusSelected).option)
        job.cancel()
    }

    @Test
    fun `select option updates state and persists`() = runTest {
        val repo = FakeDnsSettingsRepository(DnsOption.SERVER)
        val logger = FakeDnsLogger()
        val vm = DnsViewModel(repo, logger)
        advanceUntilIdle()

        val option = DnsOptions.providers.first().option
        vm.onAction(DnsAction.SelectOption(option))

        assertEquals(option, vm.state.value.selectedOption)
        assertEquals(option, repo.saved.last())
        assertEquals(option, logger.lastSelection?.selected)
        assertEquals(DnsOption.SERVER, logger.lastSelection?.old)
    }

    private class FakeDnsSettingsRepository(initial: DnsOption) : DnsSettingsRepository {
        private var stored: DnsOption = initial
        val saved: MutableList<DnsOption> = mutableListOf()

        override fun loadDnsOption(): DnsOption = stored

        override fun saveDnsOption(option: DnsOption) {
            stored = option
            saved.add(option)
        }
    }

    private class FakeDnsLogger : DnsLogger {
        data class Selection(val old: DnsOption, val selected: DnsOption, val label: String)
        var lastSelection: Selection? = null
        var lastOpened: Pair<Int, DnsOption>? = null

        override fun logScreenOpened(providersCount: Int, currentOption: DnsOption) {
            lastOpened = providersCount to currentOption
        }

        override fun logSelectionChanged(old: DnsOption, selected: DnsOption, label: String) {
            lastSelection = Selection(old, selected, label)
        }
    }
}

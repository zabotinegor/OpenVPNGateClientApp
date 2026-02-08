package com.yahorzabotsin.openvpnclientgate.core.ui.filter

import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterEntry
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterRepository
import com.yahorzabotsin.openvpnclientgate.core.ui.AppCategory
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `init loads apps cleans excluded and builds items`() = runTest {
        val apps = listOf(
            AppFilterEntry("com.sys", "System", true),
            AppFilterEntry("com.user1", "alpha", false),
            AppFilterEntry("com.user2", "Beta", false)
        )
        val repo = FakeAppFilterRepository(apps, setOf("com.sys", "missing"))
        val logger = FakeFilterLogger()
        val vm = FilterViewModel(repo, logger)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(repo.loadCalled)
        assertEquals(AppCategory.USER, state.currentCategory)
        assertEquals(setOf("com.sys"), repo.saved.last())
        assertEquals(2, logger.opened?.first)
        assertEquals(1, logger.opened?.second)
        assertEquals(1, logger.opened?.third)

        val userItems = state.itemsByCategory[AppCategory.USER].orEmpty()
        assertTrue(userItems.first() is FilterUiItem.SelectAll)
        val userApps = userItems.filterIsInstance<FilterUiItem.App>()
        assertEquals(listOf("com.user1", "com.user2"), userApps.map { it.packageName })
        assertTrue(userApps.all { it.isEnabled })
        val userSelectAll = userItems.first() as FilterUiItem.SelectAll
        assertTrue(userSelectAll.isChecked)
        assertTrue(userSelectAll.isEnabled)

        val systemItems = state.itemsByCategory[AppCategory.SYSTEM].orEmpty()
        val systemApps = systemItems.filterIsInstance<FilterUiItem.App>()
        assertEquals(listOf("com.sys"), systemApps.map { it.packageName })
        assertTrue(systemApps.all { !it.isEnabled })
        val systemSelectAll = systemItems.first() as FilterUiItem.SelectAll
        assertTrue(!systemSelectAll.isChecked)
        assertTrue(systemSelectAll.isEnabled)
    }

    @Test
    fun `toggle app updates excluded and state`() = runTest {
        val apps = listOf(
            AppFilterEntry("com.user1", "Alpha", false)
        )
        val repo = FakeAppFilterRepository(apps, emptySet())
        val logger = FakeFilterLogger()
        val vm = FilterViewModel(repo, logger)
        advanceUntilIdle()

        vm.onAction(FilterAction.ToggleApp("com.user1", false))

        assertEquals(setOf("com.user1"), repo.updated.last())
        val userItems = vm.state.value.itemsByCategory[AppCategory.USER].orEmpty()
        val app = userItems.filterIsInstance<FilterUiItem.App>().first()
        assertTrue(!app.isEnabled)
        assertEquals("com.user1", logger.toggles.last().first)
        assertEquals(false, logger.toggles.last().second)
    }

    @Test
    fun `select all disables and enables category apps`() = runTest {
        val apps = listOf(
            AppFilterEntry("com.user1", "Alpha", false),
            AppFilterEntry("com.user2", "Beta", false),
            AppFilterEntry("com.sys", "System", true)
        )
        val repo = FakeAppFilterRepository(apps, setOf("com.sys"))
        val logger = FakeFilterLogger()
        val vm = FilterViewModel(repo, logger)
        advanceUntilIdle()

        vm.onAction(FilterAction.SelectAll(AppCategory.USER, false))
        assertEquals(setOf("com.sys", "com.user1", "com.user2"), repo.updated.last())
        val userItemsDisabled = vm.state.value.itemsByCategory[AppCategory.USER].orEmpty()
        assertTrue(userItemsDisabled.filterIsInstance<FilterUiItem.App>().all { !it.isEnabled })
        assertEquals(AppCategory.USER, logger.selectAlls.last().first)
        assertEquals(false, logger.selectAlls.last().second)
        assertEquals(2, logger.selectAlls.last().third)

        vm.onAction(FilterAction.SelectAll(AppCategory.USER, true))
        assertEquals(setOf("com.sys"), repo.updated.last())
        val userItemsEnabled = vm.state.value.itemsByCategory[AppCategory.USER].orEmpty()
        assertTrue(userItemsEnabled.filterIsInstance<FilterUiItem.App>().all { it.isEnabled })
        assertEquals(AppCategory.USER, logger.selectAlls.last().first)
        assertEquals(true, logger.selectAlls.last().second)
        assertEquals(2, logger.selectAlls.last().third)
    }

    private class FakeAppFilterRepository(
        private val apps: List<AppFilterEntry>,
        initialExcluded: Set<String>
    ) : AppFilterRepository {
        private var excluded = initialExcluded.toMutableSet()
        val saved: MutableList<Set<String>> = mutableListOf()
        val updated: MutableList<Set<String>> = mutableListOf()
        var loadCalled: Boolean = false

        override suspend fun loadInstalledApps(): List<AppFilterEntry> {
            loadCalled = true
            return apps
        }

        override fun loadExcludedPackages(): Set<String> = excluded.toSet()

        override fun saveExcludedPackages(packages: Set<String>) {
            excluded = packages.toMutableSet()
            saved.add(excluded.toSet())
        }

        override fun updateExcludedPackages(update: (MutableSet<String>) -> Unit): Set<String> {
            val mutable = excluded.toMutableSet()
            update(mutable)
            excluded = mutable
            updated.add(excluded.toSet())
            return excluded.toSet()
        }
    }

    private class FakeFilterLogger : FilterLogger {
        var opened: Triple<Int, Int, Int>? = null
        val selectAlls: MutableList<Triple<AppCategory, Boolean, Int>> = mutableListOf()
        val toggles: MutableList<Pair<String, Boolean>> = mutableListOf()

        override fun logScreenOpened(userCount: Int, systemCount: Int, excludedCount: Int) {
            opened = Triple(userCount, systemCount, excludedCount)
        }

        override fun logSelectAll(category: AppCategory, isChecked: Boolean, affectedCount: Int) {
            selectAlls.add(Triple(category, isChecked, affectedCount))
        }

        override fun logToggle(packageName: String, isEnabled: Boolean) {
            toggles.add(packageName to isEnabled)
        }
    }
}

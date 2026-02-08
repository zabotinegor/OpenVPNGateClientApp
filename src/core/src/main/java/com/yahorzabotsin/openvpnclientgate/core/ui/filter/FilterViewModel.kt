package com.yahorzabotsin.openvpnclientgate.core.ui.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterEntry
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterRepository
import com.yahorzabotsin.openvpnclientgate.core.ui.AppCategory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FilterViewModel(
    private val repository: AppFilterRepository,
    private val logger: FilterLogger
) : ViewModel() {

    private val _state = MutableStateFlow(FilterUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FilterEffect>()
    val effects = _effects.asSharedFlow()

    private var allApps: List<AppFilterEntry> = emptyList()
    private var excludedPackages: Set<String> = emptySet()

    init {
        loadApps()
    }

    fun onAction(action: FilterAction) {
        when (action) {
            is FilterAction.SelectCategory -> selectCategory(action.category)
            is FilterAction.SelectAll -> applySelectAll(action.category, action.isChecked)
            is FilterAction.ToggleApp -> onAppToggleChanged(action.packageName, action.isEnabled)
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            setLoading(true)
            val installedApps = repository.loadInstalledApps()
            val installedPackageNames = installedApps.map { it.packageName }.toSet()

            var storedExcluded = repository.loadExcludedPackages()
            val cleaned = storedExcluded.intersect(installedPackageNames)
            if (cleaned.size != storedExcluded.size) {
                repository.saveExcludedPackages(cleaned)
                storedExcluded = cleaned
            }

            allApps = installedApps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            excludedPackages = storedExcluded
            updateItems()
            val userCount = allApps.count { !it.isSystemApp }
            val systemCount = allApps.count { it.isSystemApp }
            logger.logScreenOpened(userCount, systemCount, excludedPackages.size)
            setLoading(false)
        }
    }

    private fun updateItems() {
        val itemsByCategory = mapOf(
            AppCategory.USER to itemsFor(AppCategory.USER),
            AppCategory.SYSTEM to itemsFor(AppCategory.SYSTEM)
        )
        _state.value = _state.value.copy(itemsByCategory = itemsByCategory)
    }

    private fun itemsFor(category: AppCategory): List<FilterUiItem> {
        val appEntries = allApps.filter { it.isSystemApp == (category == AppCategory.SYSTEM) }
        val appItems = appEntries.map {
            FilterUiItem.App(it.packageName, it.label, !excludedPackages.contains(it.packageName))
        }
        val allEnabled = appItems.isNotEmpty() && appItems.all { it.isEnabled }
        return buildList {
            add(FilterUiItem.SelectAll(allEnabled, appItems.isNotEmpty()))
            addAll(appItems)
        }
    }

    private fun selectCategory(category: AppCategory) {
        if (_state.value.currentCategory == category) return
        _state.value = _state.value.copy(currentCategory = category)
    }

    private fun onAppToggleChanged(packageName: String, isEnabled: Boolean) {
        excludedPackages = repository.updateExcludedPackages { set ->
            if (isEnabled) set.remove(packageName) else set.add(packageName)
        }
        logger.logToggle(packageName, isEnabled)
        updateItems()
    }

    private fun applySelectAll(category: AppCategory, isChecked: Boolean) {
        _state.value = _state.value.copy(currentCategory = category)
        val targetPackages = currentItems(category).map { it.packageName }
        excludedPackages = repository.updateExcludedPackages { set ->
            if (isChecked) {
                set.removeAll(targetPackages.toSet())
            } else {
                set.addAll(targetPackages)
            }
        }
        logger.logSelectAll(category, isChecked, targetPackages.size)
        updateItems()
    }

    private fun currentItems(category: AppCategory): List<AppFilterEntry> = allApps.filter {
        it.isSystemApp == (category == AppCategory.SYSTEM)
    }

    private fun setLoading(loading: Boolean) {
        _state.value = _state.value.copy(isLoading = loading)
    }
}

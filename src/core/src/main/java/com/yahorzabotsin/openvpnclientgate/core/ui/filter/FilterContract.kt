package com.yahorzabotsin.openvpnclientgate.core.ui.filter

data class FilterUiState(
    val isLoading: Boolean = false,
    val currentCategory: AppCategory = AppCategory.USER,
    val itemsByCategory: Map<AppCategory, List<FilterUiItem>> = emptyMap()
)

sealed class FilterUiItem {
    data class SelectAll(val isChecked: Boolean, val isEnabled: Boolean) : FilterUiItem()
    data class App(val packageName: String, val label: String, val isEnabled: Boolean) : FilterUiItem()
}

sealed class FilterAction {
    data class SelectCategory(val category: AppCategory) : FilterAction()
    data class SelectAll(val category: AppCategory, val isChecked: Boolean) : FilterAction()
    data class ToggleApp(val packageName: String, val isEnabled: Boolean) : FilterAction()
}

sealed class FilterEffect

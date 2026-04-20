package com.yahorzabotsin.openvpnclientgate.core.servers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SelectedCountryVersionSignal {
    private val versionState = MutableStateFlow(0L)
    val version: StateFlow<Long> = versionState.asStateFlow()

    fun bump() {
        versionState.value = versionState.value + 1L
    }
}

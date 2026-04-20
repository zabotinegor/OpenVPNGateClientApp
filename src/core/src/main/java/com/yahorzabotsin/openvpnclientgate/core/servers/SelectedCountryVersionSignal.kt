package com.yahorzabotsin.openvpnclientgate.core.servers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SelectedCountryVersionSignal {
    private val versionState = MutableStateFlow(0L)
    val version: StateFlow<Long> = versionState.asStateFlow()

    fun bump() {
        versionState.update { it + 1L }
    }
}

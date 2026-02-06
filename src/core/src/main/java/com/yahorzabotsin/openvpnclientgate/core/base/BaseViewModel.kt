package com.yahorzabotsin.openvpnclientgate.core.base

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

abstract class BaseViewModel<S : UiState, I : UiIntent>(initialState: S) : ViewModel() {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state

    abstract fun handleIntent(intent: I)
    protected fun updateState(reducer: (S) -> S) {
        _state.value = reducer(_state.value)
    }
}

package com.yahorzabotsin.openvpnclientgate.core.ui.common.text

sealed interface UiText {
    data class Res(val resId: Int, val args: List<Any> = emptyList()) : UiText
    data class Plain(val value: String) : UiText
}


package com.yahorzabotsin.openvpnclientgate.core.ui.common.text

import android.content.Context

fun Context.resolve(text: UiText): String = when (text) {
    is UiText.Plain -> text.value
    is UiText.Res -> getString(text.resId, *text.args.toTypedArray())
}

package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun LifecycleOwner.launchLogged(tag: String, block: suspend CoroutineScope.() -> Unit) {
    val handler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Coroutine failed", throwable)
    }
    lifecycleScope.launch(handler) {
        block()
    }
}


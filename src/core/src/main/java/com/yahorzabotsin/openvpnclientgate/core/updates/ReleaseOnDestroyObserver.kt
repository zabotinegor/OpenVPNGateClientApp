package com.yahorzabotsin.openvpnclientgate.core.updates

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Runs [release] once when the observed owner is destroyed. Used to close an Activity-backed
 * window synchronously during `onDestroy`; a coroutine cancelled by the same destruction
 * resumes only after the window manager has already reported the window as leaked.
 */
internal class ReleaseOnDestroyObserver(private val release: () -> Unit) : DefaultLifecycleObserver {
    private var released = false

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        if (released) return
        released = true
        release()
    }
}

package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import timber.log.Timber

class AppDebugTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: LogTags.APP
        Log.println(priority, resolvedTag, message)
        if (t != null) {
            Log.println(priority, resolvedTag, Log.getStackTraceString(t))
        }
    }
}

class AppReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.DEBUG || priority == Log.VERBOSE) return
        val resolvedTag = tag ?: LogTags.APP
        Log.println(priority, resolvedTag, message)
        if (t != null) {
            Log.println(priority, resolvedTag, Log.getStackTraceString(t))
        }
    }
}


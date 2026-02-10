package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import timber.log.Timber

class AppDebugTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: LogTags.APP
        val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
        Log.println(priority, resolvedTag, fullMessage)
    }
}

class AppReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.DEBUG || priority == Log.VERBOSE) return
        val resolvedTag = tag ?: LogTags.APP
        val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
        Log.println(priority, resolvedTag, fullMessage)
    }
}


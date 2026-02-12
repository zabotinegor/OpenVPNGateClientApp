package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import timber.log.Timber

class AppDebugTree(
    private val fileLogStore: AppFileLogStore? = null
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: LogTags.APP
        val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
        Log.println(priority, resolvedTag, fullMessage)
        fileLogStore?.write(priority, resolvedTag, fullMessage)
    }
}

class AppReleaseTree(
    private val fileLogStore: AppFileLogStore? = null
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: LogTags.APP
        val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
        fileLogStore?.write(priority, resolvedTag, fullMessage)
        if (priority == Log.DEBUG || priority == Log.VERBOSE) return
        Log.println(priority, resolvedTag, fullMessage)
    }
}


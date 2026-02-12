package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import timber.log.Timber

abstract class BaseAppTree(
    protected val persistentLogStore: AppFileLogStore?
) : Timber.Tree() {
    protected fun resolvedTag(tag: String?): String = tag ?: LogTags.APP

    protected fun fullMessage(message: String, t: Throwable?): String =
        if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
}

class AppDebugTree(
    fileLogStore: AppFileLogStore? = null
) : BaseAppTree(fileLogStore) {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = resolvedTag(tag)
        val fullMessage = fullMessage(message, t)
        Log.println(priority, resolvedTag, fullMessage)
        persistentLogStore?.write(priority, resolvedTag, fullMessage)
    }
}

class AppReleaseTree(
    fileLogStore: AppFileLogStore? = null
) : BaseAppTree(fileLogStore) {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = resolvedTag(tag)
        val fullMessage = fullMessage(message, t)
        persistentLogStore?.write(priority, resolvedTag, fullMessage)
        if (priority == Log.DEBUG || priority == Log.VERBOSE) return
        Log.println(priority, resolvedTag, fullMessage)
    }
}


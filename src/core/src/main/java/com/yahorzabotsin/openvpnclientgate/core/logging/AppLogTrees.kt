package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import timber.log.Timber
import java.net.URI

abstract class BaseAppTree(
    protected val persistentLogStore: AppFileLogStore?
) : Timber.Tree() {
    private val urlRegex = Regex("""https?://\S+""")

    protected fun resolvedTag(tag: String?): String = tag ?: LogTags.APP

    protected fun fullMessage(message: String, t: Throwable?): String =
        if (t == null) message else "$message\n${Log.getStackTraceString(t)}"

    protected fun redactSensitiveUrls(message: String): String =
        urlRegex.replace(message) { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ':')
            val trailing = match.value.removePrefix(candidate)
            val uri = runCatching { URI(candidate) }.getOrNull()
            val scheme = uri?.scheme
            val host = uri?.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
                "<redacted-url>$trailing"
            } else {
                val port = uri.port
                val redacted = if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
                "$redacted$trailing"
            }
        }
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
        if (priority >= Log.INFO) {
            persistentLogStore?.write(priority, resolvedTag, redactSensitiveUrls(fullMessage))
        }
        if (priority == Log.DEBUG || priority == Log.VERBOSE) return
        Log.println(priority, resolvedTag, fullMessage)
    }
}


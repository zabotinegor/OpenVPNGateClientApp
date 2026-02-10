package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

object AppLog {
    private const val DEFAULT_THROTTLE_WINDOW_MS = 30_000L
    private val throttledUntilMs = ConcurrentHashMap<String, Long>()
    private val throttledSuppressedCount = ConcurrentHashMap<String, Int>()
    @Volatile
    private var currentTimeMsProvider: () -> Long = { System.currentTimeMillis() }

    fun d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Log.INFO, tag, message, null)
    fun w(tag: String, message: String) = log(Log.WARN, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String, message: String) = log(Log.ERROR, tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable) = log(Log.ERROR, tag, message, throwable)

    fun dThrottled(
        tag: String,
        message: String,
        key: String = message,
        windowMs: Long = DEFAULT_THROTTLE_WINDOW_MS
    ) = logThrottled(Log.DEBUG, tag, message, key, windowMs, null)

    fun iThrottled(
        tag: String,
        message: String,
        key: String = message,
        windowMs: Long = DEFAULT_THROTTLE_WINDOW_MS
    ) = logThrottled(Log.INFO, tag, message, key, windowMs, null)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        if (Timber.forest().isEmpty()) {
            Log.println(priority, tag, message)
            if (throwable != null) {
                Log.println(priority, tag, Log.getStackTraceString(throwable))
            }
            return
        }
        if (throwable == null) {
            Timber.tag(tag).log(priority, message)
        } else {
            Timber.tag(tag).log(priority, throwable, message)
        }
    }

    private fun logThrottled(
        priority: Int,
        tag: String,
        message: String,
        key: String,
        windowMs: Long,
        throwable: Throwable?
    ) {
        val now = currentTimeMsProvider()
        val compositeKey = "$priority|$tag|$key"
        val allowedAfter = throttledUntilMs[compositeKey]
        if (allowedAfter != null && now < allowedAfter) {
            throttledSuppressedCount.merge(compositeKey, 1) { old, inc -> old + inc }
            return
        }

        val suppressed = throttledSuppressedCount.remove(compositeKey) ?: 0
        if (suppressed > 0) {
            log(
                priority = Log.INFO,
                tag = tag,
                message = "Suppressed $suppressed repeated logs for key=$key",
                throwable = null
            )
        }

        throttledUntilMs[compositeKey] = now + windowMs
        log(priority = priority, tag = tag, message = message, throwable = throwable)
    }

    internal fun setTimeProviderForTest(provider: () -> Long) {
        currentTimeMsProvider = provider
    }

    internal fun resetForTest() {
        throttledUntilMs.clear()
        throttledSuppressedCount.clear()
        currentTimeMsProvider = { System.currentTimeMillis() }
    }
}


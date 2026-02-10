package com.yahorzabotsin.openvpnclientgate.core.logging

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

object AppLog {
    private const val DEFAULT_THROTTLE_WINDOW_MS = 30_000L
    private const val CLEANUP_INTERVAL_OPS = 128
    private const val MAX_THROTTLED_KEYS = 4_096
    private val throttledUntilMs = ConcurrentHashMap<String, Long>()
    private val throttledSuppressedCount = ConcurrentHashMap<String, Int>()
    private val throttleOpsCount = AtomicInteger(0)
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
        maybeCleanup(now)
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
        trimToMaxSize()
        log(priority = priority, tag = tag, message = message, throwable = throwable)
    }

    private fun maybeCleanup(nowMs: Long) {
        if (throttleOpsCount.incrementAndGet() % CLEANUP_INTERVAL_OPS != 0) return
        cleanupExpired(nowMs)
        trimToMaxSize()
        trimSuppressedToMaxSize()
    }

    private fun cleanupExpired(nowMs: Long) {
        throttledUntilMs.entries.forEach { entry ->
            if (entry.value <= nowMs) {
                throttledUntilMs.remove(entry.key, entry.value)
            }
        }
    }

    private fun trimToMaxSize() {
        val size = throttledUntilMs.size
        if (size <= MAX_THROTTLED_KEYS) return
        val overflow = size - MAX_THROTTLED_KEYS
        val keysToEvict = throttledUntilMs.entries
            .asSequence()
            .sortedBy { it.value }
            .take(overflow)
            .map { it.key }
            .toList()
        keysToEvict.forEach { key ->
            throttledUntilMs.remove(key)
        }
    }

    private fun trimSuppressedToMaxSize() {
        val size = throttledSuppressedCount.size
        if (size <= MAX_THROTTLED_KEYS) return
        val overflow = size - MAX_THROTTLED_KEYS
        val iterator = throttledSuppressedCount.keys.iterator()
        repeat(overflow) {
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    internal fun setTimeProviderForTest(provider: () -> Long) {
        currentTimeMsProvider = provider
    }

    internal fun throttledKeyCountForTest(): Int = throttledUntilMs.size

    internal fun resetForTest() {
        throttledUntilMs.clear()
        throttledSuppressedCount.clear()
        throttleOpsCount.set(0)
        currentTimeMsProvider = { System.currentTimeMillis() }
    }
}


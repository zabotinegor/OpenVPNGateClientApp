package com.yahorzabotsin.openvpnclientgate.core.updates

/**
 * Conflates frequent download progress events into at most one queued UI callback.
 *
 * The network stream can report a new value for every buffer read, while the main thread
 * may be busy. Posting one Runnable per chunk causes a potentially unbounded queue of
 * callbacks retaining the Activity-backed dialog until the queue drains.
 *
 * [post] and [remove] must schedule and remove the same [Runnable] on the UI thread.
 * Only the most recent progress snapshot needs to be rendered.
 */
internal class LatestUpdateProgressDispatcher(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val render: (AppUpdateInstallProgress) -> Unit
) {
    private val lock = Any()
    private var latest: AppUpdateInstallProgress? = null
    private var scheduled = false
    private var closed = false

    private val renderLatest = Runnable {
        val snapshot = synchronized(lock) {
            scheduled = false
            if (closed) {
                latest = null
                null
            } else {
                latest.also { latest = null }
            }
        }
        if (snapshot != null) {
            synchronized(lock) {
                if (!closed) render(snapshot)
            }
        }
    }

    fun offer(progress: AppUpdateInstallProgress) {
        synchronized(lock) {
            if (closed) return
            latest = progress
            if (!scheduled) {
                scheduled = true
                post(renderLatest)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latest = null
            if (scheduled) {
                remove(renderLatest)
                scheduled = false
            }
        }
    }
}

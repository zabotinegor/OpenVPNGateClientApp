package com.yahorzabotsin.openvpnclientgate.core.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestUpdateProgressDispatcherTest {
    private class ManualQueue {
        val callbacks = mutableListOf<Runnable>()
        fun post(callback: Runnable) { callbacks.add(callback) }
        fun remove(callback: Runnable) { callbacks.remove(callback) }
        fun runNext() { callbacks.removeAt(0).run() }
    }

    @Test
    fun `large progress burst queues a single callback containing latest value`() {
        val queue = ManualQueue()
        val rendered = mutableListOf<AppUpdateInstallProgress>()
        val dispatcher = LatestUpdateProgressDispatcher(queue::post, queue::remove, rendered::add)

        repeat(10_000) { index ->
            dispatcher.offer(AppUpdateInstallProgress(index.toLong(), 10_000, index / 100))
        }

        assertEquals(1, queue.callbacks.size)
        assertTrue(rendered.isEmpty())
        queue.runNext()
        assertEquals(1, rendered.size)
        assertEquals(9_999L, rendered.single().downloadedBytes)
        assertTrue(queue.callbacks.isEmpty())
    }

    @Test
    fun `subsequent burst schedules a new callback after first render`() {
        val queue = ManualQueue()
        val rendered = mutableListOf<AppUpdateInstallProgress>()
        val dispatcher = LatestUpdateProgressDispatcher(queue::post, queue::remove, rendered::add)

        dispatcher.offer(AppUpdateInstallProgress(3, 20, 15))
        queue.runNext()
        dispatcher.offer(AppUpdateInstallProgress(20, 20, 100))
        queue.runNext()

        assertEquals(listOf(3L, 20L), rendered.map { it.downloadedBytes })
        assertEquals(0, queue.callbacks.size)
    }

    @Test
    fun `dismiss removes queued UI work and ignores future progress`() {
        val queue = ManualQueue()
        val rendered = mutableListOf<AppUpdateInstallProgress>()
        val dispatcher = LatestUpdateProgressDispatcher(queue::post, queue::remove, rendered::add)

        dispatcher.offer(AppUpdateInstallProgress(8, 16, 50))
        assertEquals(1, queue.callbacks.size)

        dispatcher.close()
        dispatcher.offer(AppUpdateInstallProgress(16, 16, 100))
        dispatcher.close()

        assertTrue(queue.callbacks.isEmpty())
        assertTrue(rendered.isEmpty())
    }

    @Test
    fun `already dispatched stale callback cannot render after dismissal`() {
        val queue = ManualQueue()
        val rendered = mutableListOf<AppUpdateInstallProgress>()
        val dispatcher = LatestUpdateProgressDispatcher(queue::post, queue::remove, rendered::add)

        dispatcher.offer(AppUpdateInstallProgress(4, 10, 40))
        val staleCallback = queue.callbacks.single()
        dispatcher.close()
        staleCallback.run()

        assertTrue(rendered.isEmpty())
        assertTrue(queue.callbacks.isEmpty())
    }
}

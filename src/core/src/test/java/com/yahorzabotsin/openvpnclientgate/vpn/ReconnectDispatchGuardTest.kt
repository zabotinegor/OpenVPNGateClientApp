package com.yahorzabotsin.openvpnclientgate.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Direct, plain-JVM unit tests for [ReconnectDispatchGuard]'s state transitions -- no Robolectric,
 * no Android context, no [OpenVpnService] instance required. It demonstrates that the
 * reconnect-dispatch state is now one explicit state class with unit-testable transitions,
 * verifiable in complete isolation from the surrounding service. [OpenVpnServiceReconnectEngineDispatchTest] continues to cover the
 * integration behavior (real `onStartCommand()`/AIDL callback wiring, log lines, production
 * call-site ordering); this file covers the guard's own contract.
 */
class ReconnectDispatchGuardTest {

    @Test
    fun freshGuard_startsIdleAtGenerationZero() {
        val guard = ReconnectDispatchGuard()

        assertEquals(0, guard.currentGeneration)
        assertEquals(ReconnectDispatchGuard.State.IDLE, guard.state)
        assertFalse(guard.isBufferPendingForCurrentGeneration())
    }

    @Test
    fun beginNewAttempt_incrementsMonotonically() {
        val guard = ReconnectDispatchGuard()

        val first = guard.beginNewAttempt()
        val second = guard.beginNewAttempt()
        val third = guard.beginNewAttempt()

        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(3, third)
        assertEquals(3, guard.currentGeneration)
    }

    @Test
    fun armPending_forCurrentGeneration_entersBufferPending() {
        val guard = ReconnectDispatchGuard()
        val generation = guard.beginNewAttempt()

        guard.armPending(generation)

        assertEquals(ReconnectDispatchGuard.State.BUFFER_PENDING, guard.state)
        assertTrue(guard.isBufferPendingForCurrentGeneration())
    }

    @Test
    fun armPending_forStaleGeneration_staysIdle() {
        val guard = ReconnectDispatchGuard()
        val staleGeneration = guard.beginNewAttempt()
        guard.beginNewAttempt() // supersede -- live generation is now ahead of staleGeneration

        guard.armPending(staleGeneration)

        assertEquals(
            "Arming a marker for a generation that is no longer live must not report BUFFER_PENDING " +
                "-- the marker only matters when it equals the CURRENT generation",
            ReconnectDispatchGuard.State.IDLE,
            guard.state
        )
    }

    @Test
    fun clearPendingIfOwnedBy_matchingGeneration_clearsToIdle() {
        val guard = ReconnectDispatchGuard()
        val generation = guard.beginNewAttempt()
        guard.armPending(generation)

        guard.clearPendingIfOwnedBy(generation)

        assertEquals(ReconnectDispatchGuard.State.IDLE, guard.state)
    }

    @Test
    fun clearPendingIfOwnedBy_nonMatchingGeneration_leavesMarkerIntact() {
        // Regression: a superseded (earlier) buffer's Runnable resolving first must not be
        // able to clear a still-pending NEWER buffer's marker.
        val guard = ReconnectDispatchGuard()
        val bufferA = guard.beginNewAttempt()
        val bufferB = guard.beginNewAttempt()
        guard.armPending(bufferB)

        guard.clearPendingIfOwnedBy(bufferA)

        assertEquals(
            "Clearing with a stale (superseded) generation must be a no-op -- the live marker " +
                "belongs to a newer buffer and only that buffer's own Runnable may clear it",
            ReconnectDispatchGuard.State.BUFFER_PENDING,
            guard.state
        )
        assertTrue(guard.isBufferPendingForCurrentGeneration())
    }

    @Test
    fun clearPendingIfOwnedBy_matchingGenerationAfterSupersession_isNoOpBecauseGenerationMoved() {
        // Complementary case: buffer A's own Runnable tries to clear AFTER a newer attempt has
        // begun. Even though bufferA legitimately owns the -1-worthy marker value it originally
        // armed, a fresh beginNewAttempt() already moved the live generation away from bufferA, so
        // state is IDLE regardless -- clearing is harmless either way. This pins that a supersession
        // alone (without any arm for the new generation) already reads as IDLE, matching production
        // callers that check the generation mismatch before ever calling clearPendingIfOwnedBy.
        val guard = ReconnectDispatchGuard()
        val bufferA = guard.beginNewAttempt()
        guard.armPending(bufferA)
        guard.beginNewAttempt() // a newer attempt begins; nothing armed for it yet

        assertEquals(ReconnectDispatchGuard.State.IDLE, guard.state)

        guard.clearPendingIfOwnedBy(bufferA)

        assertEquals(ReconnectDispatchGuard.State.IDLE, guard.state)
    }

    @Test
    fun generalizesToNArbitraryOverlappingBuffers_onlyNewestMarkerSurvivesUntilItResolves() {
        // The N>2 generalization, pinned as a direct unit test instead of only an analytic
        // argument: for N buffers posted in increasing generation order, the marker
        // set to the newest generation is the only one any earlier buffer's clear can fail to
        // disturb, and suppression stays continuously active until the newest resolves.
        val guard = ReconnectDispatchGuard()
        val generations = (1..5).map { guard.beginNewAttempt() }
        val newest = generations.last()
        guard.armPending(newest)

        // Every earlier buffer's Runnable resolves first and tries to clear its OWN (now-stale)
        // generation -- none of them may succeed in disturbing the newest buffer's marker.
        generations.dropLast(1).forEach { staleGeneration ->
            guard.clearPendingIfOwnedBy(staleGeneration)
            assertTrue(
                "Suppression must remain continuously active while any earlier buffer resolves",
                guard.isBufferPendingForCurrentGeneration()
            )
        }

        // Only the newest buffer's own Runnable can end the suppression window.
        guard.clearPendingIfOwnedBy(newest)
        assertFalse(guard.isBufferPendingForCurrentGeneration())
    }

    @Test
    fun captureTimeAndExecutionTimeChecksCanDisagree_closingTheEnqueuePointWindowClass() {
        // The structural fix: isBufferPendingForCurrentGeneration() is deliberately NOT
        // memoized, so a capture-time read taken before armPending() and an execution-time read
        // taken after it observe different answers for the exact same stray level -- this is what
        // lets the execution-time re-check catch what the capture-time check missed.
        val guard = ReconnectDispatchGuard()
        val generation = guard.beginNewAttempt()

        val capturedBeforeArm = guard.isBufferPendingForCurrentGeneration()
        guard.armPending(generation)
        val checkedAfterArm = guard.isBufferPendingForCurrentGeneration()

        assertFalse(
            "Capture-time read taken before the marker is armed must see IDLE -- this is the torn " +
                "read the enqueue-point window used to expose",
            capturedBeforeArm
        )
        assertTrue(
            "Execution-time read taken after the marker is armed for the SAME generation must see " +
                "BUFFER_PENDING -- the re-check that closes the window class",
            checkedAfterArm
        )
    }

    @Test
    fun beginNewAttempt_afterArm_immediatelySupersedesWithoutExplicitClear() {
        // Every one of the three production bump sites (ACTION_START, preserveReconnect
        // ACTION_STOP, finishStopFlowConfirmed()) calls only beginNewAttempt() -- none of them
        // call clearPendingIfOwnedBy() directly. This pins that a bump alone is sufficient to move
        // state back to IDLE for a marker armed at the old generation, without needing a paired
        // clear call.
        val guard = ReconnectDispatchGuard()
        val generation = guard.beginNewAttempt()
        guard.armPending(generation)
        assertTrue(guard.isBufferPendingForCurrentGeneration())

        guard.beginNewAttempt()

        assertFalse(
            "A fresh beginNewAttempt() must move state back to IDLE for the old marker even " +
                "without an explicit clear -- the marker no longer equals the live generation",
            guard.isBufferPendingForCurrentGeneration()
        )
    }

    @Test
    fun beginNewAttempt_concurrentCallsFromMultipleThreads_loseNoIncrementAndReturnDistinctValues() {
        // beginNewAttempt() is the single choke point all three production bump sites now flow
        // through -- including finishStopFlowConfirmed(), which runs on the AIDL binder thread --
        // so it MUST be atomic under genuine cross-thread contention, not merely typed as an
        // AtomicInteger. Unlike the older regression test (which
        // increments a reflected AtomicInteger field directly, bypassing this method and pinning
        // only the field's TYPE), this test races real calls to beginNewAttempt() itself and would
        // fail immediately if the method were reverted to a non-atomic read-modify-write such as:
        //   val next = attemptGeneration.get() + 1; attemptGeneration.set(next); return next
        val guard = ReconnectDispatchGuard()
        val threadCount = 64
        val iterationsPerThread = 500
        val expectedTotal = threadCount * iterationsPerThread
        // CopyOnWriteArrayList copies its entire backing array on every write, making the
        // 32,000 concurrent adds below quadratic in allocation/copy cost -- expensive enough to
        // make the 30s timeout flaky on constrained CI workers. Only distinct-value membership is
        // needed here (see the assertions below), so a concurrent set is a direct, cheaper
        // drop-in: ConcurrentHashMap.newKeySet() adds are O(1) amortized, no whole-collection copy.
        val returnedValues = ConcurrentHashMap.newKeySet<Int>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(threadCount) {
                executor.submit {
                    startLatch.await()
                    repeat(iterationsPerThread) {
                        returnedValues.add(guard.beginNewAttempt())
                    }
                    doneLatch.countDown()
                }
            }

            startLatch.countDown()
            val finished = doneLatch.await(30, TimeUnit.SECONDS)

            assertTrue("All $threadCount threads must complete within the timeout", finished)
            assertEquals(
                "Every increment must be observed exactly once -- no increment may be lost to a " +
                    "torn read-modify-write across threads",
                expectedTotal,
                guard.currentGeneration
            )
            assertEquals(
                "No two concurrent callers may observe the same returned generation -- each " +
                    "beginNewAttempt() call must own a distinct value",
                expectedTotal,
                returnedValues.size
            )
        } finally {
            executor.shutdownNow()
        }
    }
}

package com.yahorzabotsin.openvpnclientgate.core.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Guards the invariant every generation-based drift guard depends on: a key's published
 * generation never moves backward. A regression here is not a cosmetic one -- `current()` going
 * backward lets a superseded backfill's captured ticket match again, so its stale pages overwrite
 * the newer selection or full-list cache.
 */
class CountrySyncGenerationsTest {

    @Before
    fun setUp() {
        CountrySyncGenerations.resetForTests()
    }

    @Test
    fun bump_publishes_the_ticket_it_returns() {
        val claimed = CountrySyncGenerations.bump("jp")

        assertEquals(claimed, CountrySyncGenerations.current("JP"))
    }

    @Test
    fun sequential_bumps_of_one_key_strictly_increase() {
        var previous = CountrySyncGenerations.current("FR")
        repeat(20) {
            val claimed = CountrySyncGenerations.bump("FR")
            assertTrue("generation must strictly increase", claimed > previous)
            assertEquals(claimed, CountrySyncGenerations.current("FR"))
            previous = claimed
        }
    }

    /**
     * Reproduces the interleaving that the pre-fix `bump` allowed: allocate the ticket, then
     * publish it in a separate step. Two racing bumps could allocate 1 and 2 and publish in the
     * opposite order, leaving the map holding 1 -- i.e. the key's published generation lower than
     * a ticket already handed out. With allocation inside `compute`, the last publisher for a key
     * always holds that key's largest ticket.
     */
    @Test
    fun concurrent_bumps_of_the_same_key_never_publish_a_lower_generation() {
        val bumperCount = 8
        val observerCount = 4
        val bumpsPerThread = 20_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(bumperCount)
        val claimed = Collections.synchronizedList(mutableListOf<Long>())

        // Observing under one monitor gives the samples a total order, so "this sample is lower
        // than an earlier one" is a genuine regression of the published value rather than two
        // reads racing each other.
        val observerMonitor = Any()
        var highestSeen = 0L
        var regressions = 0
        val observers = List(observerCount) {
            Thread {
                start.await()
                while (done.count > 0L) {
                    synchronized(observerMonitor) {
                        val now = CountrySyncGenerations.current("DE")
                        if (now < highestSeen) regressions++ else highestSeen = now
                    }
                }
            }
        }
        val bumpers = List(bumperCount) {
            Thread {
                start.await()
                repeat(bumpsPerThread) { claimed.add(CountrySyncGenerations.bump("DE")) }
                done.countDown()
            }
        }
        (observers + bumpers).forEach { it.start() }
        start.countDown()
        assertTrue("workers did not finish", done.await(60, TimeUnit.SECONDS))
        observers.forEach { it.join(10_000) }

        assertEquals(bumperCount * bumpsPerThread, claimed.size)
        assertEquals("a key's published generation must never move backward", 0, regressions)
        assertEquals(
            "published generation must equal the highest ticket allocated for the key",
            claimed.max(),
            CountrySyncGenerations.current("DE")
        )
    }

    /**
     * `bump` and `bumpUnlessBumpedSince` mutate the same key, so they must serialize against each
     * other too: a check-and-claim that interleaved with a plain bump could drop or regress a
     * publication. Both go through `compute`, so the key ends on the highest ticket either of them
     * actually published.
     */
    @Test
    fun concurrent_bump_and_conditional_bump_leave_the_key_on_the_highest_published_ticket() {
        val threadCount = 16
        val opsPerThread = 3_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val published = Collections.synchronizedList(mutableListOf<Long>())

        repeat(threadCount) { index ->
            Thread {
                start.await()
                repeat(opsPerThread) {
                    if (index % 2 == 0) {
                        published.add(CountrySyncGenerations.bump("BY"))
                    } else {
                        val since = CountrySyncGenerations.current("BY")
                        CountrySyncGenerations.bumpUnlessBumpedSince("BY", since)
                            ?.let { published.add(it) }
                    }
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS))

        assertTrue("no generation was ever published", published.isNotEmpty())
        assertEquals(
            "published generation must equal the highest ticket either operation published",
            published.max(),
            CountrySyncGenerations.current("BY")
        )
    }

    @Test
    fun keys_are_canonicalized_across_case_and_whitespace() {
        val claimed = CountrySyncGenerations.bump("  by ")

        assertEquals(claimed, CountrySyncGenerations.current("BY"))
        assertEquals(claimed, CountrySyncGenerations.current("by"))
    }
}

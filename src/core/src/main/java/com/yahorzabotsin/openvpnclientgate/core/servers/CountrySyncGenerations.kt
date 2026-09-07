package com.yahorzabotsin.openvpnclientgate.core.servers

import androidx.annotation.VisibleForTesting
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-country generation counter shared by backfill launches and sync completions.
 * Bumped at each [DefaultCountryServersInteractor.launchSilentBackfill] and by
 * [ServersV2Repository.getServersForCountry] after a successful country sync; the backfill
 * captures the generation at start and skips its writes when it no longer matches, so the
 * latest job for a country always wins.
 *
 * Generations are **globally monotonic tickets**, not per-country counters: every bump takes
 * the next value from one process-wide sequence, so two generations are comparable even when
 * they belong to different keys. That is what lets a name-only backfill decide, once it finally
 * resolves its country code, whether the code key was bumped *before* or *after* its own launch
 * (`generationOfCodeKey > myLaunchGeneration` means "bumped after I launched"). A per-country
 * `prev + 1` counter cannot answer that question, and answering it wrong lets a backfill whose
 * pages predate a sync overwrite that sync's fresher cache.
 *
 * The backing map is private on purpose: the monotonic invariant above only holds while every
 * mutation goes through [bump]/[bumpUnlessBumpedSince], and every read/write canonicalizes its
 * key through [key]. A raw, un-canonicalized code silently splits one country into two unrelated
 * counters: a bump keyed `"JP"` then stays invisible to a guard keyed `"jp"`, which is exactly
 * the "stale paged list overwrites the newer sync's cache" failure the guards exist to prevent.
 * [Locale.ROOT] is mandatory here -- a device in the Turkish locale uppercases `"i"` to `"İ"`,
 * which would split the key by device locale too.
 */
@VisibleForTesting
internal object CountrySyncGenerations {
    /** One process-wide sequence, so generations from different keys stay comparable. */
    private val sequence = AtomicLong(0L)

    private val generations: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** Canonical, locale-independent generation key for a country code (or, for name-only
     * selections that have no code, a country name). Idempotent. */
    fun key(rawCountryKey: String): String = rawCountryKey.trim().uppercase(Locale.ROOT)

    /** Current generation of [rawCountryKey]; `0` when it has never been bumped. */
    fun current(rawCountryKey: String): Long = generations[key(rawCountryKey)] ?: 0L

    /** Claims a fresh generation for [rawCountryKey] and returns it. */
    fun bump(rawCountryKey: String): Long {
        val next = sequence.incrementAndGet()
        generations[key(rawCountryKey)] = next
        return next
    }

    /**
     * Claims a fresh generation for [rawCountryKey] **only if** nobody bumped that key after
     * generation [sinceGeneration] was issued; returns the claimed generation, or `null` when
     * the key has already moved on (the caller has been superseded and must not write).
     *
     * Used when a name-only backfill adopts its canonical code key after resolving the code:
     * blindly bumping there would advance the counter past a sync that completed while the code
     * was being resolved, making that sync invisible to the caller's own drift guard. The
     * check-and-claim is atomic so a sync landing between the read and the write cannot be
     * stomped either.
     */
    fun bumpUnlessBumpedSince(rawCountryKey: String, sinceGeneration: Long): Long? {
        var claimed: Long? = null
        generations.compute(key(rawCountryKey)) { _, previous ->
            if (previous != null && previous > sinceGeneration) {
                previous
            } else {
                sequence.incrementAndGet().also { claimed = it }
            }
        }
        return claimed
    }

    /**
     * Drops every recorded generation. Tests only: this object is a process-wide singleton, so a
     * generation left behind by a previous test would otherwise decide the next test's drift
     * guard. The sequence itself is deliberately NOT reset -- monotonicity must survive.
     */
    @VisibleForTesting
    fun resetForTests() {
        generations.clear()
    }
}

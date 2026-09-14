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

    /**
     * Claims a fresh generation for [rawCountryKey] and returns it.
     *
     * The ticket is allocated **inside** [ConcurrentHashMap.compute] so allocation and
     * publication are one atomic step per key. Allocating first and then assigning
     * (`generations[key] = sequence.incrementAndGet()`) is a compound operation: two concurrent
     * bumps of the same key can allocate 1 and 2 and then publish in the opposite order, leaving
     * the map holding 1. [current] would then move *backward*, and a superseded backfill whose
     * captured ticket is 1 would pass its `isCurrentGeneration()` guard and overwrite the newer
     * selection/cache -- exactly what these tickets exist to prevent. Under `compute` the loser
     * of the per-key bin lock always allocates the larger ticket, so a key's generation is
     * strictly increasing.
     *
     * This is also what makes [bump] and [bumpUnlessBumpedSince] safe against each other: both
     * mutate through `compute` on the same key, so their check-and-claim sequences serialize on
     * that key's bin lock instead of interleaving.
     */
    fun bump(rawCountryKey: String): Long {
        var claimed = 0L
        generations.compute(key(rawCountryKey)) { _, _ ->
            sequence.incrementAndGet().also { claimed = it }
        }
        return claimed
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
     * Bumps every distinct, non-blank key among [rawCountryKeys], so any same-country backfill
     * still in flight stands down instead of overwriting the pool the caller is about to write.
     *
     * Several keys are accepted because [DefaultCountryServersInteractor.launchSilentBackfill]
     * keys its launch generation by `countryCode ?: countryName`: a backfill started from a screen
     * opened *by name* guards on the name key, while one started with a code guards on the code
     * key, and a sync may additionally know the country under a freshly relocalized name. Bumping
     * only the key the caller happens to hold would leave the other kinds unsuperseded. Bumping a
     * key nothing is guarding on is harmless -- generations are monotonic tickets that are only
     * ever compared for equality with a captured value.
     *
     * Every caller that *replaces a country's persisted candidate pool* must call this first --
     * not only a new selection of the same source. A server-source switch (DEFAULT_V2 -> VPN Gate)
     * routes through [SelectedCountryServerSync], which rewrites the same country's pool from a
     * different source entirely; without a bump here, an in-flight V2 backfill's guard still reads
     * as current and its pages land on top of the just-synced pool, resetting the active server to
     * index 0 whenever its config is absent from the V2 data.
     */
    fun supersede(vararg rawCountryKeys: String?) {
        rawCountryKeys.asSequence()
            .filterNot { it.isNullOrBlank() }
            .map { key(it!!) }
            .distinct()
            .forEach { bump(it) }
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

package com.yahorzabotsin.openvpnclientgate.core.settings

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide monotonic ticket advanced every time the persisted [ServerSource] is written.
 *
 * Background work that is only valid for the source it started under (today: the silent V2
 * backfill in `DefaultCountryServersInteractor`) captures [current] at launch and stands down
 * once it no longer matches. Reading the live setting instead is not sufficient:
 *
 * - The Settings flow persists the new source first and only then launches its source-change
 *   sync, and that sync is the thing that advances the per-country generation guards. When the
 *   sync is cancelled (the Settings screen closes) or returns before writing (the country is
 *   missing from the new source, or its configs fail to load), no generation ever moves, so a
 *   guard built only on generations still reads as current.
 * - A live `serverSource == DEFAULT_V2` check closes only part of that hole: it is a
 *   check-then-write against a value that can change in between, and it cannot see a source
 *   that moved away and back (`DEFAULT_V2 -> VPNGATE -> DEFAULT_V2`) while the job was parked.
 *   In that window the VPN Gate sync already rewrote the country's candidate pool, and the
 *   older V2 pages must not land on top of it.
 *
 * The ticket is advanced *before* the new value is published (see [UserSettingsStore]), so a
 * guard can never observe the new source with the old ticket. The reverse skew is harmless: a
 * guard that sees the new ticket with the old source simply stands down one instant early.
 *
 * Ordering the bump ahead of the publish is not by itself enough for a guard whose *write* comes
 * after its check. The bump and the publish therefore also run under [SelectionWriteLock] -- the
 * monitor the guarded selection writes take -- so a source transition cannot land between such a
 * guard and the commit it protects.
 *
 * Only ever compared for equality with a captured value, so wraparound is not a concern and the
 * counter is deliberately never reset -- monotonicity must survive for the life of the process.
 */
object ServerSourceEpoch {
    private val epoch = AtomicLong(0L)

    /** The current ticket. Capture this before starting source-dependent background work. */
    fun current(): Long = epoch.get()

    /**
     * Advances the ticket. Called by [UserSettingsStore] on every write that can change the
     * persisted server source, including writes that happen to store the same value again --
     * a spurious advance only makes an in-flight job stand down, which is always the safe
     * direction.
     */
    @VisibleForTesting
    internal fun bump(): Long = epoch.incrementAndGet()
}

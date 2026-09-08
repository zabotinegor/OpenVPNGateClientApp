package com.yahorzabotsin.openvpnclientgate.core.settings

/**
 * The single monitor shared by two writers that must never interleave:
 *
 * - every guarded write to the persisted server selection (`SelectedCountryStore` takes this
 *   monitor for its whole check -> write -> restore-index sequence), and
 * - every persisted change of the server source ([UserSettingsStore.saveServerSource] and
 *   [UserSettingsStore.save], which advance [ServerSourceEpoch] and then publish the new value).
 *
 * Without one monitor the two are independent synchronization domains, and a deferred writer that
 * is only valid for the source it started under (the silent V2 backfill) has a window it cannot
 * close: its freshness predicate is evaluated inside the selection monitor, but a source change
 * landing between that evaluation and the commit is invisible to it. The predicate passes with the
 * old epoch, the source becomes VPN Gate, and the V2 pool is then written into a selection that
 * belongs to the new source. Nothing necessarily repairs it afterwards -- the source-change sync
 * returns early when the country is missing from the new source or its configs fail to load -- so
 * the incompatible pool survives for the whole cache TTL.
 *
 * Holding this monitor across the epoch bump and the source publish makes the transition atomic
 * against the guarded commit: the source write either lands entirely before the predicate runs (so
 * the predicate sees the advanced epoch and stands down) or blocks until the commit has finished,
 * after which the source-change sync owns the pool.
 *
 * The monitor is reentrant, guards only in-memory bookkeeping plus a `SharedPreferences.apply()`
 * (which hands the disk write to a background thread), and is never held across a network call or
 * a suspension point, so the critical sections stay short.
 */
internal object SelectionWriteLock {
    val monitor = Any()
}

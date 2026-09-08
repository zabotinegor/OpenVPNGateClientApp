package com.yahorzabotsin.openvpnclientgate.core.settings

/**
 * The single monitor shared by two writers that must never interleave:
 *
 * - every guarded write to the persisted server selection (`SelectedCountryStore` takes this
 *   monitor for its whole check -> write -> restore-index sequence), and
 * - every persisted setting change that advances an epoch the deferred writer's freshness
 *   predicate compares: [UserSettingsStore.saveServerSource] ([ServerSourceEpoch]),
 *   [UserSettingsStore.saveLanguage] ([AppLocaleEpoch]), and [UserSettingsStore.save], which
 *   advances both. Each bumps its epoch and then publishes the new value under this monitor.
 *
 * Without one monitor the two are independent synchronization domains, and a deferred writer that
 * is only valid for the source and language it started under (the silent V2 backfill) has a window
 * it cannot close: its freshness predicate is evaluated inside the selection monitor, but a setting
 * change landing between that evaluation and the commit is invisible to it. The predicate passes
 * with the old epoch, the source becomes VPN Gate, and the V2 pool is then written into a selection
 * that belongs to the new source. Nothing necessarily repairs it afterwards -- the source-change
 * sync returns early when the country is missing from the new source or its configs fail to load --
 * so the incompatible pool survives for the whole cache TTL. The language half is the same shape:
 * a pool assembled in the old language commits against the new one, and the relocalization job that
 * would repair it can be cancelled during recreation or fail to load its cache/network data.
 *
 * The coverage has to be all-or-nothing. A half-guarded set -- one epoch's writers on the monitor
 * and another's off it -- closes only the window it happens to cover and leaves the predicate's
 * other term exposed, which reads as protected without being so.
 *
 * Holding this monitor across the epoch bump and the value publish makes the transition atomic
 * against the guarded commit: the setting write either lands entirely before the predicate runs (so
 * the predicate sees the advanced epoch and stands down) or blocks until the commit has finished,
 * after which the change's own follow-up sync owns the pool.
 *
 * The monitor is reentrant, guards only in-memory bookkeeping plus a `SharedPreferences.apply()`
 * (which hands the disk write to a background thread), and is never held across a network call or
 * a suspension point, so the critical sections stay short.
 */
internal object SelectionWriteLock {
    val monitor = Any()
}

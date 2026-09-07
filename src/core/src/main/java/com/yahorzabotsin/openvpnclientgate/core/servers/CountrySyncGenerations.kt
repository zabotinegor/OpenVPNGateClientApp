package com.yahorzabotsin.openvpnclientgate.core.servers

import androidx.annotation.VisibleForTesting
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-country generation counter shared by backfill launches and sync completions.
 * Incremented at each [DefaultCountryServersInteractor.launchSilentBackfill] and by
 * [ServersV2SyncCoordinator] after a successful country sync; the backfill captures the
 * generation at start and skips its writes when it no longer matches, so the latest job
 * for a country always wins.
 *
 * Every producer and consumer MUST derive its map key through [key]. The map is shared
 * across independently-written call sites (sync bump, foreground paging freshness guard,
 * backfill drift guard) and a raw, un-canonicalized code silently splits one country into
 * two unrelated counters: a bump keyed `"JP"` then stays invisible to a guard keyed `"jp"`,
 * which is exactly the "stale paged list overwrites the newer sync's cache" failure the
 * guards exist to prevent. [Locale.ROOT] is mandatory here — a device in the Turkish
 * locale uppercases `"i"` to `"İ"`, which would split the key by device locale too.
 */
@VisibleForTesting
internal object CountrySyncGenerations {
    val generations: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** Canonical, locale-independent generation key for a country code (or, for name-only
     * selections that have no code, a country name). */
    fun key(rawCountryKey: String): String = rawCountryKey.trim().uppercase(Locale.ROOT)
}

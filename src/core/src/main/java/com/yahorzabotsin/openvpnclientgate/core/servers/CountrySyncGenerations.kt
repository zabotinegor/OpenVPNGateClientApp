package com.yahorzabotsin.openvpnclientgate.core.servers

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-country generation counter shared by backfill launches and sync completions.
 * Incremented at each [DefaultCountryServersInteractor.launchSilentBackfill] and by
 * [ServersV2SyncCoordinator] after a successful country sync; the backfill captures the
 * generation at start and skips its writes when it no longer matches, so the latest job
 * for a country always wins.
 */
@VisibleForTesting
internal object CountrySyncGenerations {
    val generations: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
}

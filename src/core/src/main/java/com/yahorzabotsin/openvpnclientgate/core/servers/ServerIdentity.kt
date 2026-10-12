package com.yahorzabotsin.openvpnclientgate.core.servers

/**
 * Stable per-pool identity for [StoredServer] entries.
 *
 * Positions in the persisted server list are NOT stable: a background refresh
 * ([SelectedCountryStore.saveSelectionPreservingIndex]) can add, remove or reorder entries while an
 * auto-switch cycle is running. Anything that must survive such a refresh (the cycle's visited set,
 * the persisted "current server" index) therefore tracks these keys instead of numeric positions.
 *
 * Scheme, per entry:
 *  - base key: `id:<id>` when the backend server id is valid (> 0), otherwise
 *    `cfg:<config length>:<config hash>|<ip>` (never the IP alone -- distinct servers may share one);
 *  - the n-th (n >= 1) additional entry with the same base key gets the ordinal suffix `#n`, so
 *    duplicates stay distinguishable instead of collapsing onto the first occurrence. The first
 *    occurrence keeps the plain base key, so it does not change when a duplicate is later added.
 */
internal object ServerIdentity {
    fun keys(servers: List<StoredServer>): List<String> {
        val seen = HashMap<String, Int>()
        return servers.map { server ->
            val base = baseKey(server)
            val ordinal = (seen[base] ?: 0)
            seen[base] = ordinal + 1
            if (ordinal == 0) base else "$base#$ordinal"
        }
    }

    private fun baseKey(server: StoredServer): String =
        if (server.id > 0) {
            "id:${server.id}"
        } else {
            "cfg:${server.config.length}:${server.config.hashCode()}|${server.ip.orEmpty()}"
        }
}

/** A cycle candidate resolved against the persisted list: the server, its identity and position. */
data class CycleCandidate(val server: StoredServer, val key: String, val index: Int)

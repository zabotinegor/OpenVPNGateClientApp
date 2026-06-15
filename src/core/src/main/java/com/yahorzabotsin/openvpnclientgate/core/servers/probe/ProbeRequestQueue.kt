package com.yahorzabotsin.openvpnclientgate.core.servers.probe

/**
 * Durable HTTP probe request queue backed by WorkManager.
 *
 * Callers enqueue a probe request for a given server ID; the queue ensures
 * delivery survives app restarts, deduplicates concurrent requests for the
 * same server, and respects server-side rate limiting (HTTP 429).
 */
interface ProbeRequestQueue {
    /**
     * Enqueue a POST /api/v2/servers/{serverId}/probe request.
     *
     * If a pending or running request for the same [serverId] already exists,
     * the new request is silently dropped (KEEP deduplication).
     */
    fun enqueue(serverId: Int)
}

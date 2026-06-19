package com.yahorzabotsin.openvpnclientgate.core.servers.probe

import timber.log.Timber
import java.io.IOException

/**
 * Wraps [ProbeApi] and maps raw HTTP responses to [ProbeResult].
 *
 * This is a thin client responsible only for HTTP→domain mapping.
 * Retry scheduling and WorkManager wiring belong to SUB-02 ([WorkManagerProbeRequestQueue]).
 */
class HardProbeApiClient(private val probeApi: ProbeApi) {

    /**
     * Calls `POST api/v2/servers/{id}/probe` and maps the response to a [ProbeResult].
     *
     * Mapping:
     * - 202 → [ProbeResult.Queued]
     * - 404 → [ProbeResult.NotFound]
     * - 422 → [ProbeResult.NoConfigData]
     * - 429 → [ProbeResult.RateLimited]
     * - 503 → [ProbeResult.ServiceUnavailable]
     * - any other code (including unexpected 2xx codes) → [ProbeResult.Error] with that code
     * - network / IO exception → [ProbeResult.Error] with code -1
     */
    suspend fun probe(serverId: Int): ProbeResult {
        return try {
            val response = probeApi.probe(serverId)
            mapResponseCode(response.code())
        } catch (e: IOException) {
            Timber.w(e, "HardProbeApiClient: network error probing server %d", serverId)
            ProbeResult.Error(code = -1)
        }
    }

    private fun mapResponseCode(code: Int): ProbeResult = when (code) {
        202 -> ProbeResult.Queued
        404 -> ProbeResult.NotFound
        422 -> ProbeResult.NoConfigData
        429 -> ProbeResult.RateLimited
        503 -> ProbeResult.ServiceUnavailable
        else -> ProbeResult.Error(code = code)
    }
}

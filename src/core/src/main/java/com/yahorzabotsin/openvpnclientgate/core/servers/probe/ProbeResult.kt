package com.yahorzabotsin.openvpnclientgate.core.servers.probe

/**
 * Sealed result type for the hard-probe API call.
 *
 * Maps HTTP response codes returned by `POST api/v2/servers/{id}/probe`:
 * - [Queued]            — 202 Accepted: probe job enqueued on the server side.
 * - [NotFound]          — 404 Not Found: server ID does not exist.
 * - [NoConfigData]      — 422 Unprocessable Entity: server exists but has no config data.
 * - [RateLimited]       — 429 Too Many Requests: caller should back off before retrying.
 * - [ServiceUnavailable]— 503 Service Unavailable: backend temporarily down.
 * - [Error]             — any other non-success HTTP code or network/IO exception.
 *                         [code] is the HTTP status code, or -1 for network/IO failures.
 */
sealed class ProbeResult {
    /** HTTP 202 — probe job accepted and queued by the server. */
    data object Queued : ProbeResult()

    /** HTTP 404 — the requested server ID was not found. */
    data object NotFound : ProbeResult()

    /** HTTP 422 — the server exists but has no configuration data available. */
    data object NoConfigData : ProbeResult()

    /** HTTP 429 — rate limit exceeded; caller should wait before retrying. */
    data object RateLimited : ProbeResult()

    /** HTTP 503 — backend service is temporarily unavailable. */
    data object ServiceUnavailable : ProbeResult()

    /**
     * Any other HTTP error code or network/IO failure.
     *
     * @param code HTTP status code of the failed response, or -1 for network/IO exceptions.
     */
    data class Error(val code: Int) : ProbeResult()
}

# Backend endpoints

Every backend route this app calls, verified against source. The backend exposes more than this —
what matters is what the client actually uses.

## Called at runtime

| Endpoint | Caller |
|---|---|
| `GET /api/v2/servers/countries/active?locale=` | `servers/ServersV2Api.kt` |
| `GET /api/v2/servers?locale&countryCode&isActive&skip&take` | `servers/ServersV2Api.kt` — page size 50 |
| `POST /api/v2/servers/{id}/probe` | `servers/probe/ProbeApi.kt` |
| `GET /api/v1/servers/events` (SSE) | `servers/sse/SseServerEventsClient.kt` via `PrimaryDomainRoutes.sseServersEventsUrl` |
| `GET /api/v2/versions/check-update`, then `GET /api/v1/versions/check-update` | `updates/UpdateCheckRepository.kt` via `ApiConstants.primaryUpdateCheckUrls` — **v2 first, v1 as fallback** |
| `GET /api/v1/versions/number/{versionName}/build/{buildNumber}?locale=` | `versions/VersionReleaseRepository.kt` |
| `GET {FALLBACK_SERVERS_URL}` (VPN Gate CSV) | `servers/ServerRepository.kt` |

Update-check query parameters: `platform`, `releaseType`, `currentBuild`, `locale`. `releaseType`
comes from the `APP_RELEASE_TYPE` build field — see [build-config.md](build-config.md).

## Defined but not called

| Endpoint | Status |
|---|---|
| `GET /api/v1/servers/active` (legacy CSV on the primary host) | **Dead code.** `ApiConstants.primaryLegacyServersUrl()` is defined and has **zero callers**. `resolveServerUrls` returns `[FALLBACK_SERVERS_URL]` for `VPNGATE` and `[]` for `DEFAULT_V2`. Any doc describing a three-step fallback through this route is wrong |
| `/api/v1/legal/*` | Not used. Legal documents are two hardcoded website URLs opened in a browser — opened in a browser from the About screen |
| `/api/v1/general-info` | Not used. No reference anywhere in `src/` |

## URL derivation

Nothing is hardcoded. `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` are build-time fields;
`PrimaryDomainRoutes` strips any `/api/vN` marker from the configured base and rebuilds each path at
runtime. See [build-config.md](build-config.md).

## Retrofit clients

Two, wired in `core/di/CoreDi.kt`:

- unnamed — dummy base `https://openvpnclientgate.local/` with `ScalarsConverterFactory`, used only
  for `@Url`-driven APIs
- `named("v2")` — `ApiConstants.primaryRetrofitBaseUrl()` with `GsonConverterFactory`

## SSE has no fallback host

The SSE client rotates through a candidate URL list, but **only the primary is configured**.
`FALLBACK_SERVERS_URL` is deliberately excluded — it is a VPN Gate CSV endpoint and is not
SSE-capable. Rotation exists as a mechanism with nothing to rotate to in production. A doc claiming
"primary → fallback" rotation is describing a chain that is not configured.

*Last verified against: `core/AppConstants.kt`, `servers/ServersV2Api.kt`, `servers/probe/ProbeApi.kt`, `servers/sse/SseServerEventsClient.kt`, `updates/UpdateCheckRepository.kt`, `versions/VersionReleaseRepository.kt`, `core/di/CoreDi.kt` (2026-07-31).*

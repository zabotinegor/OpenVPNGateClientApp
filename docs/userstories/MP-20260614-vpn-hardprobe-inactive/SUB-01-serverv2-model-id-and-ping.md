# SUB-01 — Android ServerV2 model: expose server `id` and `ping` from API

## User story

As a VPN client user, I want the server list to show real ping values for V2 servers, and as the app infrastructure, I want each V2 server entry to carry its backend integer ID, so that the ping display reflects real latency and the probe subsystem can reference the correct server.

## Background

The app uses two server sources:

- **Legacy CSV** (`ServerRepository`): delivers a rich `Server` model with real `ping` values.
- **DEFAULT\_V2 API** (`ServersV2Repository`): delivers `ServerV2` objects that are mapped to `Server` via `toLegacyServer()`. Currently `toLegacyServer()` hardcodes `ping = 0`, so the server list always shows **0 мс** for V2 servers (confirmed by screenshot evidence — see image attached to this story). Similarly, `ServerV2` carries no `id` field, making it impossible for SUB-04 (VPN inactivity → hardprobe trigger) to call `POST /api/v2/servers/{id}/probe`.

The server team is adding both `id` (integer primary key) and `ping` (milliseconds, server-side measured) to `VpnServerV2ListItemDto` — tracked in server repo story `US-12-server-list-expose-id.md`. This story implements the Android-side changes needed to parse, cache, and propagate both new fields.

**Currently affected files:**
- `src/core/…/servers/ServerV2.kt` — `id` and `ping` fields missing
- `src/core/…/servers/ServersV2Repository.kt` — cache deserialization (Gson `Array<ServerV2>::class.java`); backward-compatibility must be maintained
- `src/core/…/servers/CountryServersInteractor.kt` — calls `toLegacyServer()` on line ~79
- `src/core/…/ui/main/MainSelectionInteractor.kt` — also calls `toLegacyServer()`
- `src/core/…/servers/Server.kt` — `ping: Int` field already exists; `signalStrength` is still hardcoded to `WEAK` (out of scope)

## Acceptance criteria

| ID | Criterion |
|----|-----------|
| AC-1 | `ServerV2.kt` gains `@SerializedName("id") val id: Int = 0` and `@SerializedName("ping") val ping: Int = 0`. Both default to `0` so existing cached JSON without these fields deserialises without crash or exception. |
| AC-2 | `ServerV2.toLegacyServer()` maps `ping = ping` (from the model, not hardcoded `0`) and carries `id` to any accessible location needed by callers (see AC-3). |
| AC-3 | The server `id` is accessible to the hardprobe subsystem at the point of VPN connection failure. Acceptable options: store `id` in `SelectedCountryStore` alongside the existing fields, or return it as a separate field in `ServerSelectionResult`. The chosen approach must not break the existing `SelectedCountryStore` read/write contract for older stored JSON (add `id` as optional with default `0`). |
| AC-4 | When the server list JSON response contains `"id": 42` and `"ping": 37`, the parsed `ServerV2` has `id=42` and `ping=37`. |
| AC-5 | When the server list JSON response omits `id` and `ping` (old cache or old API response), the parsed `ServerV2` has `id=0` and `ping=0` — no crash, no `JsonSyntaxException`. |
| AC-6 | `Server.ping` received from the V2 path equals the value from `ServerV2.ping` (i.e. `toLegacyServer()` propagates it rather than hardcoding `0`). |
| AC-7 | Existing cache migration paths in `ServersV2Repository` are unaffected: legacy cache files that pre-date this change continue to be read without errors. |
| AC-8 | Unit tests (JVM/Robolectric) cover: correct parsing of `id` and `ping`; missing-field backward-compatibility; `toLegacyServer()` propagation; `SelectedCountryStore` round-trip with the new `id` field. |
| AC-9 | `assembleDebugApp` and `testDebugUnitTestApp` pass with no new failures. |

## Out of scope

- Server-side DTO change (tracked separately: server repo `US-12-server-list-expose-id.md`).
- `signalStrength` calculation from ping (remains hardcoded `WEAK`).
- Any UI layout changes — the ping value already renders in the server list; propagating a real value from the model is sufficient.
- Hardprobe queue, API client, or trigger wiring (SUB-02, SUB-03, SUB-04).
- `ServerAutoSwitcher` changes.
- TV launcher — ping display and id propagation flow through the same `src/core` code path; no launcher-specific changes are needed.

## Risks and open questions

| # | Risk / Question | Severity | Resolution |
|---|-----------------|----------|------------|
| R-1 | Server team has not yet shipped `id` and `ping` in the v2 API response. Until then AC-4 can only be verified with a mocked response. | Medium | Unit-test with a mock JSON payload; mark E2E verification as blocked on server delivery. |
| R-2 | `SelectedCountryStore` writes JSON manually (not Gson) using `KEY_JSON_*` constants. Adding `id` there requires a new constant and a backward-compatible read path (missing key → 0). | Low | Add `KEY_JSON_SERVER_ID = "id"` constant; default to `0` on read. |
| R-3 | `ServerV2` is serialised to cache via `Gson` directly (the full `ServerV2` object array is written as JSON). Adding optional fields with defaults is backward-compatible for Gson deserialization. No cache invalidation is needed. | Low | Resolved — Gson ignores unknown fields by default and uses field defaults for missing ones. |
| R-4 | `ping` field name collision: the server DTO uses `"ping"` (confirm with server team). If the JSON key differs, the `@SerializedName` must be updated. | Low | Confirm JSON key name with server team before shipping. |

## Implementation notes

1. **`ServerV2.kt`** — add two optional fields with Gson annotations and `= 0` defaults:
   ```kotlin
   @SerializedName("id")   val id:   Int = 0,
   @SerializedName("ping") val ping: Int = 0,
   ```
   These must have default values so they are optional in the primary constructor and Gson can deserialise old cached JSON without them.

2. **`ServerV2.toLegacyServer()`** — change `ping = 0` to `ping = ping`. The `Server.ping` field already exists and is typed `Int`.

3. **`SelectedCountryStore`** — inspect how `saveSelection` / `currentServer` store and read server fields. Add `id` as a stored field (new `KEY_JSON_SERVER_ID` string constant). Read path must default to `0` when the key is absent. The `StoredServer` data class (if present) may need a new `id: Int = 0` field.

4. **Propagation chain** — `id` must be accessible at least until `ServerAutoSwitcher` / `OpenVpnService` can retrieve the currently-connected server's ID for hardprobe. Confirm the shortest path; `SelectedCountryStore` is the most likely storage point since it already persists per-session server metadata.

5. **No changes needed to `ServersV2Repository` deserialization** — Gson handles new optional fields transparently when defaults are provided in the data class.

6. **No changes needed to `CountryServersInteractor` or `MainSelectionInteractor`** — they call `toLegacyServer()` which automatically picks up both fields once (2) is done.

## Test scenarios

| ID | Scenario | Type | Expected result |
|----|----------|------|-----------------|
| TS-1 | Parse `ServerV2` JSON with `"id": 5, "ping": 120` | Unit | `serverV2.id == 5`, `serverV2.ping == 120` |
| TS-2 | Parse `ServerV2` JSON without `id` or `ping` keys | Unit | `serverV2.id == 0`, `serverV2.ping == 0`, no exception |
| TS-3 | `toLegacyServer()` on a `ServerV2` with `ping = 75` | Unit | `server.ping == 75` |
| TS-4 | `toLegacyServer()` on a `ServerV2` with `ping = 0` (default) | Unit | `server.ping == 0` (no regression) |
| TS-5 | `SelectedCountryStore.saveSelection()` writes server with `id = 42`, then `currentServer()` reads it back | Unit | Returned stored server has `id == 42` |
| TS-6 | `SelectedCountryStore` reads a JSON blob without `"id"` key (legacy stored data) | Unit | Returns `id == 0`, no crash |
| TS-7 | Existing `ServersV2Repository` cache migration test — write old-format cache, read it back | Unit (regression) | All existing fields parse correctly; `id` and `ping` default to `0` |
| TS-8 | Manual QA: connect to a DEFAULT\_V2 server after the server team ships `ping` in the API; server list shows non-zero ping | Manual (post server delivery) | Ping value > 0 displayed for at least one server |

## Definition of done

- AC-1 through AC-9 are met.
- TS-1 through TS-7 pass as automated unit tests.
- TS-8 is documented as a manual QA step to be executed after the server team ships `id` and `ping` in the v2 API response.
- No existing unit tests are broken.
- `assembleDebugApp` succeeds.
- The story file is committed on `feature/sub-01-serverv2-id-and-ping` and pushed.

---

*Master plan: MP-20260614-vpn-hardprobe-inactive / SUB-01 | dependsOn: none (external: server-repo US-12)*

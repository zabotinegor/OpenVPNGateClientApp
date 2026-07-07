# How-To Runbook

This runbook collects non-obvious techniques and patterns discovered during development. Add an
entry when a technique is reusable and not obvious from the library documentation alone.

---

## Use MockWebServer in pure-JVM unit tests (no Robolectric)

**When to use**

When you need to test an API client (e.g., a Retrofit + OkHttp wrapper) at the HTTP level
without standing up a real server. `MockWebServer` runs entirely in-process on a random port,
so these tests are fast pure-JVM tests that run with `testDebugUnitTestApp` — no emulator or
Robolectric required.

**Step 1 — Register the artifact in the version catalog**

In `src/gradle/libs.versions.toml`, add a `[libraries]` entry that reuses the existing
`square-okhttp` version reference:

```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "square-okhttp" }
```

The `square-okhttp` version ref is already present (used by the runtime `okhttp` library). Using
the same ref keeps `MockWebServer` and the runtime client at identical versions, which prevents
subtle protocol-mismatch bugs.

**Step 2 — Declare the dependency as test-only**

In the module's `build.gradle.kts` (e.g., `src/core/build.gradle.kts`):

```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

Do not add it to `implementation` or `androidTestImplementation` unless specifically needed for
instrumented tests.

**Step 3 — Write the test**

```kotlin
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class MyApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns expected result on HTTP 202`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(MyApi::class.java)
        val client = MyApiClient(api)

        val result = client.doRequest(serverId = 1)

        assertEquals(MyResult.Queued, result)
    }
}
```

Key points:
- Call `server.start()` in `@Before` and `server.shutdown()` in `@After`.
- Use `server.url("/")` as the Retrofit base URL so requests go to the in-process server.
- `server.enqueue()` queues responses in FIFO order; each response is consumed by exactly one
  request.
- `server.takeRequest()` lets you assert on the outgoing request (method, path, headers, body).

**First demonstrated**

SUB-03 (`HardProbeApiClientTest`).

**References**

- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/probe/HardProbeApiClientTest.kt`
- `src/gradle/libs.versions.toml` (`okhttp-mockwebserver` entry)
- [OkHttp MockWebServer README](https://github.com/square/okhttp/tree/master/mockwebserver)

---

## Hardprobe enqueue during VPN lifecycle — when it fires and when it is suppressed

**When to use this knowledge**

When adding a new VPN lifecycle event (disconnect, reconnect, auto-switch variant) and deciding
whether that event should trigger a hardprobe.

**When hardprobes are enqueued**

`ProbeRequestQueue.enqueue(serverId)` is called at five points in the VPN lifecycle:

| Event | Code location | Notes |
|---|---|---|
| Auto-switch timed/immediate | `ServerAutoSwitcher.requestSwitchNow()` | Probe for the failing server before `nextServerCircular()` advances the index. |
| DEFAULT_V2 hydration early-return | `ServerAutoSwitcher.requestSwitchNow()` | Probe enqueued before the function returns for on-demand hydration. Added in US-12. |
| Engine VPN_STATUS failure | `OpenVpnService.updateState()` | Probe for the failing server on `VPN_STATUS` auto-switch path. |
| Watchdog recovery | `OpenVpnService.handleConnectedProbeResult()` | Probe for the stalled server when watchdog triggers a reconnect. |
| User-initiated disconnect | `OpenVpnService.finishStopFlowConfirmed()` | Probe for the server that was active when the user tapped Disconnect. Added in US-12. |

**Why `serverId == 0` is a no-op**

`SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted()` returns `0` when:

- The currently selected server IP does not match the last-started IP (user changed
  selection in the UI while connected).
- The server has no integer ID from the v2 API — this covers legacy CSV sources (`LEGACY`,
  `VPNGATE`, `CUSTOM`), which use opaque string identifiers and map to `id = 0` in the shared
  data model.
- The network loss level `LEVEL_NONETWORK` is active — the calling code explicitly sets the ID
  to 0 in this case, because the device has lost internet connectivity (not a server failure).

A probe with `serverId = 0` would target no specific server and is suppressed at the call site
with a simple `if (serverId != 0)` guard. This is the correct behavior; do not remove it.

**WorkManager KEEP deduplication**

`ProbeRequestQueue` uses `ExistingWorkPolicy.KEEP`. If the same `serverId` is enqueued multiple
times in rapid succession (e.g., user stop followed by auto-switch for the same server), only
the first enqueue takes effect. This is intentional — the backend only needs one probe signal
per server per event cluster.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt` (`finishStopFlowConfirmed`, `handleConnectedProbeResult`, `updateState`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt` (`requestSwitchNow`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/probe/ProbeRequestQueue.kt`
- `src/docs/server-sync-flow.md` (Hardprobe Trigger Points section)
- `docs/userstories/US-12-hardprobe-on-every-vpn-disconnect.md`
- `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-04-vpn-inactivity-hardprobe-trigger.md`

---

## Verify SSE client connection on device

**When to use**

After deploying a build that includes `SseServerEventsClient`, or when debugging SSE connectivity
issues (e.g., the server list is not updating in real time despite backend pushes).

**What to check**

The SSE client connects when the app enters the foreground and disconnects when it backgrounds.
A `servers-changed` push event from the backend triggers a forced server-list sync.

**Step 1 — Start logcat filter**

In a terminal, start streaming SSE-relevant log lines before launching the app:

```bash
adb -s <serial> logcat -v time -e "SseServerEventsClient|CoreApp"
```

Replace `<serial>` with your device's ADB serial (`adb devices` to list).

**Step 2 — Launch the app**

```bash
adb -s <serial> shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
```

Expected logcat sequence (within a few seconds of launch):

```
CoreApp: SSE lifecycle observer registered
SseServerEventsClient: SSE client starting; url=https://.../api/v1/servers/events
SseServerEventsClient: SSE connecting (attempt=0)
SseServerEventsClient: SSE connection opened (HTTP 200)
```

Immediately after the `connection opened` line you should also see server-sync activity
(`syncCountries`, `fetchAllPages`, or similar `ServersV2SyncCoordinator` tags). This is the
`onOpen` sync trigger added in SUB-03: every successful SSE connection open fires
`syncCoordinator.sync(forceRefresh=true, cacheOnly=false)` so the server list is always fresh
on reconnect, not only when the backend sends a push event.

**Step 3 — Verify foreground / background lifecycle**

Press the Home button. Expect:

```
SseServerEventsClient: SSE client stopping
SseServerEventsClient: SSE connection closed
SseServerEventsClient: SSE reconnect loop exited
```

Return the app to the foreground. Expect the "SSE client starting / connecting / opened" sequence
to repeat from the top.

**Step 4 — Verify event-triggered sync**

When the backend emits a `servers-changed` event (requires a backend-side deployment or test tool):

```
SseServerEventsClient: SSE event received: type='servers-changed' id='...'
SseServerEventsClient: servers-changed event received; triggering server re-fetch
```

The event is emitted to an internal `MutableSharedFlow` with `debounce(500 ms)` (added in
SUB-04). This means `ServersV2SyncCoordinator` / `fetchAllPages` log lines appear at least
500 ms after the last `servers-changed` log line — not immediately. If the backend sends a
burst of events, they collapse into a single sync call after the debounce window closes.

Note: if the app just reconnected (e.g., foreground return), the `onOpen` sync (Step 2 above)
fires first (no debounce — direct call). The `servers-changed` path is a second, independent
trigger. Both call `syncCoordinator.sync(forceRefresh=true, cacheOnly=false)`.

**Diagnosing backoff**

If the SSE endpoint is unreachable the client retries with exponential backoff:

```
SseServerEventsClient: SSE connection failure (HTTP -1): ...
SseServerEventsClient: SSE reconnect in 5000ms (attempt=1)
SseServerEventsClient: SSE reconnect in 10000ms (attempt=2)
```

Delay starts at 5 s and doubles per attempt, capping at 5 min. This is expected when the
device is offline or the backend endpoint is down.

**Backoff reset — stability-threshold guard**

The backoff counter resets only when a connection is closed or fails *after* being alive for at
least 10 s (`STABLE_CONNECTION_RESET_DELAY_MS`). If the connection drops within 10 s of opening
(including after receiving events), the counter is not reset and the next reconnect uses the
next backoff delay. This prevents a tight reconnect loop when a degraded server connects, sends
events, and immediately drops the connection.

Receiving a `servers-changed` event does **not** reset the backoff counter (changed in SUB-03).
Only `onClosed` / `onFailure` with a stable-connection elapsed time ≥ 10 s resets it.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/CoreApp.kt` (`registerSseLifecycleObserver`)
- `src/docs/server-sync-flow.md` (SSE Server-Push Sync section)
- `docs/runbooks/android-qa.md` (MP-20260621 SUB-02 section)
- `docs/userstories/MP-20260621-server-push-sse/SUB-02-android-sse-client.md`

---

## Pinned Favorites section with sealed ListItem types — sectioned RecyclerView pattern

**When to use**

When building a scrollable list that displays a "pinned favorites" header + rows at the top, followed by a regular section. Favorites are hidden if empty. Long-press on any row should reflect the current favorite state ("Add to favorites" vs "Remove from favorites").

**Architecture overview**

Use a **sealed class** to represent different item types:

```kotlin
sealed class ListItem {
    data class SectionHeader(val label: String) : ListItem()
    data class Row(val item: Item, val isFavorite: Boolean) : ListItem()
}
```

The `isFavorite` boolean is computed fresh on every list build so the `PopupMenu` always shows the correct "Add" or "Remove" label.

**Step 1 — Define the sealed ListItem type**

```kotlin
sealed class ListItem {
    data class SectionHeader(val label: String) : ListItem()
    data class Row(val item: Server, val isFavorite: Boolean) : ListItem()
}
```

**Step 2 — Build the list with favorites pinned at top**

In your ViewModel's `buildItems()` method:

```kotlin
fun buildItems(): List<ListItem> {
    val favorites = favoritesFilter.filterFavoriteServers(allServers)
    val nonFavorites = allServers.filter { it !in favorites }
    
    return mutableListOf<ListItem>().apply {
        // Pinned section (hidden if empty)
        if (favorites.isNotEmpty()) {
            add(ListItem.SectionHeader("Favorites"))
            favorites.forEach { server ->
                add(ListItem.Row(server, isFavorite = true))
            }
        }
        // Regular section
        if (nonFavorites.isNotEmpty()) {
            add(ListItem.SectionHeader("All Servers"))
            nonFavorites.forEach { server ->
                add(ListItem.Row(server, isFavorite = false))
            }
        }
    }
}
```

**Step 3 — Two RecyclerView view types**

In your adapter:

```kotlin
const val VIEW_TYPE_SECTION_HEADER = 0
const val VIEW_TYPE_ROW = 1

override fun getItemViewType(position: Int): Int = when (items[position]) {
    is ListItem.SectionHeader -> VIEW_TYPE_SECTION_HEADER
    is ListItem.Row -> VIEW_TYPE_ROW
}

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
    VIEW_TYPE_SECTION_HEADER -> SectionHeaderViewHolder(...)
    VIEW_TYPE_ROW -> RowViewHolder(...)
    else -> throw IllegalArgumentException("Unknown view type: $viewType")
}
```

**Step 4 — Long-press with PopupMenu**

In your Activity, handle long-press on rows:

```kotlin
private fun onLongClickServer(anchorView: View, server: Server, isFavorite: Boolean) {
    if (server.id <= 0) return  // Legacy servers cannot be favorited
    
    showPopupMenu(anchorView) { action ->
        when (action) {
            "add_favorite" -> viewModel.toggleFavorite(server.id, favorite = true)
            "remove_favorite" -> viewModel.toggleFavorite(server.id, favorite = false)
        }
    }
}
```

Pass the long-click callback from the adapter to the Activity. See `ServersInCountryActivityCore.kt` for the complete pattern.

**TV-only variant (D-pad navigation)**

On Android TV, long-press is not available. Instead, use D-pad down / center actions to navigate and select items. Apply the same sealed-list architecture but gate the long-press menu behind `TvUtils.isTvDevice(context)`:

```kotlin
// In onLongClickServer or equivalent handler:
if (!TvUtils.isTvDevice(this)) {
    showPopupMenu(anchorView, server, isFavorite)
}
```

This pattern is reused identically across SUB-02 (countries) and SUB-03 (servers in country).

**First demonstrated**

SUB-02 (`CountriesListActivity.kt`, `CountriesListViewModel.kt`) — MP-20260706-favorite-countries-servers. Extended to servers in SUB-03 (`ServersInCountryActivityCore.kt`, `ServerListViewModel.kt`).

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/countries_list/CountriesListViewModel.kt` (`buildItems`)
- `src/mobile/src/main/java/com/yahorzabotsin/openvpnclientgate/mobile/countries_list/CountriesListActivity.kt` (PopupMenu adapter)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/servers_in_country/ServerListViewModel.kt` (`buildItems`)
- `src/mobile/src/main/java/com/yahorzabotsin/openvpnclientgate/mobile/servers_in_country/ServersInCountryActivityCore.kt` (long-press handler)
- `src/docs/favorites-ui-patterns.md`

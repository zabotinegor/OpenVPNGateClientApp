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

**Step 2 — Build the list with favorites pinned at top (ADDITIVE pattern)**

The pinned Favorites section is purely **additive**: favorited items appear in the pinned
section at the top AND remain at their normal position in the regular list below, marked
favorite by id membership. Do NOT filter favorites out of the regular list — the pinned
section is a shortcut, not a re-homing. This is the shared pattern across the countries
screen (SUB-02, `ServerListViewModel.buildItems()`) and the servers-in-country screen
(SUB-03, `CountryServersViewModel.buildItems()`); both screens must stay consistent.

In your ViewModel's `buildItems()` method:

```kotlin
fun buildItems(): List<ListItem> {
    val favorites = favoritesFilter.filterFavoriteServers(favoriteIds, allServers)

    return mutableListOf<ListItem>().apply {
        // Pinned section (hidden if empty) — additive shortcut on top
        if (favorites.isNotEmpty()) {
            add(ListItem.SectionHeader("Favorites"))
            favorites.forEach { server ->
                add(ListItem.Row(server, isFavorite = true))
            }
        }
        // Regular section: ALL items, favorites included at their normal position.
        // Mark favorite status via O(1) Set lookup (favoriteIds is a Set), not List.contains.
        allServers.forEach { server ->
            add(ListItem.Row(server, isFavorite = server.id in favoriteIds))
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

Pass the long-click callback from the adapter to the Activity. See `CountryServersActivity.kt` for the complete pattern.

**TV-only variant (D-pad long-press → dialog, not PopupMenu)**

On Android TV, `PopupMenu` doesn't anchor well to a D-pad-focused row, so TV uses a D-pad long-press (hold OK/center, delivered as `performLongClick()` on the focused row by the platform) to open a remote-navigable `AlertDialog` instead. Branch presentation with `FavoriteActionDialog.resolvePresentation(isTvDevice, canFavorite)`, which returns `NONE` / `TV_DIALOG` / `POPUP_MENU`:

```kotlin
private fun showFavoriteMenu(anchor: View, server: Server, isFavorite: Boolean) {
    when (FavoriteActionDialog.resolvePresentation(
        isTvDevice = TvUtils.isTvDevice(this),
        canFavorite = server.id > 0
    )) {
        FavoriteActionDialog.Presentation.NONE -> return
        FavoriteActionDialog.Presentation.TV_DIALOG -> {
            showTvFavoriteDialog(server, isFavorite)
            return
        }
        FavoriteActionDialog.Presentation.POPUP_MENU -> Unit // fall through to PopupMenu below
    }
    // ... existing PopupMenu path
}
```

Guard the dialog against window leaks the same way the PopupMenu path already is (dismiss any previous instance before showing a new one, dismiss in `onDestroy`, identity-checked dismiss listener). This pattern is reused identically across the countries screen (`ServerListActivity`) and servers-in-country screen (`CountryServersActivity`).

**First demonstrated**

SUB-02 (`CountriesListActivity.kt`, `CountriesListViewModel.kt`) — MP-20260706-favorite-countries-servers. Extended to servers in SUB-03 (`CountryServersActivity.kt`, `CountryServersViewModel.kt`). TV D-pad long-press dialog variant added in SUB-04 (`FavoriteActionDialog.kt`, `ServerListActivity.kt`, `CountryServersActivity.kt`).

**Testing the TV long-press with adb**

`adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` delivers a **short** press on at least some TV hardware (Xiaomi/MIBOX4), not a held key — it will not trigger the dialog. Use a held `sendevent` injection instead; see `tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md` and `docs/runbooks/solutions.md` for the working sequence.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/countries_list/CountriesListViewModel.kt` (`buildItems`)
- `src/mobile/src/main/java/com/yahorzabotsin/openvpnclientgate/mobile/countries_list/CountriesListActivity.kt` (PopupMenu adapter)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersViewModel.kt` (`buildItems`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersActivity.kt` (long-press handler, TV dialog handler)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/FavoriteActionDialog.kt`
- `src/docs/favorites-ui-patterns.md`
- `tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md`

---

## Serve a local mock backend to drive availability-driven QA (list churn, favorites hide/restore)

**When needed**

A manual QA case requires the synced server-list content to change deterministically (e.g.
SUB-05 CASE-SUB05-005: a favorited country must disappear from a sync and reappear in a later
one), but the canonical backend is hosted and cannot be mutated safely, and natural content
churn cannot be relied on in-session.

**Steps:**

1. Fetch the real payloads from the canonical backend (`PRIMARY_SERVERS_URL` from
   `servers.local.json`): `countries/active` per supported language, each per-country
   `/api/v2/servers` payload, and the legacy v1 CSV. Save them as local files.
2. Serve them with a small local Python HTTP mock bound to `127.0.0.1:18081`, mapping the API
   routes to the saved files. Have the mock re-read the countries JSON from disk per request so
   payload edits take effect on the next sync without restarting the mock.
3. Bridge the device to the host loopback: `adb reverse tcp:18081 tcp:18081`.
4. Rebuild the debug APK pointed at the mock:
   `-PPRIMARY_SERVERS_URL=http://127.0.0.1:18081 -PFALLBACK_SERVERS_URL=http://127.0.0.1:18081`.
   A cleartext loopback URL needs two LOCAL, test-only, **uncommitted** tweaks:
   - `src/core/build.gradle.kts`: allow loopback `http://` at the config-time URL guard (it
     otherwise rejects non-HTTPS endpoints at configuration time);
   - a mobile debug-manifest overlay setting `android:usesCleartextTraffic="true"`.
5. Install the mock build and confirm the runtime is actually on-mock via logcat (SSE
   `connecting url=http://127.0.0.1:18081/...`, countries/servers fetched from the mock)
   before asserting anything.
6. Drive content changes by editing the served JSON; trigger an immediate re-sync via HOME +
   reopen (SSE `onOpen` fires a `forceRefresh` sync on every foreground return).
7. Cleanup (mandatory): revert both local patches (`git checkout` / delete the overlay; verify
   `git status` is clean of QA patches), stop the mock, `adb reverse --remove tcp:18081`,
   reinstall the canonical APK, and verify the installed `base.apk` md5 equals the canonical
   build artifact.

**Notes:**

- Never commit the two build tweaks — they defeat the HTTPS-only endpoint guard.
- The mock-build APK has a different md5 than the canonical build; record both so evidence is
  attributable to the right build.
- If `adb install -r` fails with `INSTALL_FAILED_INSUFFICIENT_STORAGE`, uninstall + fresh
  install works.

**First demonstrated**

SUB-05 Manual QA (`CASE-SUB05-005-mock`, AC3 favorites availability hide/restore).

**References**

- `tests/manual-e2e/stories/SUB-05-favorites-manual-e2e/cases/CASE-SUB05-005-availability-hide-restore.md`
- `docs/runbooks/how-to.md` ("Verify SSE client connection on device" — foreground `onOpen` sync trigger)
- `src/docs/server-sync-flow.md` (sync trigger matrix)

---

## How to safely change `SpeedometerView`'s needle/label geometry ratios

**When needed**

Before touching any of `SpeedometerView.kt`'s private geometry `const val`s —
`NEEDLE_OUTER_RADIUS_RATIO`, `NEEDLE_OUTER_HALF_WIDTH_RATIO`, `LABEL_RADIUS_RATIO`,
`LABEL_TEXT_SIZE_RATIO`, or `LABEL_HALO_PADDING_RATIO` — for example to change the dial's visual
proportions, add a scale stop, or resize the needle.

**The invariant these constants must jointly satisfy**

The needle's outer tip must never enter any scale label's legibility halo. This was a real,
user-reported defect (needle tip overlapping the "0" label at rest) fixed during
`us-21-speedometer-redesign` by dropping `NEEDLE_OUTER_RADIUS_RATIO` from `0.66` to `0.45`. The
binding constraint is not the narrowest label ("0") but the *widest* one ("1000", 4 digits) at
`LABEL_RADIUS_RATIO` (0.61): its halo radius pushes its own inner edge — the closest any halo gets
to the dial center — down to roughly `0.483 * outerRadius`, while the needle tip corner sits at
roughly `0.451 * outerRadius`, leaving only ~0.032 of the outer radius as margin. See the full
worked derivation in `SpeedometerView.kt`'s KDoc directly above `NEEDLE_OUTER_RADIUS_RATIO`
(`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/SpeedometerView.kt`).

**Steps**

1. Before changing any of the ratios above, re-read the KDoc block on `NEEDLE_OUTER_RADIUS_RATIO`
   and redo its arithmetic with your new values (label text width/height scale linearly with
   `LABEL_TEXT_SIZE_RATIO`; halo radius is `hypot(width, height) / 2 + LABEL_HALO_PADDING_RATIO`).
   Confirm the clearance (`labelInnerEdge - needleTipCorner`) stays positive with headroom, not
   just non-negative — font metrics shift slightly across devices/densities/locales.
2. **No unit or component test currently pins this invariant.** All of
   `NEEDLE_OUTER_RADIUS_RATIO`/`LABEL_RADIUS_RATIO`/`LABEL_HALO_PADDING_RATIO`/
   `LABEL_TEXT_SIZE_RATIO` are `private const val`, invisible to `SpeedometerViewTest`, which only
   imports the pure companion functions. Reverting `NEEDLE_OUTER_RADIUS_RATIO` to its old `0.66`
   value (i.e. silently reintroducing the original defect) still leaves the full test suite green.
   Do not treat "tests pass" as confirmation that this specific invariant holds — verify it by hand
   (step 1) or visually, per step 3.
3. After changing the constants, take an on-device screenshot in the CONNECTED state at a value
   near each scale stop the needle can realistically point through, in **both** light and dark
   theme, and visually confirm no tip/halo overlap — mirroring the manual QA evidence at
   `docs/qa-evidence/feature-us-21-speedometer-redesign-ui/phone-v9-manualqa-light-connected.png`.
4. A component-layer regression test (bitmap comparison or geometry assertion against a
   constructed `SpeedometerView`) is not currently possible in this module — see the Robolectric
   limitation entry in `docs/runbooks/solutions.md` ("Custom `core` Views with resource-reading
   `init` blocks cannot get Robolectric component tests at all"). If that module-wide gap is ever
   closed, pinning this invariant with a real test is the first thing that should be added for
   this view.

**Notes**

- This is a documentation-only invariant today (KDoc, not code) — it is easy to weaken by accident
  when tuning visuals, and the test suite will not catch it.
- Tracked as non-blocking follow-up QG2-02 in
  `docs/qa-evidence/feature-us-21-speedometer-redesign-qualitygate-2.md`.

**First encountered**

`us-21-speedometer-redesign`, fix cycle 2 (commit `3cb9ba9`, `NEEDLE_OUTER_RADIUS_RATIO` 0.66 -> 0.45).

# How-To Runbook

This runbook collects non-obvious techniques and patterns discovered during development. Add an
entry when a technique is reusable and not obvious from the library documentation alone.

## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [Use MockWebServer in pure-JVM unit tests (no Robolectric)](#use-mockwebserver-in-pure-jvm-unit-tests-no-robolectric)
- [Hardprobe enqueue during VPN lifecycle — when it fires and when it is suppressed](#hardprobe-enqueue-during-vpn-lifecycle--when-it-fires-and-when-it-is-suppressed)
- [Verify SSE client connection on device](#verify-sse-client-connection-on-device)
- [Pinned Favorites section with sealed ListItem types — sectioned RecyclerView pattern](#pinned-favorites-section-with-sealed-listitem-types--sectioned-recyclerview-pattern)
- [Serve a local mock backend to drive availability-driven QA (list churn, favorites hide/restore)](#serve-a-local-mock-backend-to-drive-availability-driven-qa-list-churn-favorites-hiderestore)
- [How to run the OpenVPN engine's own unit tests (they are NOT part of `testDebugUnitTestApp`)](#how-to-run-the-openvpn-engines-own-unit-tests-they-are-not-part-of-testdebugunittestapp)
- [Manually verify a SharedPreferences migration path on a real device](#manually-verify-a-sharedpreferences-migration-path-on-a-real-device)

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

`ProbeRequestQueue.enqueue(serverId)` is called at five points in the VPN lifecycle — see
`docs/features/server-sync.md`'s "Hardprobe Trigger Points" section for the canonical, up-to-date
list of call sites (this file used to keep its own copy of that table; it drifted out of the two
other places it was also copied into, so it's now a single pointer instead of a third copy).

**Why `serverId == 0` is a no-op**

`SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted()` returns `0` when:

- The currently selected server IP does not match the last-started IP (user changed
  selection in the UI while connected).
- The server has no integer ID from the v2 API — this covers CSV sources like `VPNGATE`,
  which use opaque string identifiers and map to `id = 0` in the shared data model.
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
- `docs/features/server-sync.md` (Hardprobe Trigger Points section)
- the ClickUp story
- the ClickUp story

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
- `docs/features/server-sync.md` (SSE Server-Push Sync section)
- `docs/operations/device-qa-log.md` (MP-20260621 SUB-02 section)
- the ClickUp story

---

## Pinned Favorites section with sealed ListItem types — sectioned RecyclerView pattern

**When to use**

When building a scrollable list that displays a "pinned favorites" header + rows at the top, followed by a regular section. Favorites are hidden if empty. Long-press on any row should reflect the current favorite state ("Add to favorites" vs "Remove from favorites").

**Architecture overview**

This is the real shape used in production (`src/core/.../serverlist/CountryListAdapter.kt`), not a
generic sketch — use these exact names, not a reinvented `ListItem`/`Item` pair:

```kotlin
sealed interface CountryListItem {
    data class SectionHeader(val title: UiText, val showFavoriteIcon: Boolean = false) : CountryListItem
    data class CountryRow(
        val country: Country,
        val isFavorite: Boolean,
        // true only for the row instance rendered inside the pinned "Favorites" block;
        // the same favorited country also appears again lower down with isPinnedSection = false
        val isPinnedSection: Boolean = false
    ) : CountryListItem
}
```

The `isFavorite` boolean is computed fresh on every list build so the long-press menu/dialog always
shows the correct "Add"/"Remove" label. `isPinnedSection` is what lets the adapter (via
`pinnedSectionItemCount()`) tell `FavoritesSectionCardDecoration` how many leading items to draw the
pinned card background around — see `docs/features/favorites.md`'s "Visual Framing and Card
Treatment" section for that decoration mechanism, which this entry doesn't otherwise cover.

**Step 1 — Build the list with favorites pinned at top (ADDITIVE pattern)**

The pinned Favorites section is purely **additive**: favorited items appear in the pinned
section at the top AND remain at their normal position in the regular list below, marked
favorite by id membership. Do NOT filter favorites out of the regular list — the pinned
section is a shortcut, not a re-homing. This is the shared pattern across the countries
screen (SUB-02, `ServerListViewModel.buildItems()`) and the servers-in-country screen
(SUB-03, `CountryServersViewModel.buildItems()`); both screens must stay consistent.

In your ViewModel's `buildItems()` method:

```kotlin
fun buildItems(): List<CountryListItem> {
    val favorites = favoritesFilter.filterFavoriteCountries(favoriteCodes, allCountries)

    return buildList {
        // Pinned section (hidden if empty) — additive shortcut on top
        if (favorites.isNotEmpty()) {
            add(CountryListItem.SectionHeader(favoritesTitle, showFavoriteIcon = true))
            favorites.forEach { country ->
                add(CountryListItem.CountryRow(country, isFavorite = true, isPinnedSection = true))
            }
        }
        // Regular section: ALL items, favorites included at their normal position.
        // Mark favorite status via O(1) Set lookup, not List.contains.
        allCountries.forEach { country ->
            add(CountryListItem.CountryRow(country, isFavorite = country.code in favoriteCodes))
        }
    }
}
```

**Step 2 — View types keyed off the sealed interface**

```kotlin
override fun getItemViewType(position: Int): Int = when (items[position]) {
    is CountryListItem.SectionHeader -> VIEW_TYPE_HEADER
    is CountryListItem.CountryRow -> VIEW_TYPE_COUNTRY
}
```

**Step 3 — Long-press with PopupMenu**

In your Activity, handle long-press on rows:

```kotlin
private fun onLongClickServer(anchorView: View, server: Server, isFavorite: Boolean) {
    if (server.id <= 0) return  // Servers without a v2 ID cannot be favorited
    
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

SUB-02 (`ServerListActivity.kt`, `ServerListViewModel.kt` — named `CountriesList*` at the time, since
renamed) — MP-20260706-favorite-countries-servers. Extended to servers in SUB-03 (`CountryServersActivity.kt`, `CountryServersViewModel.kt`). TV D-pad long-press dialog variant added in SUB-04 (`FavoriteActionDialog.kt`, `ServerListActivity.kt`, `CountryServersActivity.kt`).

**Testing the TV long-press with adb**

`adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` delivers a **short** press on at least some TV hardware (Xiaomi/MIBOX4), not a held key — it will not trigger the dialog. Use a held `sendevent` injection instead; see `docs/operations/device-qa-tv.md` and `docs/guides/troubleshooting.md` for the working sequence.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerListViewModel.kt` (`buildItems`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerListActivity.kt` (PopupMenu adapter)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersViewModel.kt` (`buildItems`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersActivity.kt` (long-press handler, TV dialog handler)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/FavoriteActionDialog.kt`
- `docs/features/favorites.md`
- `docs/operations/device-qa-tv.md`

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

- the ClickUp QA suite
- `docs/guides/how-to.md` ("Verify SSE client connection on device" — foreground `onOpen` sync trigger)
- `docs/features/server-sync.md` (sync trigger matrix)

---

## How to run the OpenVPN engine's own unit tests (they are NOT part of `testDebugUnitTestApp`)

**When needed**

After bumping the `src/external/OpenVPNEngine` submodule, when you need to verify an engine-side unit test (e.g. an upstream test that ships with the merged commits, such as `TestTrafficHistory.kt`) actually compiles and passes against the client's resolved dependencies.

**Why this is non-obvious**

The client's aggregate unit-test task, `testDebugUnitTestApp` (defined in `src/build.gradle.kts`), depends only on `:core`, `:mobile`, and `:tv` — it does **not** depend on `:openVpnEngine`. The client CI workflow (`.github/workflows/build-by-pull-request.yml`) runs `testDebugUnitTestApp`, so it also never exercises engine-side tests. A green `testDebugUnitTestApp` run after an engine bump tells you nothing about whether the engine's own test suite (including any new upstream tests that arrived with the merge) still passes.

**Steps**

1. From `src/`, run the engine module's test task directly by its Gradle path:
   ```bash
   ./gradlew :openVpnEngine:testFullDebugUnitTest
   ```
2. To target a single test class (faster feedback while investigating one upstream change):
   ```bash
   ./gradlew :openVpnEngine:testFullDebugUnitTest --tests de.blinkt.openvpn.core.TestTrafficHistory
   ```
3. Read the HTML/XML report under `src/external/OpenVPNEngine/main/build/test-results/` and `build/reports/tests/` the same way as any other module's Gradle test report.

**Notes**

- This is a real, standing coverage gap, not just a one-off workaround: any future engine bump should include this direct run as part of validation, since neither the client aggregate task nor client CI will catch an engine-side test regression.
- The engine repository's own CI (`src/external/OpenVPNEngine/.github/workflows/build.yaml`) runs `test<target>ReleaseUnitTest` on the fork, but that only applies if Actions are enabled on the fork — it cannot be relied on as the client's safety net.
- Fixing the gap properly (adding `:openVpnEngine:testFullDebugUnitTest` to the `testDebugUnitTestApp` aggregate, or to client CI) is out of scope for a routine engine sync; treat it as a candidate for a dedicated follow-up story rather than doing it inline during an engine bump.

**First demonstrated**

US-14 (`update-openvpn-engine`) quality gate — direct run of `:openVpnEngine:testFullDebugUnitTest --tests de.blinkt.openvpn.core.TestTrafficHistory` (3/3 passed) substituted for the missing aggregate/CI coverage.

**References**

- `src/build.gradle.kts` (`testDebugUnitTestApp` aggregate task, depends on `:core`/`:mobile`/`:tv` only)
- `.github/workflows/build-by-pull-request.yml` (client CI test step)
- `src/external/OpenVPNEngine/main/src/test/java/de/blinkt/openvpn/core/TestTrafficHistory.kt`
- `.sdlc/evidence/us-14-quality-gate.md` (finding 1)

---

## Manually verify a SharedPreferences migration path on a real device

**When to use**

When a code change migrates a stale/removed persisted preference value (e.g., an enum constant
that no longer exists) to a new default, and you want to confirm the migration on a real device
rather than trust unit tests alone — particularly for values a user could plausibly still have
on disk from before the change shipped.

**Steps**

1. Confirm the debug build variant is debuggable (`android:debuggable="true"`, the default for
   `assembleDebugApp`) — `run-as` only works against a debuggable package.
2. Seed the stale value directly into the app's SharedPreferences XML file via `adb shell run-as`:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate.mobile \
     sed -i 's/server_source">[^<]*</server_source">LEGACY</' \
     /data/data/com.yahorzabotsin.openvpnclientgate.mobile/shared_prefs/user_settings.xml
   ```
   Repeat with each stale value under test (`LEGACY`, `CUSTOM`, the pre-existing legacy `"DEFAULT"`
   string, or an arbitrary corrupted string) — force-stop and relaunch the app between edits so the
   preference is re-read from disk rather than served from an in-memory cache.
3. Launch the app and confirm no crash (`adb logcat | grep FATAL` is a fast negative check), then
   confirm the migrated value is what Settings/logic actually uses going forward.

**Notes**

- This directly exercises what unit tests can only simulate: the real Android SharedPreferences
  backing store, real process launch, and the real migration code path in
  `UserSettingsStore.load()` — it does not replace unit test coverage for the migration logic
  itself, but it closes the gap between "the function returns the right value in a JVM test" and
  "an upgrading user's actual on-disk preference file doesn't crash the app."
- `run-as` requires the target package to be debug-signed/debuggable; this technique does not work
  against a release build.

**First demonstrated**

US-15 (`remove-legacy-and-custom-server-sources`) manual QA — verified the `LEGACY`/`CUSTOM`/
`"DEFAULT"` → `DEFAULT_V2` migration on a real device after removing those enum values, confirming
no crash and correct fallback for users upgrading from a build where a removed source was selected.

**References**

- `src/core/.../settings/UserSettingsStore.kt` (`load()` migration logic)
- the ClickUp QA suite (manual QA case files)

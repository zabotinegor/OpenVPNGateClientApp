# Release Review — Pass 1 (Correctness & Integration)

**Branch:** `feature/release/04.07.2026`
**Diff scope:** `origin/main...HEAD` (5 commits: `d3ca0ab`, `9dd3004`, `9ef8475`, `6aa4b33`, `a0a97d4`)
**Actual diff size:** 95 files changed, +6153/-131 (task brief said 66/+5420/-1074; verified actual numbers via `git diff --stat`)

## Scope covered

- `SseServerEventsClient.kt` (new) — SSE long-poll client, lifecycle-bound, exponential backoff, URL rotation.
- `MainConnectionInteractor.kt` / `MainSelectionInteractor.kt` — server-counter-reset-on-connect fix (fresh config read at Connect time, config-first index resolution).
- `MainViewModel.kt` — `pendingUserSelectionOverride` / `isBackgroundRefresh` guards against background sync clobbering an in-flight user selection.
- `OpenVpnService.kt` — `@Volatile` on cross-thread state flags, FGS-notification-exit guard (`reconnectPending`), `userInitiatedStart` clear on `LEVEL_CONNECTED`, hardprobe enqueue on disconnect.
- `ServerAutoSwitcher.kt` — hardprobe enqueue on `DEFAULT_V2` hydration path, `resetForTest()`.
- `CoreDi.kt` / `CoreApp.kt` / `AppConstants.kt` — SSE client DI wiring, `ProcessLifecycleOwner` registration, new `sseServersEventsUrl()` route.
- `build.gradle.kts`, `libs.versions.toml`, `consumer-rules.pro` — new `okhttp-sse` / `lifecycle-process` deps, R8 keep rule for `ProbeApi`.
- Tooling/config carryover (`.claude/`, `.githooks/`, `.github/scripts/`, `AGENTS.md`, `AGENTS/*`, `.gitignore`) across all 5 commits, not only `a0a97d4`.

## Findings

### Cross-story interaction: SSE → server sync → selection state

The SSE client's `doSync()` calls `syncCoordinator.sync(forceRefresh = true, cacheOnly = false)` on every `onOpen` and on debounced `servers-changed` events. This is the same sync path that previously overwrote the user's server selection (root cause of `bug-server-counter-resets-on-connect`). Verified the interaction is safe:

- `MainViewModel` now checks `pendingUserSelectionOverride` before applying any background-sourced selection (`isBackgroundRefresh = true` at all 3 call sites: startup preload x2, periodic/foreground refresh x1).
- `MainConnectionInteractor.prepareStart()` independently re-reads `SelectedCountryStore.currentServer()` fresh at Connect time when `preferUserSelection = true`, so even if a background SSE-triggered sync updates the on-disk config between selection and Connect, the interactor no longer trusts a stale in-memory `selectedServer.config`.
- These two defenses are complementary (ViewModel-level guard + interactor-level fresh read) and do not conflict — no double-guard race identified.

**Verdict: no regression.** The SSE feature (`d3ca0ab`) predates the counter-reset fix (`6aa4b33`) chronologically in the story order but the fix in `6aa4b33`/`9dd3004` correctly accounts for SSE as a background-sync trigger.

### Cross-story interaction: OpenVpnService FGS guard vs. hardprobe enqueue

`syncEngineState()` gained a `reconnectPending` guard (from the FGS-crash fix, `9ef8475`) that suppresses `exitControllerForeground()` while `reconnectingHint` or `userInitiatedStart` is true. The same method block (from `9dd3004`/US-12) now also enqueues a hardprobe on `LEVEL_NOTCONNECTED`/`LEVEL_NONETWORK`-driven stop. These two additions sit in the same function but touch disjoint state (`controllerForegroundActive` exit vs. `probeQueue.enqueue`) and are ordered so the probe enqueue happens in `startUserStopTeardown` (explicit stop path), not inside `syncEngineState`'s idle-guard branch — confirmed no shared mutable state is written by both in a conflicting order. `probeQueue?.enqueue()` is null-safe and wrapped in try/catch, consistent with the SSE client's own defensive `Exception` handling style.

`userInitiatedStart` and `controllerForegroundActive` are correctly marked `@Volatile` given they are written on the main thread and read from the AIDL binder thread — this predates and is unrelated to the SSE change, but is exercised by the same service class the SSE-triggered sync eventually influences (server selection persisted before Connect). No shared-mutability issue introduced by combining the two stories.

### R8 / packaging risk

`consumer-rules.pro` keeps `ProbeApi` (Retrofit generic-signature crash fix) but does **not** add any keep rule for `SseServerEventsClient`, `okhttp-sse`'s `EventSourceListener`, or OkHttp SSE internals. This is consistent with the class not using reflection-based generic Retrofit calls (it uses raw `OkHttpClient` + `EventSources.createFactory`), so no rule should be needed — okhttp/okio's own consumer proguard rules cover this. Not a blocking finding, but flagging as a discussion item: recommend a release-build (`isMinifyEnabled=true`) smoke test of SSE connect/reconnect before shipping, since this is the first minified build to exercise the new `okhttp-sse` dependency end-to-end. (See Pass 2 for confirmation this is non-blocking given existing manual QA evidence.)

### `defaultSseUrls()` fallback edge case

`defaultSseUrls()` falls back to a hardcoded placeholder `https://openvpnclientgate.local/api/v1/servers/events` only when `PrimaryDomainRoutes.sseServersEventsUrl(PRIMARY_SERVERS_URL)` returns null (malformed `PRIMARY_SERVERS_URL`). This placeholder is non-resolvable by design (intentional inert fallback, matching the class doc's stated behavior — WorkManager periodic refresh remains the real safety net). Not a hardcoded *production* URL per `AGENTS.md` convention since it's an inert placeholder, not a real endpoint. No violation.

### Tooling commit scope

`a0a97d4` alone is config-only (`.claude/settings.json`, `.githooks/pre-commit`, `.github/agents/agent-sync.agent.md`, `.github/scripts/sync-copilot-assets.ps1`, `.github/scripts/validate-agent-skill-definitions.ps1`, `.github/skills/agent-sync/SKILL.md`, `.gitignore`, `AGENTS.md` — 8 files, +381/-57). Verified via `git show --stat a0a97d4`.

However, tooling/config drift is **not confined to `a0a97d4`** — `.claude/commands/agent-sync.md`, `AGENTS/*.md`, `.github/scripts/protect-agent-git-command.{py,ps1}`, and `.githooks/pre-push` were introduced across `d3ca0ab`, `9dd3004`, and `6aa4b33` (each story's own agent-sync side effect). Reviewed all tooling diffs (`.claude/settings.json`, `.githooks/pre-commit`, `.claude/hooks/block-push-to-main.ps1`): all are session-limit tracking, push-protection, and agent-sync hooks — no secrets, no changes to build/release scripts, no functional app code touched. **Config-only claim holds for the full tooling surface, not just `a0a97d4`.**

### Build/test validation

Ran `./gradlew testDebugUnitTestApp` from `src/` on the actual branch HEAD: **BUILD SUCCESSFUL**, 651 unit tests, 0 failures (aggregated from `core/build/test-results/testDebugUnitTest/*.xml`). This confirms all 4 stories' test suites compile and pass together, with no cross-story test interference.

## Risk register (Pass 1)

| # | Area | Severity | Status |
|---|------|----------|--------|
| 1 | SSE-triggered sync vs. selection override | Medium (pre-mitigated) | Verified safe — dual guard confirmed |
| 2 | FGS guard vs. hardprobe enqueue ordering | Medium (pre-mitigated) | Verified safe — disjoint state |
| 3 | R8 rules for new okhttp-sse dependency | Low | Discussion item — recommend release-variant smoke test |
| 4 | Tooling commit scope broader than described | Info | Confirmed config-only across all commits |

No blocking findings in Pass 1.

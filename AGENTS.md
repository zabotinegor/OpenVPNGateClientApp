# Project Guidelines

## Project Overview
- This repository contains an Android VPN client for VPN Gate and compatible server lists.
- The Gradle root is `src/`, not the repository root.
- The app ships as two launcher apps, `src/mobile` and `src/tv`, over one shared logic module, `src/core`.
- `src/openVpnEngine` points to `src/external/OpenVPNEngine/main`, which is an external engine submodule and should be treated as a high-risk integration boundary.
- The related backend/API codebase is local-only and must be resolved from untracked `AGENTS.local.md` at repo root.
- If `AGENTS.local.md` is missing, ask the user for the local backend path and do not add it to tracked files.

## Local Overlay (AGENTS.local.md)
- `AGENTS.local.md` at the repo root is the single place for **machine-specific paths and local-only context** that agents need but that must never be committed.
- Typical content: local path to the backend server repository, local deployment URLs, notes about the current machine's toolchain.
- **Do NOT store secrets** (passwords, tokens, signing keys) in `AGENTS.local.md`. Use `src/keystore.properties` for signing and `servers.local.json` for build-time endpoints.
- If `AGENTS.local.md` is absent, agents must ask the user for the missing information instead of guessing or hard-coding paths.
- `README.local.md` follows the same convention for human-readable local setup notes.

## Build and Test
- Run Gradle commands from `src/`.
- Prefer the aggregate tasks defined in `src/build.gradle.kts`:
  - `./gradlew assembleDebugApp`
  - `./gradlew testDebugUnitTestApp`
  - `./gradlew connectedDebugAndroidTestApp` (requires a connected ADB device; runs Espresso instrumented tests for core and mobile)
  - `./gradlew connectedDebugAndroidTestTv` (requires a Leanback-capable ADB target; runs Espresso instrumented tests for tv)
  - `./gradlew assembleReleaseApp -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...`
  - `./gradlew bundleReleaseApp -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...`
- Signed release builds need `src/keystore.properties` and the referenced keystore file. Local release builds may be produced unsigned when this file is absent.
- Before any build that touches resources or native code, initialize submodules: `git submodule update --init --recursive`.

## Architecture
- `src/core` owns almost all business logic: VPN orchestration, repositories, settings, networking, logging, and shared UI flows.
- `src/mobile` and `src/tv` should stay thin. Keep feature logic out of these modules unless it is launcher-specific.
- Koin is the DI container. Use `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt` as the source of truth for wiring.
- The shared application ID and package base are intentional. Do not split package names between mobile and tv without understanding VPN permission and signing implications.

## Conventions
- Branch naming follows `feature/<feature_name>` for feature branches, `bugfix/<issue>` for bug fixes, and `hotfix/<issue>` for urgent hotfixes. Use lowercase with hyphens for multi-word features.
- Keep new domain and UI logic in `src/core` unless the change is genuinely mobile-only or tv-only.
- Use Timber for logging. Follow `docs/features/logging.md`; do not introduce `android.util.Log` for app code.
- Do not log secrets, raw credentials, or full sensitive URLs.
- Build-time server endpoints come from Gradle properties, environment variables, or `servers.local.json`, in that order. Do not hardcode production endpoints in source files.
- If a task changes API contracts for updates, releases, version metadata, or server-list payloads, inspect the backend implementation using the local path from `AGENTS.local.md` and keep client/server formats aligned.
- `app_name` is injected via Gradle `resValue`; do not duplicate it in shared string resources unless the build logic changes.
- This project uses ViewBinding and Kotlin-based Android modules. Match the existing style instead of introducing a new UI or DI pattern.

## OpenVPN Engine Update Workflow (Local AI Agents)
- Context:
  - Engine repository: `https://github.com/zabotinegor/OpenVPNGateClientEngine`.
  - Engine fork source: `schwabe/ics-openvpn`.
  - Integration intent: keep the engine changes minimal and preserve the library shape used by this app.
  - Submodule declaration and target branch are defined in `.gitmodules`.
- Required update flow:
  1. Synchronize upstream branch from `schwabe/ics-openvpn` into `OpenVPNGateClientEngine` main.
  2. Merge `main` into `OpenVPNClientApp-integration` branch in the engine repository.
  3. Resolve conflicts minimally, preserving this repository's engine-as-library behavior.
  4. In this client repository, initialize submodules and run app validation builds/tests from `src/`.
  5. Update integration branches used by the app and update the active feature branch as needed.
  6. Refresh markdown documentation when behavior, process, or constraints change.
- Validation baseline after engine update:
  - `./gradlew assembleDebugApp`
  - `./gradlew testDebugUnitTestApp`
  - For release verification, use `assembleReleaseApp` or `bundleReleaseApp` with required `-P` properties.
  - `testDebugUnitTestApp` does not run the engine's own unit tests (`:openVpnEngine` is not a dependency of that aggregate task). Run `./gradlew :openVpnEngine:testFullDebugUnitTest` directly when the merged upstream commits add or change engine-side tests. See `docs/guides/how-to.md`.
  - If the merged upstream commits raise the engine module's `compileSdk`/`targetSdk`, the first build on a machine without that SDK Platform installed can fail with `Failed to find target with hash string 'android-NN'`; retry once the SDK manager installs it (or install it explicitly). See `docs/guides/troubleshooting.md`.
  - Run the full regression checklist in `docs/guides/engine-update.md` (cold launch, server-list load, VPN connect/watchdog/disconnect, notification-tap regression, full-session stability) before trusting the merge.
- Safety constraints:
  - Do not perform incidental refactors in `src/external/OpenVPNEngine` during conflict resolution.
  - Keep module wiring intact: `:openVpnEngine` must continue to map to `src/external/OpenVPNEngine/main`.

## Project-Specific Pitfalls
- `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` are required for builds through `src/core/build.gradle.kts`. `PRIMARY_SERVERS_URL` is the trusted backend base URL, and the client derives legacy CSV, v2 API, release-note, and update-check routes from it. Missing values fail the build.
- `src/copy_drawables.gradle.kts` copies required launcher assets from the `media` submodule. If the expected files are missing, builds fail before packaging.
- `src/core/src/main/AndroidManifest.xml` contains the VPN service declaration for Android special-use foreground services. Be careful when editing service, permission, or exported settings there.
- `src/external/OpenVPNEngine` is an upstream integration area. Avoid incidental edits there unless the task explicitly requires engine changes.
- Release build hardening is intentional: `src/mobile` `release` and `src/tv` `release` must keep `isMinifyEnabled=true` and `isShrinkResources=true`.
- Preserve each module's current `jniLibs.useLegacyPackaging` setting; do not change these as cleanup without a concrete need.

## Agent Documentation Governance
- Keep AI-agent governance docs synchronized when workflow instructions change:
  - .github/AGENTS-REGISTRY.md
  - .github/FRONTMATTER-SCHEMA.md
- Keep local overlays aligned with global docs while preserving local-only constraints:
  - README.local.md
  - AGENTS.local.md
- For docs-only maintenance tasks, follow .github/agents/docs-maintainer.agent.md and .github/skills/docs-maintenance/SKILL.md. These are local-only, gitignored, and mirrored via the `agent-sync` skill — if absent (fresh checkout without agent-sync run), treat as not-yet-provisioned and ask the user to run `agent-sync` rather than assuming the workflow doesn't apply or silently skipping it (same pattern as `AGENTS.local.md`).
- Android device E2E references are documented by suite identifiers in test KDoc and local testing notes; keep them out of `.github/skills/` unless the catalog is explicitly added to this repository.

## Long-Running Operation Rules

Required builds, tests, migrations, validation, deploys, CI checks, browser/mobile sessions, and background jobs must run in foreground shell until exit code — through a real tool callback, or through `.github/scripts/invoke-long-operation.ps1` with `.sdlc/operations/*/status.json` polling. Fire-and-forget VS Code tasks are forbidden for required validation unless completion status, exit code, and recent logs are readable by the agent.

Terminal execution is capability-based: use any terminal-capable tool exposed in the current session (`run_in_terminal`, `execute`, `runCommands`). Do not stop solely because one specific tool ID is unavailable.

## Prompt-Generation Responses

When generating a handoff prompt at user or agent request, return exactly one fenced ` ```text ` block with no text outside it. The block must contain the full concrete prompt payload ready to paste into a new chat. Do not create persistent handoff/prompt markdown files (`*_HANDOFF*.md`, `*_PROMPT*.md`); return handoffs in chat output or handoff buttons unless the user explicitly requests a file. Remove any accidental transient prompt artifacts before final output.

## SDLC Status Updates

Update SDLC flow state through `.github/scripts/update-sdlc-status.ps1` using named parameters only — never positional shorthand. Always include `-FlowId`, `-Branch`, `-Step`, `-Status`, `-StoryId`, `-StoryPath`, and `-ValidatePriorSteps` for the current step. Read `.sdlc/status.json` before starting work to verify prerequisite step statuses. See `.github/skills/shared/sdlc-status-gate.md` for the full parameter reference and per-skill prior-step table.

## SDLC Minimum Report Contract

All SDLC handoff and execution outputs must include: what was done, what went wrong or failed (or explicit `none`), what was fixed or changed, evidence, what remains or next actions, and blockers or errors (or explicit `none`). Role-specific extras are allowed.

## Docs to Link Instead of Rewriting
- `README.md` for repository layout, prerequisites, signing, media assets, runtime behavior, and release commands.
- `docs/INDEX.md` for the full technical knowledge-base catalog — flow/behavior docs, bug postmortems, how-to guides, and device QA runbooks. This is the one entry point; read the relevant catalog row rather than re-deriving or re-documenting something the catalog already covers.
- `PRIVACY_POLICY.md` and `TERMS.md` for user-facing policy text.
- `LICENSE` and `src/external/OpenVPNEngine/doc/LICENSE.txt` for redistribution and licensing context.

## Useful Starting Points
- `src/build.gradle.kts` for aggregate app tasks (including `connectedDebugAndroidTestApp` for non-TV device instrumented tests and `connectedDebugAndroidTestTv` for TV/Leanback device instrumented tests).
- `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivitySmokeTest.kt` for Android mobile smoke suite identifiers used by device E2E execution.
- `src/core/build.gradle.kts` for required build configuration and generated `BuildConfig` fields.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt` for DI wiring.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashActivityCore.kt` for the shared splash/startup flow.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt` for startup preload behavior.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt` for the shared main UI flow.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt` for the shared server-list synchronization entrypoint used by splash, main foreground, settings changes, and periodic refresh.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/refresh/ServerRefreshWorker.kt` for periodic sync execution that reuses the shared coordinator.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt` for server source changes that trigger forced server sync.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt` for VPN lifecycle integration.

## When Extending Instructions
- If the repository later needs module-specific guidance, add nested `AGENTS.md` files under `src/core`, `src/mobile`, `src/tv`, or `src/external/OpenVPNEngine` instead of overloading this root file.

## Local-Only Agent Tooling

- Most session/SDLC helper scripts under `.github/scripts/` (for example `init-session.ps1`, `check-rate-limit.ps1`, `checkpoint-session.ps1`, `resume-session.ps1`, `update-sdlc-status.ps1`, `resolve-pr-threads.ps1`) and most `.github/agents/` / `.github/skills/` definitions are **local-only**: they are gitignored and mirrored onto each machine from the CopilotTools repository via the `agent-sync` skill. Only a small allowlist (such as `protect-agent-git-command.*` and `sync-copilot-assets.ps1`) is committed.
- On a fresh checkout these local-only files are absent. Provision them by running the `agent-sync` skill (or ask the user to run the CopilotTools mirror sync) before following any instruction that invokes them — including the "MANDATORY FIRST STEP — Session Limit Check" section below.
- If the scripts remain unavailable after attempting provisioning, treat the session-limit bootstrap steps as not applicable, report the missing tooling to the user, and continue only with work that does not depend on them.

<!-- BEGIN COPILOT SYNC -->
## MANDATORY FIRST STEP — Session Recovery Check

Before reading any other file or taking any action, every agent must:

1. Run `pwsh -File .github/scripts/init-session.ps1` — creates/refreshes `.sdlc/session.json` with `resetsAtUtc` (when the current usage window resets). There is no usage-percentage tracking or polling: no continuous CDP/Chrome fetch, no background watchdog, no per-tool-call check. `resetsAtUtc` only gets a rare live refresh (inside `init-session.ps1` itself) when there is no valid cached window boundary.
2. **Top-level unfinished work must arm recovery before normal tools, unconditionally — not gated on any usage level:** register the canonical resume command with `manage-session-resume.ps1 -Action SetIntent`, obey its directive, create one one-shot `CronCreate` for `resetsAtUtc + 2 minutes`, verify the job with `CronList`, and record it with `-Action Confirm`. Do not continue merely because the transcript says `Used ScheduleWakeup`; provider listing plus `Confirm` is required. **If the PreToolUse gate denies a call, never answer it by retrying that call** - run `manage-session-resume.ps1 -Action Directive` and execute its `steps` array in order, exactly as written. Those steps are the remedy as literal ordered tool calls with every argument already filled in, and `Confirm` derives the rest, so you only supply the recovery key and the job id `CronList` just reported. Subagents remain schedule-free — the orchestrator owns this **unconditionally, regardless of which tools the subagent happens to have loaded**: a subagent that has loaded `CronCreate`/`CronDelete`/`CronList`/`manage-session-resume.ps1 -Action Confirm|SetIntent|Complete` via the same `ToolSearch` mechanism the orchestrator uses must still never call them for a flow's recovery key — tool availability is not a structural guarantee that a caller is the orchestrator, only the top-level orchestrator session may arm, confirm, or replace a flow's recovery cron. **If the PreToolUse gate denies a subagent's own tool call with this same reset-recovery message anyway** (it cannot tell a subagent's calls apart from the orchestrator's own — both share one session file), the remedy above is still not the subagent's to run, whether or not it could technically load the tools to attempt it. Do not retry it and do not attempt the remedy. Read stays available and Write is available for checkpoint/evidence paths (`.sdlc/`, `docs/qa-evidence/`) even while denied — checkpoint what is done there, then end the turn and return `GATE: BLOCKED`, `REASON: reset-recovery-unconfirmed` (same convention as the lease-conflict case in item 3) so the orchestrator can re-arm and re-spawn. Checkpoint unfinished work via `.sdlc/status.json` substeps as you go — that checkpoint trail, plus the always-armed cron, is what makes work resumable, not a percentage trip-wire. For SDLC orchestrators, **flow start means invocation, not branch creation**: a `FlowId` is a branch name and does not exist yet during intake, reproduction, BA, or story approval, so arm a `-Type task -ResumeAgent <flow-slash-command>` intent as the first action after `init-session.ps1` and let the later `-Type flow -FlowId <branch>` call promote it — the manager retires the placeholder and re-attaches its cron as `staleJobId`, so the directive comes back as `replace` and the usual CronDelete → CronCreate → Confirm completes the handover.
3. **Flow lease (enforced):** every flow carries a 15-minute lease enforced by `update-sdlc-status.ps1`. Capture your `sessionId` once at flow start and pass `-SessionId` on every status write. At flow start/resume/wakeup: `-Lease check -SessionId <yours>`; exit 0 → `-Lease acquire` and proceed; exit 2 → another session is live — do not touch the flow; schedule a fresh wakeup (~15 min) or ask the user. Use `-Lease acquire -TakeOver` only on an explicit user handoff. A status write rejected with exit 2 means the lease was lost — stop immediately, cancel your own scheduled wakeups/cron jobs for the flow, report the handoff in one line. Before asking the user a blocking question, run `-Lease renew -WaitingForUser` — a waiting lease never expires and can only be displaced by an explicit `-TakeOver`; when the answer arrives, re-run `-Lease check` before acting (the next status write clears the mark). A session hitting a waiting lease reports the pending question to the user instead of scheduling retry wakeups. Run `-Lease release` at flow completion and when checkpointing for a long sleep. Subagents never take over; on exit 2 they return `GATE: BLOCKED`, `REASON: lease-conflict`.
4. **Recovery and user-wait guard:** unfinished top-level work keeps one verified reset cron, waiting on the user or not. Before `AskUserQuestion`, mark the lease `WaitingForUser` and suspend (this only records the pending question — it does not touch the cron). Waiting for user input is normal, never `blocked`, and does not exempt the flow from needing a current, confirmed cron: if the window rolls over during the wait, a rearm is required exactly as it would be for active work. On reply, clear waiting state and re-read SDLC state; rearm only if the directive shows the cron went stale during the wait. The PreToolUse gate denies ordinary work while a registered intent lacks a confirmed current-window cron, and the Stop hook refuses to end the turn on one, so an unarmed flow is stopped rather than losing its recovery silently. If the gate reports the due time has already passed, the window boundary is stale: run `init-session.ps1` (exempt from the gate for exactly that reason) rather than arming another cron against the same dead reset. If scheduling fails, retry once then record the manual fallback without claiming automatic recovery.
5. After ANY wakeup, auto-resume, or session restore: re-read `.sdlc/status.json` (steps, substeps, `lastUpdatedUtc`) and re-derive the entry step from the flow's resume table before acting — never resume from wakeup reason text, conversation memory, or `checkpoint.currentStep` alone; another session (possibly another account) may have advanced the flow while this one slept.

This applies whether the agent is invoked inside an orchestrator flow or independently by the user.

**One exemption: `agent-sync`.** It skips this entire section — no `init-session.ps1`, no usage or reset-time lookup, no recovery cron, no `.sdlc/status.json` checkpoint, no lease, no `check-tracking-preflight.ps1`. Agent Sync installs the session-tracking stack; it does not run on it, and gating a short idempotent file copy behind Chrome launches and account questions cost more than the interruption it was protecting against. An interrupted sync is recovered by re-running it. No other agent has this exemption.

This exemption is **mechanically enforced**, not just documented: `check-session-before-tool.ps1` allows a subagent spawn whose prompt opens with `/agent-sync`, plus the sync/setup toolchain commands themselves, so a sync still runs when an unrelated flow on the same branch has unarmed or stale recovery. Agent Sync therefore never needs to arm a cron, acquire a lease, resolve another flow's recovery state, or switch branches to get itself unblocked — if a sync appears blocked by flow machinery, that is a bug in the gate, not a state the agent should try to satisfy. Outward-facing git (`push`, `commit`, `merge`) stays gated for it exactly as for everyone else, which is consistent with Agent Sync never committing.

## Core Principles

- Prioritize correctness over speed.
- Prefer the smallest safe change that solves the request.
- Preserve existing architecture and naming patterns.
- Do not introduce unrelated refactors.
- Never expose secrets, tokens, credentials, or private keys.

## Execution Flow

1. Understand the user goal and expected output.
2. Inspect relevant files before editing.
3. Implement minimal focused changes.
4. Run validation steps that match the change scope.
5. Summarize what changed and why.

## Editing Rules

- Keep files ASCII unless non-ASCII is required by existing content.
- Reuse existing patterns from nearby files.
- Add brief comments only where logic is non-obvious.
- Avoid formatting-only churn in unrelated code.
- If requirements conflict, prefer safety and explicit assumptions.
- Do not create persistent handoff or prompt artifact files such as `*_HANDOFF*.md`, `*_PROMPT*.md`, `*_PROMT*.md`, `CODE_REVIEW_HANDOFF_*.md`, or chat handoff markdown files unless the user explicitly asks for a file. Return handoffs in chat output or handoff buttons instead.
- If a handoff/prompt artifact file is created accidentally, remove it before final output and return the same handoff content in chat or a handoff button. Do not report success while forbidden handoff artifacts remain in the worktree.
- Real product, test, or helper scripts are allowed when required by the requested implementation or validation; do not create script-like prompt files just to pass instructions between agents.
- Do not reference user story numbers, acceptance criteria identifiers, SDLC step names, or internal tracking IDs in code comments, commit messages, or PR descriptions.

## Validation Rules

- Run the narrowest useful checks first.
- If tests are unavailable, run lint/build or static checks when possible.
- Developer-side validation includes not only tests, but also debugging and target environment or device readiness checks when those are relevant to changed behavior.
- If validation cannot be run, explicitly state why.
- Do not claim success without evidence.

## Long-Running Operation Rules

- Agents must not abandon long-running commands, builds, tests, migrations, dev servers, CI checks, browser/mobile sessions, or background jobs after saying they are waiting.
- Required validation/build/test/migration/deploy/check operations must run in a tracked mode: direct foreground shell execution until exit code, a tool with a real completion callback, or `.github/scripts/invoke-long-operation.ps1` with status polling.
- Treat terminal execution as a capability, not a fixed tool ID. Use any available terminal-capable tool exposed in the session (for example `run_in_terminal`, `execute`, or `runCommands`) and do not stop solely because one specific tool name is unavailable.
- If a terminal-capable call routes into a VS Code task launcher (for example, a task label starts and reports `Canceled`), treat it as a task-launcher path and switch to direct terminal tools (`run_in_terminal` or `runCommands`) before declaring terminal execution unavailable.
- Do not use VS Code tasks, "Run task", or any fire-and-forget launcher for required operations unless the tool exposes reliable completion status, exit code, and recent logs to the agent. If a required task was started without trackable completion, switch to an equivalent shell/supervised command or stop with a concrete blocker.
- Do not create or modify `.vscode/tasks.json` to run required operations unless the user explicitly asks for a persistent VS Code task. Prefer direct shell commands or `.github/scripts/invoke-long-operation.ps1`.
- For agent-sync operations, treat root markdown files (`AGENTS.md`, `README.md`, `AGENTS.local.md`, `README.local.md`) as protected by default; sync them only with explicit user approval and explicit script opt-in.
- When starting a long operation, state what is running, what signal will prove completion, the tracking method, and the polling cadence or timeout.
- Poll until the operation completes, fails, times out, is cancelled, or reaches a documented user-action blocker. Use process status, terminal output, supervisor status files, health checks, log tails, CI status APIs, browser/device readiness, or file/output changes as applicable.
- Send concise progress updates during long waits, at least every 5 minutes or whenever the observed state changes. Include elapsed time, current state, and next check.
- Do not provide a final answer while a required operation is still running unless the user explicitly asked to leave it running. In that case, report the process/session identifier, how to check it, and what remains.
- **Subagent boundary rule:** a subagent terminates permanently when it returns its final message — it can never "stand by", "wait for a notification", or "resume automatically", and its background processes lose their consumer the moment it returns. A subagent must poll its own detached/background operations to a terminal status within the same turn, or return `GATE: BLOCKED`, `REASON: pending-operation` with the concrete operation handle (`.sdlc/operations/<id>` path, background task id, or exact command) so the parent takes ownership. "Started X in background, standing by" is a forbidden subagent final message.
- **Zero live children is proof of termination, not background progress:** a background shell, `Monitor` task, or dispatched worker that shows no live entry — killed, exited, or missing from the Background Tasks panel — has nothing left to notify anyone; there is no later turn it will interrupt. Reporting it as "still running", "monitoring it in the background", or planning to "wait for its completion notification" past that point is a fabricated claim, not optimism — reproduced twice in separate sessions after being told explicitly to run synchronously. Re-verify liveness before making or repeating such a claim. Then either block synchronously in the current turn, poll a real handle via `.github/scripts/invoke-long-operation.ps1`, or — only when the operation must truly outlive this session — launch it OS-detached (PowerShell `Start-Process`) and confirm progress with sequential foreground polling (`Get-Process` liveness plus a log tail), never a notification you have no way to receive.
- **Waiting-turn guard (top-level sessions and orchestrators):** end a turn waiting only on work tracked in your OWN session (your own background shell, background subagent task, or scheduled wakeup). Never end a turn waiting on an operation owned by a returned subagent — take ownership first: poll its status file, re-run it, or re-spawn the specialist. Whenever a turn ends waiting for any completion signal, also schedule a fallback `ScheduleWakeup` (1200-1800 s, reason `idle-guard: waiting for <operation>`) so a missed notification degrades into a delayed resume instead of a permanent flow hang.
- If an operation exceeds its expected duration, extend polling with a clear reason or stop with a concrete blocker. Do not silently wait indefinitely.
- Callback-style behavior is implemented as a real tool/platform completion callback when available; otherwise use foreground execution or `.github/scripts/invoke-long-operation.ps1` and poll `.sdlc/operations/*/status.json`.
- On resume after interruption or context restoration, inspect `.sdlc/operations/*/status.json` before restarting required work. Continue polling running operations, consume completed exit codes/log tails, or report a blocker if the process disappeared without a terminal status.

## Session Recovery Rules

Every agent must keep unfinished work checkpointed and its reset-recovery cron armed. See the **MANDATORY FIRST STEP** section above and `.github/skills/shared/operational-rules.md` for the authoritative rules.

Summary:
- No usage-percentage tracking or polling — `resetsAtUtc` comes from `init-session.ps1`'s rare live refresh, not continuous checks.
- Unfinished top-level work keeps one verified reset cron, always — recovery arming does not depend on any usage level, and waiting on the user does not exempt it either.
- On new session: run `pwsh -File .github/scripts/resume-session.ps1` to detect and auto-resume from checkpoint.
- `agent-sync` is exempt from all of the above — see the exemption note in **MANDATORY FIRST STEP**.
- Full workflow: `.github/skills/session-limit-tracking/SKILL.md`.

## Git Rules

- Use clear commit messages in past tense.
- Commit only relevant files.
- Do not rewrite history unless explicitly requested.
- Do not use destructive commands on user work.
- After successful validation, commit only relevant files and push to the target branch. Do not commit or push when validation failed, relevant files cannot be isolated, or unrelated user changes would be included.
- For Manual QA and evidence-heavy workflows, do not commit raw/noisy artifacts (for example screenshots, full logs, videos, generated reports, temporary exports, crash dumps, large binaries) unless the user explicitly requested persistent storage and approved their scope.
- Do not use broad staging (`git add .`, `git add -A`) in Manual QA flows; stage explicit approved files only.
- Manual QA commit allowlist is mandatory by default. Always allowed: `tests/manual-e2e/environment/**/*.md` and the QA knowledge index path. Additionally allowed **only when the repository has no `.sdlc/clickup-config.json`** (local artifact mode): `tests/manual-e2e/stories/**/specs/**`, `tests/manual-e2e/stories/**/cases/**`, `tests/manual-e2e/stories/**/suites/**`. When that config is present the repository stores story/QA content in ClickUp, and staging anything under `tests/manual-e2e/stories/` is itself a violation — it reintroduces content that was deliberately migrated out of git. If staged files are outside the allowlist for the active mode, agents must stop with `BLOCKED` until the user explicitly approves additional paths.

## Knowledge Documentation Standard

Every agent must document any discovery that would save time in a future session. This is mandatory, not optional.

**Document when:**
- A non-obvious problem was encountered and solved (e.g., a specific error with a known fix).
- A how-to was worked out during the session (e.g., how to generate a JWT token for this project, how to seed test data).
- An environment quirk or gotcha was discovered (e.g., a service must be started in a specific order, a port conflict).
- A workaround was applied (e.g., a library bug workaround, a platform-specific build flag).
- A command or pattern was discovered that is not obvious from the codebase.
- A QA or testing trick proved effective (e.g., a specific test account, a shortcut to reproduce a scenario).

**Do not document:**
- Things already obvious from README or standard docs.
- Secrets, tokens, passwords, or credentials — never write actual values.
- Personal preferences or style opinions.

**Where to write:**

First check whether this repository declares its own knowledge-base catalog — a line in its
`AGENTS.md` or `CLAUDE.md` pointing at a catalog file, for example `docs/INDEX.md` or
`src/docs/INDEX.md`. **If one is declared, it wins.** Read it, write the new doc where its
conventions say, and update its catalog row in the same change. Also update the target file's own
`## Index` block if it has one. Do not fall back to the table below in that case — a repo with a
catalog may have no `docs/runbooks/` directory at all.

Otherwise, when no catalog is declared, knowledge lives under `docs/runbooks/`:

| File | Content |
|---|---|
| `docs/runbooks/environment-setup.md` | Start commands, env var names, service dependencies, startup order |
| `docs/runbooks/api-testing.md` | Endpoint list, auth patterns, test data setup |
| `docs/runbooks/solutions.md` | Specific problems solved: error messages, root causes, fixes |
| `docs/runbooks/how-to.md` | Step-by-step guides: generate JWT, seed DB, trigger a webhook, etc. |

Add a platform-specific runbook only where that platform actually exists in the target repository —
for example `docs/runbooks/android-qa.md` (ADB commands, build variant, device prep, install
procedure) in a repo with an Android surface. Do not create platform runbooks a repo has no use for.

Create files that do not exist. Append to existing files — never overwrite useful prior content.

Each entry is a **top-level `##` heading preceded by a `---` separator**. Do not use `###` for an
entry: a heading one level deeper than the file's top-level entries silently nests under the
previous entry instead of becoming its own, so the entry becomes unreachable when an agent scans
the file's headings — and it still looks correct in any index block, which is what makes this
easy to miss. Reserve `###` for genuine sub-steps inside one entry.

**Format for `solutions.md` entries:**
```markdown
---

## <Short problem title>
**Context:** when this happens
**Problem:** what goes wrong
**Solution:** what fixes it
**Commands/code:** (if applicable)
```

**Format for `how-to.md` entries:**
```markdown
---

## How to <do something>
**When needed:** <scenario>
**Steps:**
1. ...
**Notes:** <gotchas or platform-specific details>
```

Commit knowledge files to the same branch as the implementation. They travel with the PR and get merged alongside the feature or fix.

## Communication Rules

- Be concise and concrete.
- Report blockers immediately with the exact reason.
- State assumptions when input is incomplete.
- Prefer actionable next steps over generic advice.

## User Interaction Rules

- Every question to the user goes through `AskUserQuestion`. A question asked as plain chat prose is a workflow violation — it has no options, no recorded answer, and from a subagent it routes the reply to the wrong agent.
- Every question presents the agent's **recommended option first**, labelled `(Recommended)`, with the consequence of each option in its description. Do the analysis before asking; a question is the last step of your reasoning, not a substitute for it.
- Do not ask what the code, docs, `.sdlc/status.json`, or a safe reversible default already answers. Take the default, record it as an assumption, and continue.
- A subagent that needs user input calls the tool itself. The parent must not relay, re-present, or forward a free-text reply — that produces a double-ask and an approval the subagent never received.
- Full contract, including the lease protocol around a blocking question: `.github/skills/shared/user-interaction-contract.md`.

## Safety Rules

- No harmful, malicious, or privacy-violating instructions.
- No secret extraction or credential harvesting.
- No dependency upgrades outside requested scope unless required for a fix.

## Language Policy

- Governance/process documentation is written in English.
- All user-facing chat output must use the language of the user's current request unless the user explicitly requests another language.
- Apply the same rule to questions, progress updates, summaries, blockers, handoffs, and generated prompt payloads.
- Explicit language requirements for repository artifacts or external-system messages take precedence only for those artifacts or messages.
- Templates/checklists in references stay in English unless a repository-specific requirement says otherwise.

## Runtime SDLC Status

Developer Flow Handoff and its downstream SDLC skills coordinate independent chats through `.sdlc/status.json` at the Git repository root resolved by `git rev-parse --show-toplevel`. Developer Flow Handoff and completed downstream agents expose paired handoff buttons: `(Agent)` sends compact evidence directly to the next specialist, while `(Prompt)` returns a copy-ready prompt as exactly one fenced `text` block. This file is runtime-only, must remain gitignored, and stores compact machine-readable gate evidence by flow. Agents must update it through `.github/scripts/update-sdlc-status.ps1`, not by ad hoc JSON edits, and must not store secrets, credentials, private environment values, or long logs in it. Every status write must pass `-SessionId` (flow lease identity, MANDATORY FIRST STEP item 3); exit code 2 means another session owns the flow lease — stop, do not retry. Nested `.sdlc/status.json` files below the repo root are runtime drift and must be removed or merged into the root status file. SDLC step order: `story -> branch -> implementation -> uiVerification -> review -> qualityGate -> manualQa -> docs -> pr -> reviewLoop -> merge`. Each agent updates only its own step and must pass `-ValidatePriorSteps` to enforce required prior-step statuses before writing.

Manual QA sign-off rule:

- `steps.manualQa.status=passed` is the authoritative QA approval for the current retest cycle and unblocks downstream `docs`/`pr` gates when their own prerequisites are satisfied.
- Implementers provide fix evidence and required retest scope, but Manual QA owns final defect lifecycle transitions after retest.
- Open defects from the same flow must be transitioned to non-open states (`resolved|verified|closed`) when QA retest passes; historical resolved defects must not block downstream steps.
- When Manual QA cannot personally complete a required check after exhausting ALL steps in the self-sufficiency escalation chain (primary tool -> CLI fallback -> app/service startup -> emulator startup -> documented recovery -> proven impossible), it must stop the dependent QA flow and request user-assisted verification. The agent must document every attempt made during the escalation chain. Valid escalations include passkey, biometric, hardware-token, physical-device, external-approval, inaccessible UI, and missing-access checks. The request must provide simple numbered steps, the expected result, and the exact non-secret proof required. Manual QA must wait for and assess that proof before resuming; it must not silently skip the check, infer success, accept an unsupported confirmation, or mark the check `passed`/`notNeeded`.
- In flow-backed runs, Manual QA must set `steps.manualQa.status=blocked` with the unresolved check and requested proof before yielding to the user, then update the status again after assessing returned proof. Inability to execute a check is a QA blocker, not by itself a product defect.
- If requested user-assisted proof is missing, insufficient, contradictory, or cannot be assessed, Manual QA must report the exact unresolved check and keep `steps.manualQa.status=blocked`; downstream docs, PR, and merge gates must not proceed.

Step-ownership rule:

- Each specialist step must gate only on required prior steps from SDLC order and its own in-scope evidence.
- Downstream steps must not be used as prerequisites for upstream steps (for example, `manualQa` cannot be a prerequisite for `review`).

SDLC-core handoff report contract:

- Scope: `user-story-spec`, `code-implementator`, `code-review`, `scope-quality-gate`, `e2e-manual-testing`, `docs-maintenance`, `github-create-pr`, and SDLC routing or orchestration handoffs that connect these steps.
- Every SDLC-core handoff or execution report must include a required minimum set of fields; additional role-specific sections are allowed.
- Required minimum fields: what was done, what went wrong or failed (or explicit `none`), what was fixed or changed, evidence (commands/results, paths, IDs, or concise artifacts), what remains or next actions, and blockers or errors (or explicit `none`).
- For Manual QA defect-return loops, developers must include a QA retest handoff prompt when Manual QA explicitly requested retest scope.
- The QA retest handoff prompt must list fixed items, executed validation/debugging evidence, explicit retest instructions, and residual risks.

Copy-ready `(Prompt)` response protocol:

- Return exactly one fenced `text` block with no text before or after it.
- End the payload with `END OF PROMPT`.
- If formatting is invalid, regenerate before final output.

## Handoff Artifacts

Handoff prompts are conversation output, not repository artifacts. Agents must not create files like `CODE_REVIEW_HANDOFF_*.md`, `*_HANDOFF*.md`, `*_PROMPT*.md`, `*_PROMT*.md`, or other chat-transfer markdown files unless the user explicitly requests a persistent file. If such a file is created accidentally, delete it and provide the content in chat instead. The supported durable SDLC runtime artifact is root `.sdlc/status.json`, updated only through `.github/scripts/update-sdlc-status.ps1`. Long operation runtime status/logs may live only under gitignored `.sdlc/operations/`.

## Definition of Done

A task is done when all points are true:

- Requested change is implemented.
- Relevant validation is completed or limitation is clearly stated.
- Files are consistent with repository conventions.
- Forbidden handoff/prompt artifacts and nested `.sdlc/status.json` drift have been checked and cleaned up when in scope.
- User-facing summary is clear and accurate.

## Centralized Rules and Constraints

### Prompt-Generation Responses
- Return exactly one fenced `text` block with no text before or after it.
- End the payload with `END OF PROMPT`.
- If formatting is invalid, regenerate before final output.

### Long-Running Operation Rules
- Follow foreground shell execution, real completion callback, or `.github/scripts/invoke-long-operation.ps1`.
- Avoid fire-and-forget VS Code tasks or generated `.vscode/tasks.json` unless explicitly requested.
- Do not final-answer while a required operation is still running; on resume, inspect `.sdlc/operations/*/status.json` before restarting work.
- Zero live children (a killed/exited/absent background shell, `Monitor` task, or worker) means it already ended — never claim it is still running or wait on its notification; block synchronously, use `invoke-long-operation.ps1`, or launch OS-detached with foreground polling instead.

### Update-SDLC Status
- Never invoke `.github/scripts/update-sdlc-status.ps1` using positional shorthand (e.g., `steps.story.status ready`).
- Always use named parameters (`-FlowId`, `-Branch`, `-Step`, `-Status`, plus required step-specific parameters).
<!-- END COPILOT SYNC -->

# Knowledge Base Catalog

**The single entry point for this repository's technical documentation.** Find the one row that
matches your task, open that file, and stop. Do not enumerate `*.md` or read whole files
speculatively.

Loading is two-tier: **this catalog → the file's own `## Index` → one `##` entry.** Every file over
~150 lines carries an `## Index`; use it rather than reading top to bottom.

---

## Features — how the app behaves

Current behaviour, not change history.

| Doc | Covers |
|---|---|
| [features/vpn-connection.md](features/vpn-connection.md) | Engine integration, the two-process/AIDL boundary, the six connection states and their transitions |
| [features/connection-recovery.md](features/connection-recovery.md) | Connected-state watchdog, auto-switch within country, stop/teardown retry — and what bounds each of them |
| [features/pause-resume.md](features/pause-resume.md) | Pause/Resume/Stop UI phases and the invariants that must hold |
| [features/server-sync.md](features/server-sync.md) | Server-list sync triggers, fallback chain, lazy loading, hardprobe trigger points (canonical), SSE push |
| [features/favorites.md](features/favorites.md) | Favorites data layer, case-normalization boundary, mobile and TV interaction patterns, visual framing |
| [features/server-selection.md](features/server-selection.md) | Persisted country/server choice, version signal, bootstrap, relocalization on language change |
| [features/dns.md](features/dns.md) | DNS provider override — the eight options and how they reach the tunnel |
| [features/app-filter.md](features/app-filter.md) | Per-app filter (split tunneling) — denylist model, connect-time application |
| [features/ui-shell.md](features/ui-shell.md) | Splash, main screen, connection controls, speedometer, navigation, server list screens |
| [features/notifications.md](features/notifications.md) | Two foreground services, channels, disconnect action, permission gating |
| [features/updates.md](features/updates.md) | Update check, in-app APK install, release notes, and the update trust boundary |
| [features/settings.md](features/settings.md) | Preferences, language/theme application, clamps, and which settings need a reconnect |
| [features/about-and-legal.md](features/about-and-legal.md) | About screen, log export and retention, legal document links |
| [features/tv.md](features/tv.md) | Android TV drawer guard plus the wider TV surface differences |
| [features/logging.md](features/logging.md) | Timber levels, release behaviour, anti-spam throttling, privacy |

## Reference — contracts and tables

| Doc | Covers |
|---|---|
| [reference/api-endpoints.md](reference/api-endpoints.md) | Every backend route the app calls, which are unused, and how URLs are derived |
| [reference/build-config.md](reference/build-config.md) | SDK levels (including the engine's higher one), build fields, URL resolution, Gradle tasks |
| [reference/permissions.md](reference/permissions.md) | Which manifest declares what, the service split, TV vs phone differences |
| [reference/settings-keys.md](reference/settings-keys.md) | Persisted settings with defaults, clamps and migrations |

## Guides — how to do things

| Doc | Covers |
|---|---|
| [guides/how-to.md](guides/how-to.md) | Reusable techniques: MockWebServer tests, hardprobe lifecycle, on-device SSE verification, local mock backend, prefs-migration checks |
| [guides/troubleshooting.md](guides/troubleshooting.md) | Symptom → root cause → fix. Read before debugging anything that feels familiar |
| [guides/adb-cookbook.md](guides/adb-cookbook.md) | Topic-organised reusable ADB one-liners: device control, app lifecycle, prefs, probe/autoswitch, SSE signals |
| [guides/engine-update.md](guides/engine-update.md) | Post-engine-bump regression checklist and pass criteria |

## Operations — device and QA environment

| Doc | Covers |
|---|---|
| [operations/device-qa-phone.md](operations/device-qa-phone.md) | ADB workarounds for a multi-user MIUI phone, launch/resolve, log filters |
| [operations/device-qa-miui.md](operations/device-qa-miui.md) | MIUI readiness commands and known device blockers |
| [operations/device-qa-tv.md](operations/device-qa-tv.md) | MIBOX4 Leanback launch, D-pad injection, focus gotchas |
| [operations/device-qa-log.md](operations/device-qa-log.md) | Chronological Android QA findings kept for their device-specific detail |

## Conventions — rules for agents

| Doc | Covers |
|---|---|
| [conventions/kotlin-android-standards.md](conventions/kotlin-android-standards.md) | Module boundaries, DI, UI, logging, endpoint configuration, release hardening |
| [conventions/testing-guidelines.md](conventions/testing-guidelines.md) | JVM vs Robolectric vs instrumented, and what the app test task does not cover |
| [conventions/engine-submodule.md](conventions/engine-submodule.md) | Engine fork, update flow, validation, hard constraints |
| [conventions/cross-repo-sync.md](conventions/cross-repo-sync.md) | Tool-managed paths, API contract alignment with the backend |
| [conventions/code-review-expectations.md](conventions/code-review-expectations.md) | Review dimensions and severity levels |
| [conventions/error-handling-logging.md](conventions/error-handling-logging.md) | Exception handling, structured logging, no secrets or PII |
| [conventions/agent-runtime-artifacts.md](conventions/agent-runtime-artifacts.md) | What runtime state may be written to disk |

---

## Where a new doc goes

Match what you are writing to the row below, then **prefer the most specific existing file over
creating a new one**. Most knowledge belongs as an entry in a file that already exists.

| What you have | Where it goes |
|---|---|
| How a feature behaves — flows, state models, invariants | `features/<area>.md` — extend the existing file; a new one only for a genuinely new area |
| A specific bug: symptom → root cause → fix | a `##` entry in [guides/troubleshooting.md](guides/troubleshooting.md) |
| A reusable technique or step-by-step guide not tied to one bug | a `##` entry in [guides/how-to.md](guides/how-to.md) |
| A cross-story ADB/command snippet organised by topic | [guides/adb-cookbook.md](guides/adb-cookbook.md) — a first-class destination, not just somewhere to avoid duplicating into |
| A device- or OEM-specific QA gotcha | the matching `operations/device-qa-*.md` |
| A contract, key list, limit or route table | `reference/<topic>.md` |
| A rule agents must follow | `conventions/<topic>.md` |

Never create a directory that duplicates the shape of an existing one to hold a single stray entry —
this has happened before and produced an orphaned, unreferenced duplicate. If unsure which file owns
a piece of knowledge, check this catalog first.

## Out of scope for this catalog

- `.github/**` and `.claude/commands/**` — synced from CopilotTools; their index is
  `.github/AGENTS-REGISTRY.md` (local-only, gitignored).
- `.sdlc/**` — SDLC runtime state, not durable documentation.
- `tests/manual-e2e/automation/` — PowerShell QA driver scripts and their README; they live with the
  scripts they describe.
- `PRIVACY_POLICY.md`, `TERMS.md` — user-facing legal text, not agent documentation.
- ClickUp — user stories, bugs and QA suites live there. This repository holds no per-story artifacts.

## Known gaps

None outstanding. Every subsystem in the module map has a behaviour doc above.

One open **correctness** question, which needs a device rather than an edit: the TV D-pad long-press
technique is described inconsistently across `guides/troubleshooting.md`,
`operations/device-qa-tv.md` and `operations/device-qa-log.md` — two say `sendevent` works on
MIBOX4/Android 9, one says it had no effect. Settle it on hardware, then make one the canonical answer
and reduce the others to pointers.

## Maintaining this catalog

- **New doc ⇒ add its row here in the same change.** A doc that is not catalogued is invisible.
- **New entry in a file with an `## Index` ⇒ add the matching index line in the same change.**
- Entries in `guides/*` are top-level `##` headings preceded by `---`. A `###` heading silently nests
  under the previous entry — it will still look right in the index while being structurally
  unreachable. This has happened here before.
- **One canonical owner per fact.** Link instead of restating. Hardprobe trigger points are the model
  to copy: canonical in `features/server-sync.md`, pointers everywhere else.
- **`guides/adb-cookbook.md` is topic-organised and story-free; `operations/device-qa-log.md` is a
  chronological record.** Do not merge new entries into the wrong one.
- **Describe current behaviour, not change history.** Provenance belongs in git and ClickUp.
- Add a `Last verified against: <files> (<date>)` footer to behaviour docs. Treat it as a claim, not
  proof — a doc in this repo carried that footer while naming a connection state that did not exist.
- Never hand-list documentation files in `README.md`, `AGENTS.md` or `CLAUDE.md` — link here.
- Never record credentials, tokens or device serials. Use a placeholder.

*Last verified against: the `docs/` tree (2026-07-31).*

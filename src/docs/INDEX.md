# AI-Agent Knowledge Base — Index

This is the single catalog entry point for this repository's technical knowledge base. `CLAUDE.md`
and `AGENTS.md` link only to this file for anything beyond their own core rules — read the row(s)
relevant to your current task, not the whole catalog's linked content. New docs must be added here
in the same change that creates them; nothing else should need editing in the root docs when the
knowledge base grows.

## Flow & Behavior Docs

Architecture/business-rule docs for engineers or agents changing how a feature works.

| Doc | Covers |
| --- | --- |
| [server-sync-flow.md](server-sync-flow.md) | Server-list sync triggers, source/fallback chain, SSE push, hardprobe enqueue points (canonical — see note below), foreground-service lifecycle guard |
| [favorites-ui-patterns.md](favorites-ui-patterns.md) | Favorites data layer, mobile long-press UI, TV D-pad dialog, visual framing/casing conventions |
| [vpn-pause-resume-flow.md](vpn-pause-resume-flow.md) | Pause/Resume/Stop state machine and its invariants (no bounce-back, resume parity with fresh connect) |
| [tv-drawer-guard-flow.md](tv-drawer-guard-flow.md) | TV main-screen drawer interaction-isolation guard (false-click prevention) |
| [engine-update-smoke-checklist.md](engine-update-smoke-checklist.md) | Reusable regression checklist to run after any OpenVPN engine submodule bump — a generic procedure, not a device-specific note, hence its place here rather than under Device/QA Runbooks |

**Note:** `server-sync-flow.md`'s "Hardprobe Trigger Points" section is the single canonical
description of that mechanism — `android-qa-adb-cookbook.md` and `docs/runbooks/how-to.md` both
point here rather than keeping their own copy, after one of those copies was found to have drifted
stale. Don't reintroduce a third copy.

## Conventions

Cross-cutting rules that apply regardless of feature.

| Doc | Covers |
| --- | --- |
| [logging-policy.md](logging-policy.md) | Log levels, release behavior, anti-spam throttling, privacy rules |

## Bug Postmortems

Symptom → root cause → fix, for fast diagnosis of a recurring or similar-looking regression.

| Doc | Covers |
| --- | --- |
| [../../docs/runbooks/solutions.md](../../docs/runbooks/solutions.md) | ~25 entries (SSE, WorkManager/Robolectric, foreground-service crash, favorites collisions, TV D-pad, engine SDK bumps, etc.). **Read that file's own "Index" section first and jump to the one relevant heading — do not read the whole file.** |

## How-To Guides

Reusable techniques, worked out once and written down so they don't need re-deriving.

| Doc | Covers |
| --- | --- |
| [../../docs/runbooks/how-to.md](../../docs/runbooks/how-to.md) | MockWebServer JVM testing, hardprobe lifecycle reference, verifying SSE on-device, sectioned-RecyclerView favorites pattern, local mock backend for QA, running the engine's own test suite, verifying SharedPreferences migrations. **Read that file's own "Index" section first and jump to the one relevant heading.** |

## Device / QA Runbooks

Device-specific gotchas and reusable commands for manual verification. Note: the cookbook and the
android-qa runbook are **not** the same thing — the cookbook is topic-organized reusable snippets;
`android-qa.md` is a chronological log of QA walkthroughs per story. Don't merge new entries into
the wrong one.

| Doc | Covers |
| --- | --- |
| [android-qa-adb-cookbook.md](android-qa-adb-cookbook.md) | Reusable ADB one-liners by topic: device control, app lifecycle, SharedPreferences inspection, probe/autoswitch verification, SSE client verification |
| [../../docs/runbooks/android-qa.md](../../docs/runbooks/android-qa.md) | Chronological per-story QA walkthroughs and device-specific findings (Samsung/MIUI/TV quirks, locale override technique). Has its own `## Index` ToC block too, same as `solutions.md`/`how-to.md`. |
| [../../tests/manual-e2e/environment/android-miui-manual-qa-notes.md](../../tests/manual-e2e/environment/android-miui-manual-qa-notes.md) | MIUI-specific manual QA notes |
| [../../tests/manual-e2e/environment/android-adb-vpn-qa-runbook.md](../../tests/manual-e2e/environment/android-adb-vpn-qa-runbook.md) | ADB VPN QA runbook |
| [../../tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md](../../tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md) | Android TV D-pad QA runbook (Leanback launch, long-press injection, dialog focus) |

## Out of scope for this catalog

- `docs/qa-evidence/*.md` — per-task SDLC audit trail (gate/review/docs/manualQa evidence), not
  reusable agent knowledge. Not catalogued here.
- `tests/manual-e2e/automation/*`, `tests/manual-e2e/reference/*` — infra-as-code / commit-tied
  evidence, referenced directly from the flow docs above where relevant, not catalogued separately.

## Maintaining this catalog

- Adding a new `src/docs/*.md` flow/convention doc: add one row to the relevant table above in the
  same change, and give it a one-line "Last verified against: <date/commit or story>" footer —
  this is how a reader can tell whether a flow doc might have drifted from current code.
- Adding a new entry to `docs/runbooks/solutions.md`, `docs/runbooks/how-to.md`, or
  `docs/runbooks/android-qa.md`: also add one line to that file's own `## Index` section — do not
  just append the entry body. Use a real `##` heading with a `---` separator before it, matching
  the file's dominant convention — a `###` entry with no separator silently nests under whatever
  heading precedes it instead of becoming a standalone entry (this has happened before in
  `solutions.md`).
- Creating a brand-new `docs/runbooks/*.md` file for the first time (e.g. `environment-setup.md` or
  `api-testing.md`, named in `AGENTS.md`'s decision table but not yet created as of this writing):
  add it to the "Device / QA Runbooks" table above (or a new category if it doesn't fit) in the
  same change — don't leave a newly-created runbook file uncatalogued.
- If you're unsure whether something belongs in `src/docs/` (durable behavior/architecture) vs
  `docs/runbooks/solutions.md` (a specific bug postmortem) vs `docs/runbooks/how-to.md` (a reusable
  technique) vs a device/environment note, prefer the most specific existing file before creating a
  new one, and never duplicate a file path structure that already exists elsewhere (this catalog
  exists in part because of a previous accidental duplicate: `src/docs/runbooks/solutions.md`).
- Before restating a mechanism (a trigger list, a state model, a bug's root cause) that's already
  documented elsewhere, link to it instead of re-describing it — copies drift. This has already
  happened once (see the note under "Flow & Behavior Docs" above) and produced a factually wrong
  count in one of the copies.

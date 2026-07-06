---
description: 5-step release cycle for one developer + agent. Naming conversation (feature/release/DD.MM.YYYY) + code review + quality gate → PR review babysitter loop (Gemini + Codex) → multi-surface manual QA (all release stories, post-approval) → squash merge (end-user-facing release notes) → archive (rename dev → archive/archive-dev-DD.MM.YYYY + recreate dev from main).
---

## STEP 0 — MANDATORY

```powershell
pwsh -File ".github/scripts/init-session.ps1"
```

Run this NOW. Do not proceed until this command completes.

## STEP 1 — Run the release flow

Run the full 5-step release flow. Load and follow `.github/skills/release-flow-orchestrator/SKILL.md` as the authoritative workflow.
Check session rate limit at start: run `.github/scripts/check-rate-limit.ps1` — if status is "warning" or "exhausted", checkpoint and stop.

$ARGUMENTS

## Resume — ALWAYS check this first when given a release branch or date

Read `.sdlc/status.json` (look inside the matching flow entry). Map the current state to the correct step. **Do not ask the user what to do — resume automatically.**

Evaluate in this exact order (first match wins):

1. `archive.status = "done"` → Already complete — report status
2. `merge.status = "merged"` or `squash_sha` exists → **Step 5** — Archive
3. `reviewLoop = "passed"` AND `manualQa.status = "passed"` → **Step 4** — Squash Merge
4. `manualQa.status = "passed"` → **Step 4** — Squash Merge
5. `reviewLoop = "passed"` → **Step 3** — Manual QA
6. `step = "reviewLoop"` OR `reviewLoop` field exists with any value other than `"passed"` → **Step 2** — restore `pr_number`, `release_branch`, `reviewRound` from the entry; resume the bot review loop
7. `pr.status = "created"` or `pr.status = "ready"` or a PR URL exists → **Step 2** — PR Review Babysitter Loop (not started yet)
8. `release_branch` exists → **Step 1** — PR creation (branch exists, PR not yet created)

**HARD GATE — step 2 is mandatory:**
A PR being open or `pr.status = "created"` does NOT mean the flow is complete. Step 2 (bot review loop), step 3 (QA), step 4 (merge), and step 5 (archive) still remain. Never jump from step 1 directly to step 3 or step 4. If the PR exists and `reviewLoop` is not `"passed"`, always enter step 2.

**If check-rate-limit.ps1 is blocked by the classifier:** skip the rate limit check, log a warning, and continue the flow.

## Entry rules

- Read `AGENTS.md` and `docs/runbooks/` before doing anything else.
- Detect the stack from repo markers (`build.gradle*`, `package.json`, `docker-compose*.yml`, Android modules, web entry points).
- Source must be `dev`/`develop`. Target must be `main`/`master`.

## Step summary

1. **PR creation** — naming conversation (release date → branch `feature/release/DD.MM.YYYY`); extract `stories_in_release` from `git log origin/main..dev`; spawn `/create-pr` subagent in release orchestration mode (handles code review + quality gate automatically). Proceed immediately to step 2 — do NOT stop here.
2. **Bot review babysitter loop** — use `run-review-round.ps1` (PowerShell only, never Git Bash); classify bot threads with LLM intent judgment; spawn `/review-comments` subagent for actionable bot threads (queue is pre-approved — no user confirmation needed); resolve threads via `resolve-pr-threads.ps1`; re-request review after any code push; exit when all bots approve with zero actionable items (min 1 round mandatory). Max 10 rounds. Checkpoint `pr_number`, `release_branch`, `reviewRound`, `resume_cron_id` to `.sdlc/status.json` before every wait. Proceed immediately to step 3 — do NOT stop here.
3. **Manual QA** — runs AFTER bot approval; covers ALL stories in `stories_in_release`; build + deploy + verify latest version first; all three surfaces (API, Web, Android); spawn `/manual-qa` subagent. If defects → fix → re-enter step 2 → re-run step 3 for failed surfaces only.
4. **Squash merge** — spawn `/merge-pr` subagent with release-specific commit format: title `"Release DD.MM.YYYY"`, body uses `### Added`, `### Fixed`, `### Updated`, `### Improved` sections; no story IDs, flow IDs, or internal labels. Merger runs full build + tests. Do NOT draft the commit message yourself.
5. **Archive** — use `vscode/askQuestions` to show exact operations and require explicit `CONFIRM` before proceeding: (a) rename `dev` → `archive/archive-dev-DD.MM.YYYY` (preserves history); (b) create new `dev` from merged `main` (fresh start). Print the final flow report only after this step completes.

## Subagent model assignments

Pass the `model` parameter when spawning each Agent-tool subagent:

| Subagent | Model | Rationale |
|---|---|---|
| `/code-review` | `claude-sonnet-5` | Deep multi-pass analysis (run inside /create-pr release mode) |
| `/quality-gate` | `claude-sonnet-5` | Coverage and edge-case reasoning (run inside /create-pr release mode) |
| `/manual-qa` | `claude-sonnet-5` | Multi-surface test decisions, all release stories |
| `/merge-pr` | `claude-sonnet-5` | Build + QA verification + merge gate decisions |
| `/implement` | `claude-sonnet-5` | Defect fixes from QA (if needed) |
| `/create-pr` | `claude-haiku-4-5-20251001` | PR creation with release orchestration mode |
| `/review-comments` | `claude-haiku-4-5-20251001` | Queue + apply + reply — mechanical |

## Hard stops

- Source must be `dev`/`develop`. Target must be `main`/`master`.
- Release branch naming: `feature/release/DD.MM.YYYY` (example: `feature/release/25.05.2026`). Never deviate.
- Archive branch naming: `archive/archive-dev-DD.MM.YYYY` (example: `archive/archive-dev-19.06.2026`). Use release date.
- Never push directly to `main`, `dev`, `master`, or `develop` at steps 1-4. Step 5 (archive) is the sole authorized exception and requires explicit user `CONFIRM`.
- Step 2 (bot review loop) is mandatory — never jump from step 1 to step 3 or step 4.
- Manual QA runs only after step 2 exits with all bots approving — never before.
- Do not print the final report until step 5 (archive) completes.
- Bot review requests: use `run-review-round.ps1` (PowerShell) — never Git Bash (it expands /gemini as a file path).
- Poll script MUST run in foreground (blocking), never with `run_in_background`.
- Before every foreground poll wait, call `ScheduleWakeup(delaySeconds=270, prompt="/release-flow-orchestrator feature/release/<DD.MM.YYYY>")` so the session auto-resumes if it idles. Cancel with `CronDelete` immediately after the poll returns.
- Each specialist phase must be delegated to an internal Agent-tool subagent. Do not inline review or gate logic.
- GitHub Release tag creation is handled by CI/CD automatically — do not create tags or GitHub Releases manually.

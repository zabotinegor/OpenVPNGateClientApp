---
name: agent-sync
description: "Mirror-sync Copilot customization assets and Claude slash commands from the latest zabotinegor/CopilotTools main commit into a target repository with deterministic stale-file deletion, exact-path .gitignore policy, and verification reporting."
argument-hint: "what should be synced from CopilotTools and to which target paths"
---

# Agent Sync

## Summary

Synchronize agent, skill, tool, and helper-script assets from the configured CopilotTools Git repository into the current target repository. Prefer deterministic scripts and keep the final report focused on decisions, results, and blockers.

**Session-limit exemption — Agent Sync only.** This is the single workflow exempt from the session-limit and session-recovery machinery in [../shared/operational-rules.md](../shared/operational-rules.md#session-limit-rules) and the `MANDATORY FIRST STEP` block of [../shared/agents-core-rules.md](../shared/agents-core-rules.md). Do not run `init-session.ps1`, do not read or report usage/reset state, do not arm or confirm a reset-recovery cron, do not checkpoint to `.sdlc/status.json`, and do not run `check-tracking-preflight.ps1`. Agent Sync ships that stack; it does not consume it. A sync is short, idempotent, and safe to re-run from scratch, so an interrupted one needs no recovery — rerunning it is the recovery. Everything the other agents do here still applies to them; only Agent Sync skips it.

## When to use

- Mirror-sync Copilot agents, skills, tools, scripts, Copilot/Claude hooks, Git hooks, or Claude slash commands into another repository.
- Reconcile stale synced files in a target repository.
- Update target `.gitignore` entries for synced non-agent-sync files.
- Add target `.gitignore` entries for transient Copilot handoff/prompt artifacts, runtime `.sdlc/status.json`, `.sdlc/operations/`, `.claude/launch.json`, and `.claude/settings.local.json`.
- Propagate universal agent governance rules from CopilotTools into client repo `AGENTS.md` without overwriting client-specific content.

## Expected input

- Target repository/worktree.
- Requested sync scope or default scope (`.github/agents`, `.github/skills`, `.github/tools`, `.github/scripts`, `.github/hooks`, `.githooks`, `.claude/commands`, `.claude/settings.json`).
- Any paths that must be excluded from sync.

## Blocking gates

- **Agent Sync never participates in flow machinery and is never blocked by it.** No `init-session.ps1`, no reset-recovery cron, no `.sdlc/status.json` checkpoint, no flow lease check or acquire — see the exemption in [../shared/agents-core-rules.md](../shared/agents-core-rules.md). It is the installer for that stack, so it must run whenever invoked, including when the stack is broken. An unfinished flow, a held lease, or an unarmed/stale recovery cron on the current branch is **not** a reason to stop, switch branches, ask the user how to proceed, or arm anything: those belong to whichever session owns that flow. The exemption is enforced by `check-session-before-tool.ps1`, so if a sync command is denied on those grounds, report it as a gate bug rather than trying to satisfy the demand.
- Resolve and report the latest source commit SHA from the configured CopilotTools Git repository before editing, using foreground git/terminal when available or authenticated GitHub connector/API tools when terminal execution is unavailable.
- Stop before editing only if no available tool can identify the source revision or source file contents.
- Verify the target branch/worktree state before changing files.
- Agent Sync works exclusively in the current branch — never creates, switches to, or checks out a different branch, including `main`/`dev`. The sync must apply to whatever branch is currently checked out, no exceptions.
- Agent Sync never commits, stages, or pushes — it only syncs file contents into the working tree. The user commits when ready.
- If `sync-copilot-assets.ps1` (or any sync step) fails for any reason, do not work around it by creating a branch, committing, or pushing. Report the exact error and stop — creating a branch to route around a blocker is itself a violation of the two rules above.
- Protected root markdown files (`AGENTS.md`, `README.md`, `AGENTS.local.md`, `README.local.md`) are blocked from full-file sync by default and require explicit user approval plus script flag `-AllowRootMdSync`. Exception: the script always injects the universal governance section from `.github/skills/shared/agents-core-rules.md` into a target `AGENTS.md` that already exists, using `<!-- BEGIN COPILOT SYNC --> … <!-- END COPILOT SYNC -->` markers — client content outside the markers is never modified.
- Do not delegate sync execution or comparison to a subagent. Run Agent Sync in the current chat because subagents may not receive terminal tools or the target workspace context.
- Do not run sync through VS Code task labels or other task launchers. For Agent Sync, use `run_in_terminal` first and `runCommands` second for foreground PowerShell. Task launchers are not reliable for required sync because cancellation/completion may not return script output, source SHA, file counts, or exit code.
- Do not infer that terminal execution is unavailable. Report terminal/command execution unavailable only after an actual terminal-capable tool call fails with an unavailable-tool/capability error.
- Treat `Canceled` from any task launcher as a launcher failure, not as a sync blocker. Immediately switch to `run_in_terminal` and then `runCommands` in direct foreground PowerShell; if direct script execution is unavailable after both real failed attempts, use the manual mirror-sync fallback instead of asking the user to run commands.

## Workflow

1. Read `AGENTS.md`, `.github/AGENTS-REGISTRY.md`, and target worktree state. Confirm the current branch and proceed — do not create or switch branches.
2. Resolve the latest source commit SHA from the configured `SourceRepo`/`SourceRef`.
3. Prefer `.github/scripts/sync-copilot-assets.ps1`; perform manual mirror-sync only after real failures of `run_in_terminal` and `runCommands` prove direct script execution is unavailable.
   Run the script with `run_in_terminal` first (foreground PowerShell), not as a VS Code task:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .github/scripts/sync-copilot-assets.ps1 -DryRun`
   If `run_in_terminal` is unavailable, retry the same command with `runCommands`.
   Keep root markdown protection enabled by default; include `-AllowRootMdSync` only when user explicitly approved syncing protected root markdown files.
   Then run the apply command only after reviewing the dry-run JSON:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .github/scripts/sync-copilot-assets.ps1`
4. If direct script execution is unavailable, use manual fallback through authenticated GitHub connector/API tools at the resolved source revision; compare source and target assets in scope by relative path and content. Do not use unauthenticated browser pages as source evidence for private repositories.
   **HARD STOP — manual fallback is GitHub connector/API tools only.** When `run_in_terminal` and `runCommands` are unavailable, never attempt `git clone`, `git sparse-checkout`, or any other git command as a substitute. Those require terminal access that has already failed. Use only read/search/edit tools plus authenticated GitHub connector/API calls (e.g. `github/get_file_contents`) to retrieve and compare source files at the resolved commit SHA.
5. Verify differences before editing, especially frequently changed agent/skill files.
6. Apply add/update/delete operations only inside the agreed sync scope.
7. Delete target files in scope that do not exist in source, except paths containing `agent-sync` or `sync-copilot-assets`.
8. Update target root `.gitignore` with exact synced file paths, excluding paths containing `agent-sync` or `sync-copilot-assets`.
   **Rebuild the whole managed block from the full in-scope source file list — never append only the files this run happened to touch.** The block is regenerated wholesale between the `# BEGIN synced-copilot-assets` / `# END synced-copilot-assets` markers; a run that adds new scripts but leaves the block at its previous contents produces exactly the observed failure mode — freshly synced scripts sitting untracked in the client repo, showing up as local changes in every git client.
   **This step is mandatory on the manual-fallback path too.** It is the step most easily lost when files are copied with edit tools instead of the script, because it is the only one with no per-file edit to prompt it. After finishing the copies, enumerate every in-scope source path and write the block in one pass, then also untrack anything git still tracks despite now being ignored (`git rm --cached <path>`, which leaves the file on disk).
   **Verify before reporting:** the count of entries in the managed block must equal the number of in-scope synced files minus the `agent-sync`/`sync-copilot-assets` exclusions, and `git status` must show no untracked files under the synced scope. Report both numbers.
9. Update target root `.gitignore` with transient artifact ignores for `*_HANDOFF*.md`, `*_PROMPT*.md`, `*_PROMT*.md`, `CODE_REVIEW_HANDOFF_*.md`, `.sdlc/status.json` at any depth, `.sdlc/operations/`, `.claude/launch.json`, and `.claude/settings.local.json` at any depth.
10. Keep `.github/hooks/`, `.githooks/`, and the protected-branch guard scripts trackable so project-level Copilot hooks can work from the client repository default branch.
11. Verify the source-only root `.copilottools-source` marker was not synchronized; its presence would disable the client-only guards.
12. Configure target local Git with `core.hooksPath=.githooks` and report whether it was configured, already configured, or unavailable because the target is not a Git worktree.
13. Report forbidden handoff/prompt artifacts and nested `.sdlc/status.json` files found in the target worktree; do not silently delete them unless the user requested cleanup.
14. Re-check synced files against the resolved source commit after edits. With manual fallback, verify by content comparison for every changed file and clearly mark script verification as not run.
15. **Establish the target's SDLC artifact mode (ClickUp or local).** Agent Sync is the setup owner for this — no other skill provisions it. See "ClickUp setup and verification" below.
16. Produce the required change report.

## ClickUp setup and verification

The synced SDLC-core skills run in ClickUp mode or local mode depending on whether the target repo has a valid `.sdlc/clickup-config.json` (see [../shared/clickup-integration.md](../shared/clickup-integration.md#mode-selection-every-sdlc-core-skill)). Agent Sync resolves that after the file sync, because the skills it just installed depend on it.

**Agent Sync is the only owner of ClickUp setup.** No SDLC flow provisions any of it: a flow finds the integration configured or it does not. Anything Agent Sync cannot do itself — anything needing a human's credentials, consent, or a decision only the repo owner can make — must be reported to the user as an explicit, numbered instruction, never left as an implied prerequisite.

**Run the deterministic check first, then act on what it reports:**

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .github/scripts/setup-clickup.ps1 -Probe
```

It checks, in one pass: the `.mcp.json` `clickup` entry, config presence and schema (including that the required status keys exist for this repo's `release_flow`), `.gitignore` coverage of the config/token/migration-map, whether the token file has been committed, REST token presence, live reachability of every configured List, whether every configured status name actually exists on the board, and — with `-Probe` — a create-and-delete write probe on each QA List. Every finding carries `remediation` text. Omit `-Probe` for a read-only pass; include it whenever the config changed or the QA lists have never been probed.

Then handle what it reports:

1. **`mcp.entry` FAIL** — `.mcp.json` is in sync scope and merged, so the `clickup` server entry should land automatically. Re-run the sync; if the merge still skips it, report that rather than hand-editing around the sync.
2. **`mcp.auth`** always reports `WARN`: OAuth state is per-developer and not inspectable from a script. Tell the user to run `/mcp` in an interactive Claude Code session and authenticate the `clickup` server. This is one of the steps Agent Sync cannot perform — surface it as an instruction, do not skip past it.
3. **`config.present` SKIPPED** (no config) → target is in local mode. Ask the user whether to set ClickUp up now. If they decline, report `artifactMode: local` and stop this section — local mode is fully supported and is not a blocker. If they agree, run `setup-clickup.ps1 -Scaffold`, which writes `.sdlc/clickup-config.template.json` for them to fill in. It deliberately does not write the real config: a config present with placeholder IDs is a hard error for every skill (correctly — a half-configured repo scatters one story across two systems), so the template is the safe artifact.
4. **Filling the config** — resolve real IDs with the user, never by guessing: `clickup.ps1 -Action Discover` prints every Workspace/Space/Folder/List id, and `clickup.ps1 -Action ListStatuses -ListId <tasks list>` prints the exact status names the `statuses` map must use. Set `release_flow` (`simple` for a repo with no `dev` branch, `dev-main` for one with a separate release cycle) and populate the release-only keys (`ready_for_release`, `release_review`, `qa_approval`) only for `dev-main`. Rename the template to `clickup-config.json` and re-run the check.
5. **`config.present` FAIL / `config.schema` FAIL** — report as a blocker with the script's detail; do not silently rewrite the user's config.
6. **`token.present` FAIL** — this is the step most often missed and the most consequential. Without `.sdlc/clickup-token` there is no REST fallback for a rate-limited MCP server, and every ClickUp verification in every flow reports `UNVERIFIED` and exits 0 — the whole safety net present and doing nothing, while flows still report success. Agent Sync cannot create the token; give the user these exact steps:
   1. In ClickUp: avatar → **Settings** → **Apps** → **API Token** → generate (it starts with `pk_`).
   2. Save it as a single line in `.sdlc/clickup-token` in this repository (already gitignored).
   3. Do not paste it into chat, into a tracked file, or into a shell environment variable — a variable exported in an interactive shell does not reach separately spawned processes, which is where the scripts run.
   4. Re-run `setup-clickup.ps1 -Probe`.
7. **`token.tracked` FAIL** — the token is in the git index. Tell the user to run `git rm --cached .sdlc/clickup-token`, confirm the ignore rule, and **rotate the token in ClickUp**, since it has been committed.
8. **`gitignore` FAIL** — re-run the sync; `sync-copilot-assets.ps1` rebuilds the transient-artifact block, which covers the config, template, token, and migration map.
9. **`probe.<list>` FAIL with `ITEM_246`** — that List has permanently spent the workspace-lifetime "custom task types" quota and can never accept another new task. Apply the fix in [../shared/clickup-integration.md](../shared/clickup-integration.md#free-plan-limits-exhausted-custom-task-types-quota---separate-from-custom-fields): provision a fresh List directly in the `QA` Folder (never from a template), set its 3-status pipeline explicitly, point the config at the new ID, and re-probe. Leave the exhausted List in place — its existing tasks stay valid.
10. **`statuses.<list>` FAIL** — a configured status name does not exist on the board, so every push of that key would be rejected at runtime. The check prints the board's actual names; correct the config to match exactly.
11. **Report the resolved mode** so the user knows which workflow the synced skills will take.

## Output format

Report source repository and commit SHA, target branch, sync scope, added/changed/deleted counts and paths (broken out by Copilot, Claude, and branch-guard asset types), stale-file deletion status, `removedDeadHooks` (hook entries dropped from `.claude/settings.json` because the `.github/scripts/` file they invoke no longer exists — name each one, since a retired hook that survives a merge looks configured and guards nothing), `agentsCoreRulesInjection` action for the `AGENTS.md` governance section (added-markers, replaced-section, no-change, or skipped), post-sync verification, synced `.gitignore` policy verification, Git hooks path configuration, transient artifact `.gitignore` verification, discovered forbidden artifacts, discovered nested `.sdlc/status.json` files, token-efficiency note for manual fallback, and blockers or assumptions.

Also report the ClickUp setup outcome, taken from `setup-clickup.ps1`'s JSON: `artifactMode` (`clickup` or `local`), overall `status`, and every non-`OK` check with its remediation — at minimum `clickupMcpEntry`, `clickupConfig` (absent, scaffolded, valid, or malformed), `clickupToken` (present, missing, or tracked-by-git), `clickupIgnore`, `clickupStatusNames`, and `clickupListProbe` (per-List pass/fail for every `qa_suites_list`/`qa_cases_list`, naming any List that hit `ITEM_246` and whether it was reprovisioned).

End the ClickUp section with an explicit **"what you must do yourself"** list — MCP OAuth, token creation, any ID the user has to supply — as numbered steps. A setup that is 90% done and silently missing the token behaves exactly like a working one until a flow needs it, so an unfinished item that is not spelled out is an item that does not get done.

Also report the ClickUp setup outcome, taken from `setup-clickup.ps1`'s JSON: `artifactMode` (`clickup` or `local`), overall `status`, and every non-`OK` check with its remediation — at minimum `clickupMcpEntry`, `clickupConfig` (absent, scaffolded, valid, or malformed), `clickupToken` (present, missing, or tracked-by-git), `clickupIgnore`, `clickupStatusNames`, and `clickupListProbe` (per-List pass/fail for every `qa_suites_list`/`qa_cases_list`, naming any List that hit `ITEM_246` and whether it was reprovisioned).

End the ClickUp section with an explicit **"what you must do yourself"** list — MCP OAuth, token creation, any ID the user has to supply — as numbered steps. A setup that is 90% done and silently missing the token behaves exactly like a working one until a flow needs it, so an unfinished item that is not spelled out is an item that does not get done.

## Constraints or rules

- Never delete `agent-sync.agent.md`, `.github/scripts/sync-copilot-assets.ps1`, or any path containing `agent-sync` or `sync-copilot-assets`; update those files only when source differs.
- Never add broad ignore patterns such as `/.github/agents/**`, `/.github/skills/**`, `/.github/tools/**`, or `/.github/scripts/**`.
- Never hide agent-sync-related files through `.gitignore`.
- Never hide `.github/hooks/`, `.githooks/`, or protected-branch guard scripts through the managed synced-assets `.gitignore` block.
- Never sync or create the root `.copilottools-source` marker in a client repository.
- Never create, switch to, or check out a different branch. Agent Sync operates exclusively in the current branch.
- Never commit, stage, or push. Agent Sync only syncs file contents into the working tree.
- Never full-file-replace protected root markdown files (`AGENTS.md`, `README.md`, `AGENTS.local.md`, `README.local.md`) unless user approval and `-AllowRootMdSync` are both present. The automatic marker-based injection into an existing `AGENTS.md` (from `.github/skills/shared/agents-core-rules.md`) is the sole exception and requires no flag.
- Never create handoff/prompt markdown artifacts while reporting sync results; include handoff text in chat only.
- Never invoke VS Code tasks, task labels, or "Run task" for sync dry-run or apply. Use `run_in_terminal` first and `runCommands` second with direct foreground PowerShell so the agent receives JSON output, exit code, and errors.
- If terminal/command execution is unavailable after a real failed terminal-capable tool call, do not ask the user to run commands. Complete sync manually with available read/search/edit plus authenticated GitHub connector/API tools and report the fallback. Stop only if neither terminal nor authenticated source access is available.
- **Never use `git clone`, `git sparse-checkout`, or any standalone git command as a manual fallback.** These require the same terminal access that has already failed. Manual fallback means reading each source file individually through the GitHub connector/API (e.g. `github/get_file_contents` at the resolved commit SHA) and applying edits with edit tools.
- If a previous task-based attempt was cancelled, explicitly switch to `run_in_terminal` and then `runCommands` with direct foreground PowerShell; if unavailable after real failed attempts, use manual mirror-sync fallback before reporting any blocker.
- If any post-sync file mismatches source, stop and report the mismatch.
- Never report a sync as complete while `git status` still shows untracked files under the synced scope. That is the signature of a half-finished sync — files copied, managed `.gitignore` block not rebuilt — and it leaves the client repo permanently noisy.
- Never run session-limit or session-tracking machinery as part of a sync — no `init-session.ps1`, no usage/reset lookup, no recovery cron, no `check-tracking-preflight.ps1`. Never stop, checkpoint, or defer a sync because of session usage; if a sync is cut short, it is simply re-run.
- Keep instructions token-efficient by using scripts for deterministic sync mechanics.
- Never create or overwrite a target `.sdlc/clickup-config.json` without explicit user agreement, and never invent Space/List/status IDs — read every ID back from ClickUp before writing it.
- Never treat a missing `.sdlc/clickup-config.json` as a blocker. Local artifact mode is a supported outcome; only a malformed config is an error.
- Never commit or push the ClickUp config or token. The REST token lives in gitignored `.sdlc/clickup-token` and nowhere else — never in a tracked file, never in chat, never echoed into script output.
- Never ask the user for the token value, and never handle it yourself. Point them at the file; they write it.
- Never skip the create-and-delete probe on QA lists when finishing ClickUp setup — an unverified list fails later, mid-flow, with a permanent per-list quota error.
- Never report ClickUp setup as complete while `setup-clickup.ps1` reports any `FAIL`. A missing token in particular produces no runtime error at all — it just turns every verification into a silent no-op.

## References and related skills

- Registry: [../../AGENTS-REGISTRY.md](../../AGENTS-REGISTRY.md)
- Sync helper: [../../scripts/sync-copilot-assets.ps1](../../scripts/sync-copilot-assets.ps1)

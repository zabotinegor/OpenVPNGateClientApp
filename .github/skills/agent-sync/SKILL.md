---
name: agent-sync
description: "Mirror-sync Copilot customization assets from the latest zabotinegor/CopilotTools main commit into a target repository with deterministic stale-file deletion, exact-path .gitignore policy, and verification reporting."
argument-hint: "what should be synced from CopilotTools and to which target paths"
---

# Agent Sync

## Summary

Synchronize agent, skill, tool, and helper-script assets from the configured CopilotTools Git repository into the current target repository. Prefer deterministic scripts and keep the final report focused on decisions, results, and blockers.

## When to use

- Mirror-sync Copilot agents, skills, tools, or scripts into another repository.
- Reconcile stale synced files in a target repository.
- Update target `.gitignore` entries for synced non-agent-sync files.
- Add target `.gitignore` entries for transient Copilot handoff/prompt artifacts, runtime `.sdlc/status.json`, and `.sdlc/operations/`.

## Expected input

- Target repository/worktree.
- Requested sync scope or default Copilot customization scope.
- Any paths that must be excluded from sync.

## Blocking gates

- Resolve and report the latest source commit SHA from the configured CopilotTools Git repository before editing, using foreground git/terminal when available or authenticated GitHub connector/API tools when terminal execution is unavailable.
- Stop before editing only if no available tool can identify the source revision or source file contents.
- Verify the target branch/worktree state before changing files.
- Protected root markdown files (`AGENTS.md`, `README.md`, `AGENTS.local.md`, `README.local.md`) are blocked from sync by default and require explicit user approval plus script flag `-AllowRootMdSync`.
- Do not delegate sync execution or comparison to a subagent. Run Agent Sync in the current chat because subagents may not receive terminal tools or the target workspace context.
- Do not run sync through VS Code task labels or other task launchers. For Agent Sync, use `run_in_terminal` first and `runCommands` second for foreground PowerShell. Task launchers are not reliable for required sync because cancellation/completion may not return script output, source SHA, file counts, or exit code.
- Do not infer that terminal execution is unavailable. Report terminal/command execution unavailable only after an actual terminal-capable tool call fails with an unavailable-tool/capability error.
- Treat `Canceled` from any task launcher as a launcher failure, not as a sync blocker. Immediately switch to `run_in_terminal` and then `runCommands` in direct foreground PowerShell; if direct script execution is unavailable after both real failed attempts, use the manual mirror-sync fallback instead of asking the user to run commands.

## Workflow

1. Read `AGENTS.md`, `.github/AGENTS-REGISTRY.md`, and target worktree state.
2. Resolve the latest source commit SHA from the configured `SourceRepo`/`SourceRef`.
3. Prefer `.github/scripts/sync-copilot-assets.ps1`; perform manual mirror-sync only after real failures of `run_in_terminal` and `runCommands` prove direct script execution is unavailable.
   Run the script with `run_in_terminal` first (foreground PowerShell), not as a VS Code task:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .github/scripts/sync-copilot-assets.ps1 -DryRun`
   If `run_in_terminal` is unavailable, retry the same command with `runCommands`.
   Keep root markdown protection enabled by default; include `-AllowRootMdSync` only when user explicitly approved syncing protected root markdown files.
   Then run the apply command only after reviewing the dry-run JSON:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .github/scripts/sync-copilot-assets.ps1`
4. If direct script execution is unavailable, use manual fallback through authenticated GitHub connector/API tools at the resolved source revision; compare source and target assets in scope by relative path and content. Do not use unauthenticated browser pages as source evidence for private repositories.
5. Verify differences before editing, especially frequently changed agent/skill files.
6. Apply add/update/delete operations only inside the agreed sync scope.
7. Delete target files in scope that do not exist in source, except paths containing `agent-sync` or `sync-copilot-assets`.
8. Update target root `.gitignore` with exact synced file paths, excluding paths containing `agent-sync` or `sync-copilot-assets`.
9. Update target root `.gitignore` with transient Copilot artifact ignores for `*_HANDOFF*.md`, `*_PROMPT*.md`, `*_PROMT*.md`, `CODE_REVIEW_HANDOFF_*.md`, `.sdlc/status.json` at any depth, and `.sdlc/operations/`.
10. Report forbidden handoff/prompt artifacts and nested `.sdlc/status.json` files found in the target worktree; do not silently delete them unless the user requested cleanup.
11. Re-check synced files against the resolved source commit after edits. With manual fallback, verify by content comparison for every changed file and clearly mark script verification as not run.
12. Produce the required change report.

## Output format

Report source repository and commit SHA, target branch, sync scope, added/changed/deleted counts and paths, stale-file deletion status, post-sync verification, synced `.gitignore` policy verification, transient artifact `.gitignore` verification, discovered forbidden artifacts, discovered nested `.sdlc/status.json` files, token-efficiency note for manual fallback, and blockers or assumptions.

## Constraints or rules

- Never delete `agent-sync.agent.md`, `.github/scripts/sync-copilot-assets.ps1`, or any path containing `agent-sync` or `sync-copilot-assets`; update those files only when source differs.
- Never add broad ignore patterns such as `/.github/agents/**`, `/.github/skills/**`, `/.github/tools/**`, or `/.github/scripts/**`.
- Never hide agent-sync-related files through `.gitignore`.
- Never sync protected root markdown files (`AGENTS.md`, `README.md`, `AGENTS.local.md`, `README.local.md`) unless user approval and `-AllowRootMdSync` are both present.
- Never create handoff/prompt markdown artifacts while reporting sync results; include handoff text in chat only.
- Never invoke VS Code tasks, task labels, or "Run task" for sync dry-run or apply. Use `run_in_terminal` first and `runCommands` second with direct foreground PowerShell so the agent receives JSON output, exit code, and errors.
- If terminal/command execution is unavailable after a real failed terminal-capable tool call, do not ask the user to run commands. Complete sync manually with available read/search/edit plus authenticated GitHub connector/API tools and report the fallback. Stop only if neither terminal nor authenticated source access is available.
- If a previous task-based attempt was cancelled, explicitly switch to `run_in_terminal` and then `runCommands` with direct foreground PowerShell; if unavailable after real failed attempts, use manual mirror-sync fallback before reporting any blocker.
- Do not commit or push unless explicitly requested.
- If any post-sync file mismatches source, stop and report the mismatch.
- Keep instructions token-efficient by using scripts for deterministic sync mechanics.

## References and related skills

- Registry: [../../AGENTS-REGISTRY.md](../../AGENTS-REGISTRY.md)
- Sync helper: [../../scripts/sync-copilot-assets.ps1](../../scripts/sync-copilot-assets.ps1)

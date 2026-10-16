---
name: agent-sync
description: "Mirror-sync manifest-declared CopilotTools agent, skill, script and runtime assets into the current client branch, with safe stale-file deletion, exact gitignore policy and verification."
argument-hint: "what should be synced from CopilotTools and to which target paths"
---

# Agent Sync

## Summary

Use the deterministic `.github/scripts/sync-agent-assets.ps1` to reconcile
client-facing shared/platform assets from
`.github/copilottools/distribution.json` and runtime-owned assets from
`.github/copilottools/runtimes/*.json`. Shared orchestration must not
special-case runtime IDs or duplicate native runtime paths. Source-only
ReviewBot service assets are excluded; generic external PR review tooling
in distribution scope is not.

This workflow installs the SDLC/session machinery; it must not invoke it.

## Ownership and client Git-tracking policy

CopilotTools owns the source and tests for all shared agent infrastructure.
A client repository tracks only the **pre-sync bootstrap**: the `agent-sync`
skill and platform launchers, the sync script and its dot-sourced helpers,
and native Git hooks/branch guards. Never commit copied infrastructure tests.

Operational SDLC skills, agents, scripts and runtime manifests are copied to
the local client worktree but individually **gitignored**; existing runtime
entrypoints expect those local paths. Changing to an external runtime cache
requires a separate coordinated migration.

CopilotTools test assets are **source-only, not copied at all**:
`.github/scripts/test-*`, `.github/scripts/run-all-tests.ps1`, and
`.github/tests/`. Client-owned application tests are independent and must
not be removed merely because they are named `test`.

Agent Sync owns the exact `.gitignore` projections: the synced-assets block
covers all non-bootstrap files from the effective source/runtime manifest,
with obsolete entries pruned; the transient block contains unique per-machine
state patterns. Preserve client-specific ignore rules outside these blocks.
Never broad-ignore agent directories or native branch protection. A formerly
tracked infrastructure test needs an explicit client commit to remove it
from the Git index: an ignore rule alone cannot untrack an indexed file.

Canonical standalone guard regressions can be run from CopilotTools with:
`sh .github/tests/test-pre-push-dev-archive-guard.sh` and
`pwsh -NoProfile -File .github/tests/test-protect-agent-git-command-decisions.ps1`.


## When to use

- Bring a target branch/worktree up to a verified source revision.
- Repair missing/stale synced agents, skills, hooks, scripts or runtime assets.
- Rebuild the exact managed `.gitignore` entries and transient paths.
- Bootstrap/verify artifact mode (ClickUp or local) after source sync.

## Expected input

Target repository and current worktree, requested scope or default manifest
scope, explicit exclusions, and any **explicit** permission to modify protected
root Markdown. Otherwise leave protected root Markdown unchanged.

## Blocking gates

- **Session exemption:** Never call `init-session.ps1`, usage/reset tracking,
  `check-tracking-preflight.ps1`, recovery cron, leases or
  `.sdlc/status.json` checkpoints. Existing flows, stale recovery state or
  lease holders cannot block a sync. An interruption is recovered by rerunning
  the sync. See [agents-core-rules](../shared/agents-core-rules.md).
- **Current branch only:** Inspect target branch and worktree. Never create,
  switch, check out or reset a branch; never stage, commit or push target
  changes. This holds even if sync fails.
- **Source attestation:** Resolve configured `SourceRepo`/`SourceRef` to an
  exact CopilotTools Git source commit before touching files. Without a
  verifiable revision and source content, stop.
- **Root protection:** `AGENTS.md`, `README.md`, `AGENTS.local.md` and
  `README.local.md` require explicit permission **and** `-AllowRootMdSync`.
  That includes writing the marked universal governance section to AGENTS.
  Without both, plain sync must leave those files untouched.
- **Installer survival:** Never delete `agent-sync` or `sync-agent-assets`
  files or sync the source-only `.agenttools-source` marker. Never broad-ignore
  agents, skills, scripts, hooks or protected branch guards.
- **Script and tool discipline:** Prefer direct foreground `pwsh 7+`
  with `run_in_terminal`, falling back to `runCommands`. Do not use VS Code
  task launchers or delegate sync to a subagent. Do not claim a terminal is
  unavailable without real failed terminal-capable attempts; an error from
  a working sync script must be reported, not bypassed. If both direct tools
  are genuinely unavailable, load
  [manual-fallback.md](references/manual-fallback.md) and use authenticated
  GitHub connector/API tooling only, no standalone Git commands.
- **No hidden failure:** A mismatch, unsafe deletion, incomplete hooks,
  misconfigured ignore list or a failing ClickUp setup check must be
  reported; do not report completion before verification.

## Workflow

1. **Resolve authoritative scope.** Read target `AGENTS.md`, any explicitly
   declared cross-repository sync convention, and the current worktree
   state. Read agent registry **only if** required to classify a changed
   agent/skill or resolve an affected mapping; no cold full registry read.
   Identify the configured source ref and resolve its latest commit SHA.
2. **Run deterministic preview:** use a direct foreground tool to run
   `pwsh -NoProfile -File .github/scripts/sync-agent-assets.ps1 -DryRun`.
   Inspect source SHA and add/update/delete categories from its JSON.
   Where tool availability is established, use the same script without
   `-DryRun` for apply. Add `-AllowRootMdSync` only with express approval.
3. **Reconcile safely:** Script-owned asset enumeration, runtime manifests,
   source SHA verification, safe stale deletion, config merges, managed
   ignore generation and post-sync adapters remain authoritative. Never
   replace them with ad hoc LLM file-by-file editing when the script works.
   The managed ignore block must cover all in-scope synced files, not just
   changed files, while leaving `agent-sync`, sync scripts, Git hooks and
   protected branch guards trackable. Runtime transient paths are declared
   by manifests. Do not copy the source-only marker.
4. **Verify the result:** Verify source-only test scripts and runners are
   absent from the client copy; managed ignored paths match the effective
   source/runtime inventory minus bootstrap, and transient patterns are unique.
   Compare against the resolved source revision,
   including unchanged assets, stale-file deletion, `.gitignore` entries,
   runtime post-sync checks, trusted hook installation outside the worktree
   and configured Git hooks path. Report prohibited prompt/handoff artifacts
   and nested `.sdlc/status.json` paths; do not silently remove user files.
   A sync result with unmatched files or unexpected untracked synced paths
   is not complete.
5. **Check artifact mode:** Run read-only
   `pwsh -NoProfile -File .github/scripts/setup-clickup.ps1` once after sync.
   A valid ClickUp config must have REST fallback, correct statuses,
   ignores, and QA List probes when configured or changed. A missing
   ClickUp config is supported `artifactMode: local`, **not** an error.
   Run `-Probe` when config changed or QA lists are unverified. Read the
   detailed [ClickUp setup playbook](references/clickup-setup.md) **only**
   when a check needs remediation, the user requests onboarding, or
   consent/credentials or list recovery need user actions. Never request,
   handle, expose or invent a token, ClickUp ID or status.
6. **Report verified outcome.** Follow the [output contract](#output-format).

## Output format

Use the structured JSON result as the source
for counts and runtime categories. Include source repo/commit SHA, current
target branch, manifest-driven scope, add/update/delete counts and paths,
stale-file reconciliation, removed dead hooks, governance marker action
(null without `-AllowRootMdSync`), ignore and runtime verification,
trusted hooks configuration, source marker absence, untracked file state,
forbidden artifacts, nested statuses, and blockers. Report
`artifactMode`, setup status and non-OK ClickUp checks with remediation;
clearly state any user action still required. A fallback must identify
every verification it could not perform.

## Constraints or rules

- Never weaken branch protection, session exemption, scope boundaries,
  private config handling, safe deletion or post-sync verification.
- Never add broad ignore patterns such as `/.github/scripts/**`,
  `/.github/skills/**`, `/.github/agents/**` or `/.codex/agents/**`.
- Never create handoff/prompt Markdown artifacts, and do not bypass a failed
  deterministic sync by committing, switching branch or inventing a workaround.
- Keep client-specific README/AGENTS text outside approved governance markers.
- ClickUp setup is owned by Agent Sync. Never fabricate a working ClickUp
  result while any mandatory setup check is FAIL; never write config/token
  without explicit consent. Credential creation and OAuth belong to the user.
- Use only observed runtime metadata in reports; never invent a check result.

## References and related skills

- [Manual fallback — load only if direct terminal tools are unavailable](references/manual-fallback.md)
- [ClickUp setup — load only for onboarding/remediation](references/clickup-setup.md)
- [Runtime distribution manifest](../../copilottools/distribution.json)
- [Shared operational rules](../shared/operational-rules.md)

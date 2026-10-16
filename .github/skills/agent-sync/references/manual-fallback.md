# Agent Sync: Manual Mirror-Sync Fallback

This is an exceptional, high-cost path. Load it only after real attempts at
both direct foreground terminal tools fail because terminal execution is
unavailable. A failed sync with a working terminal is **not** permission to
bypass the deterministic script: report its error instead.

## Source resolution and allowed tools

1. Prove both `run_in_terminal` and `runCommands` are unavailable with actual
   attempted tool calls. A VS Code task `Canceled` is a launcher failure, not
   proof the terminal is unavailable.
2. Obtain the configured CopilotTools source ref and exact latest commit SHA
   through authenticated GitHub connector/API tools. Stop without editing if
   source revision or file contents cannot be established.
3. Never use `git clone`, `git sparse-checkout`, or standalone Git as a
   manual fallback: they require the terminal that has already failed.
   Use read/search/edit and authenticated GitHub connector/API only, never
   unauthenticated browser pages as evidence for private repositories.

## Safe mirror and verification

1. Inspect the existing target branch/worktree without creating, switching,
   staging, committing or pushing any branch.
2. Apply the canonical exclude patterns to the source inventory before
   copying: never copy CopilotTools `.github/scripts/test-*`,
   `.github/scripts/run-all-tests.ps1` or `.github/tests/`. Preserve
   separately owned client application tests. Enumerate in-scope source files
   from the shared distribution manifest
   plus every declared runtime manifest (no hard-coded runtime IDs). Compare
   exact relative paths and contents at the resolved source SHA.
3. Mirror additions/updates/deletions within the approved scope; never delete
   any `agent-sync` or `sync-agent-assets` path. Root protected Markdown,
   including governance marker injection, needs user approval **and**
   `-AllowRootMdSync`; do not write it during an ordinary sync.
4. Build the entire managed `.gitignore` block between
   `# BEGIN synced-agent-assets` and `# END synced-agent-assets` using all
   in-scope files, not only changed files. Exclude `agent-sync` and
   `sync-agent-assets`; keep hooks, branch guards and these protected paths
   trackable. Add shared transient ignore entries and runtime-manifest
   `distribution.transientIgnorePaths`, keeping only the first occurrence
   of each pattern; never broad directory ignores.
5. Verify that the managed ignore block count equals all in-scope sync paths
   minus protected exclusions, that source bytes match for **every** changed
   file and that no unexpected untracked paths remain. If a path remains
   tracked despite a new ignore rule, **report** that it must be untracked
   by the repository owner; Agent Sync itself never runs `git rm --cached`
   because it must not stage changes.
6. Preserve local config merge semantics, installed trusted Git hooks outside
   the checkout, runtime post-sync verification and source-only marker
   exclusion. If a required operation cannot be accomplished without a
   terminal, report the specific unverified item and do not claim success.

## Result

Report source SHA, target branch, scope, per-category added/changed/deleted
counts and paths, `.gitignore` entries vs expected, stale deletion,
config/runtime verification, hook installation, forbidden artifacts, source
marker checks, and exact unresolved blockers. Identify manual execution and
state explicitly that script verification was not run. The normal ClickUp
probe/onboarding obligation still applies; load
[clickup-setup.md](clickup-setup.md) only when remediation is needed.

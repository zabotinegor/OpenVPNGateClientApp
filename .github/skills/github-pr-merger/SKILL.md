---
name: github-pr-merger
description: "Squash-merge the current branch PR: resolve PR, draft commit message from real diff in repository style, execute squash merge, and delete feature branch."
argument-hint: "optional PR number or URL"
user-invocable: true
---

# GitHub PR Merger

Merge in this order: resolve PR, analyze diff, draft squash message, confirm, execute squash merge, delete branch.

## Workflow

1. Read `AGENTS.md`.
2. Resolve target PR (auto by current branch, or explicit PR number/URL).
3. Inspect `origin/main..HEAD` with git commands used in create-pr skill.
4. Group changes by coherent themes.
5. Draft squash commit message using [references/squash-merge-style.md](./references/squash-merge-style.md):
   - Title: `{exact PR title} (#{PR number})`
   - Body: structured, factual, derived from real diff.
6. Confirm with user before merge unless user already gave explicit go-ahead.
7. Execute squash merge (prefer `gh pr merge --squash`).
8. Delete remote and local feature branch after successful merge.
9. Report merge URL, commit SHA if available, and deletion status.

## Rules

- Squash merge only.
- Do not merge with failing required checks unless user explicitly accepts risk.
- Do not claim success unless confirmed by command/API.

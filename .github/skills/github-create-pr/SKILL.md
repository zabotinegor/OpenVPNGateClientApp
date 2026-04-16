---
name: github-create-pr
description: "Analyze the current branch against origin/main, draft a professional GitHub PR title/body in repository style, and create the PR when supported tooling/auth is available."
argument-hint: "optional base branch or PR scope"
user-invocable: true
---

# GitHub Create PR

Create PRs in this order: inspect branch state, analyze `origin/main..HEAD`, draft title/body, then create the PR.

## Workflow

1. Read `AGENTS.md` and relevant docs for changed scope.
2. Resolve comparison target (`origin/main` by default).
3. Inspect change set with git:
   - `git branch --show-current`
   - `git fetch origin main`
   - `git log --oneline origin/main..HEAD`
   - `git diff --stat origin/main..HEAD`
   - `git diff --name-only origin/main..HEAD`
4. Group changes into 2-5 coherent themes.
5. Draft concise English PR title in past tense (no `feat:`/`fix:` prefixes).
6. Draft PR body per [references/pr-style.md](./references/pr-style.md).
7. Create PR via approved tooling (`gh pr create` preferred).
8. Report title, body, and PR URL/number, or exact blocker.

## Tool Selection Map (Mandatory)
- Use `get_changed_files` first for quick branch sanity check.
- Use `run_in_terminal` next for git-based branch and diff commands from step 3.
- Use `run_in_terminal` deep diff per file only if summary output is insufficient.
- Use `github-pull-request_create_pull_request` for PR creation.
- Use `github-pull-request_issue_fetch` only after PR creation when metadata verification is required.

## Efficiency Rules
- Prefer git diff/log output over workspace-wide search tools when drafting PR content.
- Avoid `search/textSearch`, `search/codebase`, and `search/usages` for normal create-PR flow.
- Read source files only for ambiguous diffs or explicitly requested detail.
- If `origin/main` is not available, use `origin/<default>` and state the fallback.

## Rules

- Use factual diff-derived content, not assumptions.
- Mention contract/build/workflow/test impacts when relevant.
- Do not claim PR creation unless command/API succeeded.
- Use past-tense action verbs in title and body (for example: `Added`, `Updated`, `Fixed`, `Refactored`).

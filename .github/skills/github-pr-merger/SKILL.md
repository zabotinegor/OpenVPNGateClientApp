---
name: github-pr-merger
description: "Squash-merge the current branch's open PR: resolve the PR, draft a concise plain-language commit message from the actual diff, execute squash merge, and delete the feature branch. Use when the user asks to merge a PR, squash merge, clean up after merge, or close a feature branch."
argument-hint: "optional PR number or URL"
user-invocable: true
---

# GitHub PR Merger

Merge PRs in a predictable order: resolve the PR, analyze the diff, draft the commit message, execute squash merge, then delete the branch.

## When to use

- User asks to merge a PR with squash strategy.
- Need deterministic squash message from actual diff.
- Need post-merge branch cleanup.

## Expected input

Provide when available:

- PR number or URL
- repository and base-branch constraints
- branch deletion preference (remote/local)

## Output format

1. MCP server availability check
2. Resolved PR and branch context
3. Build and test results (pass/fail, command used)
4. Manual QA result (all ACs verified / blocked / failed)
5. Drafted squash commit title and body
6. Explicit user approval status (approved / pending / rejected)
7. Merge result and commit SHA (if available)
7. Remote/local branch deletion status
8. Current branch after checkout + pull

## Pre-flight: MCP Server Check

Run the MCP server pre-flight check from [../shared/sdlc-shared-flow-patterns.md](../shared/sdlc-shared-flow-patterns.md#mcp-server-pre-flight-check) before starting the workflow.

## Blocking gates

- Run the build and all tests before merge (for every target branch, not only main/dev). If build or tests fail, stop and report the blocker.
- Build and deploy the latest version from the current branch before Manual QA. If deploy fails or the running version does not match the branch, stop and report the blocker. Do not QA on stale artifacts.
- Run Manual QA Agent before merge for every target branch. Verify all story ACs still pass after review-comment fixes. Stop if QA fails or cannot run.
- If Manual QA cannot fully complete a required check and requires user-assisted verification, stop the merge flow until QA has provided detailed steps, received the requested proof, assessed it as sufficient, and returned a passed result. User confirmation without assessed proof is not a substitute for Manual QA.
- Show the exact squash commit title and body before merge.

## Workflow

0. Run MCP server pre-flight check (see Pre-flight section above).
1. Read repository-local guidance.
   Check `.github/skills/shared/operational-rules.md` and `AGENTS.md` so repository conventions are respected in the commit message.
2. Resolve the target PR.
   Prefer detecting the open PR for the current branch automatically.
   If automatic lookup fails, use the PR number or URL provided by the user.
   If no open PR can be resolved, stop and report a blocker. Do not create a new PR in this workflow.
3. Run build and all tests.
   Detect build and test commands from stack markers (`build.gradle*`, `package.json`, `Makefile`, `*.sln`, `docker-compose*.yml`) and `docs/runbooks/`.
   Run the full build and all tests (unit, integration, instrumented where feasible).
   If build or tests fail: report exact error and stop. Do not proceed to the next step.
   Document any newly discovered command in `docs/runbooks/` if not already present.
4. Build and deploy the latest version before Manual QA.
   Detect build/deploy commands from stack markers and `docs/runbooks/`.
   Build the latest artifacts from the current branch. Deploy them: `adb install -r` (Android), `docker-compose up -d --build` (Docker), restart service with fresh build (.NET/Node/Python), or equivalent.
   Verify the running version matches the current branch/commit. If build, deploy, or version verification fails: stop and report the blocker. Do not QA on stale artifacts.
5. Run Manual QA Agent.
   Spawn a **Manual QA subagent** with the story path (from `.sdlc/status.json` or the PR description), diff scope, and instruction to verify every story AC is still working after review-comment fixes.
   Wait for the result. If QA fails, is blocked, or story context is missing — stop and report. Do not replace this gate with user confirmation.
6. Inspect the change set with git.
   - `git branch --show-current`
   - `git fetch origin main`
   - `git log --oneline origin/main..HEAD`
   - `git diff --stat origin/main..HEAD`
   - `git diff --name-only origin/main..HEAD`
   Read key changed files when the summary alone is not enough to write a precise commit message.
7. Group the change set into coherent user-facing themes.
   Prefer semantic sections such as Fixed, Added, Updated, and Improved when they fit the diff.
   Do not mirror every commit one-to-one when a higher-level summary is clearer.
8. Draft the squash commit message.
   Follow the format from [references/squash-merge-style.md](./references/squash-merge-style.md).
   - **Title line**: derive a concise user-facing summary from the actual diff. Not from the PR title or story title.
   - **Body**: valid Markdown with one `### Category` heading per semantic group and plain bullets below it.
   Do not mention story ids, story names, ticket numbers, flow ids, or other internal tracking labels.
9. Execute squash merge.
   Use `vscode/askQuestions` to show the final squash commit title and body and ask for approval. Present the title and body in the question body so the user can read and approve or request changes inline.
   Only after the user confirms with a separate, explicit message, execute:
   `gh pr merge {PR number} --squash --subject "{commit title}" --body "{commit body}"`.
   Use merge-only tooling (`gh pr merge` or GitHub merge API/tools). Never call PR creation tools.
   Do not proceed until the command/API confirms success.
10. Delete branch and switch to base.
   After confirmed merge:
   - Resolve the PR head branch and verify it is not `main`, `dev`, `master`, or `develop`. If it is protected, do not delete or mutate it; report `BLOCKED` for cleanup.
   - Delete the remote head branch only when it is non-protected: `git push origin --delete {branch}`.
   - Switch to base branch: `git checkout {base-branch}` (use the PR base branch — `main`, `dev`, etc.).
   - Pull latest: `git pull`.
   - Delete local branch: `git branch -d {branch}`.
11. Report the result.
    Return the merged PR URL/number, commit SHA, branch deletion status, and current branch after checkout.

## Commit Title Rules

- Derive the title from the actual diff, not from the PR title or story title.
- Keep the title concise, plain-language, and understandable to end users.
- **The title is user-facing release text — write it for the end user, not for developers.**
- Keep it in English regardless of the PR body language.
- Do not include story ids, story names, ticket ids, PR numbers, flow ids, branch names, or similar internal tracking labels.
- Do not frame the title as a developer task description when a user-facing outcome is available.
- Avoid technical jargon, implementation details, and code-level specifics.

## Commit Body Rules

- The body MUST be valid Markdown with semantic sections, for example `### Fixed`, `### Added`, `### Updated`, `### Improved`.
- Under each heading, use plain Markdown bullets. Do not repeat the category label in every bullet.
- Group by user-facing meaning, not by file, commit, or implementation layer.
- Keep related changes together even when they touched multiple files.
- Omit categories that truly do not apply rather than filling them with noise.
- Keep the body short, usually 3-7 bullets total.
- Start each bullet with the user-visible outcome rather than the implementation detail.
- **The body is user-facing release text — write it for the end user, not for developers.**
- Keep the language factual, plain-language, and easy for a non-technical user to scan quickly.
- Avoid technical jargon, implementation details, and code-level specifics unless absolutely necessary for clarity.
- Do not use a single free-form `What's New` block or paragraph list in place of semantic `### Category` sections with bullets.
- Do not copy the PR body verbatim — re-derive from the diff so the message reflects actual changes.
- Do not mention story ids, story names, ticket ids, flow ids, branch names, or other internal tracking labels.
- Mention internal implementation details only when they are required to avoid ambiguity.

## Tool Safety Guardrails

- Merge-only flow: use PR read/status/merge operations.
- Before any branch deletion or other ref mutation, resolve every destination ref and stop with `BLOCKED` if it is `main`, `dev`, `master`, or `develop`.
- Never directly push, force-push, create, delete, recreate, or otherwise mutate a protected branch. Only the approved PR merge operation may write to a protected branch.
- Never invoke PR creation operations such as `gh pr create`, `github-pull-request_create_pull_request`, `github/create_pull_request`, `github/create_pull_request_with_copilot`, `github.vscode-pull-request-github/create_pull_request`.
- If PR resolution fails (no open PR), report the blocker and ask the user whether they want to run a separate create-PR workflow.
- Do not merge until the user explicitly approves the exact squash commit title and body shown in final form via `vscode/askQuestions`. Treat only a separate, explicit post-draft confirmation message as approval; do not treat initial merge intent or handoff prompt wording as approval.

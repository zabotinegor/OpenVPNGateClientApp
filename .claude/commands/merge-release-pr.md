---
description: Squash-merge a release PR into main with build+tests, Manual QA, user-approved squash message, and no direct protected-branch mutation
---

Merge the release PR into main. Load AGENTS.md first. Check session rate limit at start: if `rate_limits.five_hour.used_percentage` >= 90%, checkpoint and stop (see .github/skills/session-limit-tracking/SKILL.md).

Then follow `.github/skills/github-pr-merger/SKILL.md` plus the release-specific rules below.

$ARGUMENTS

**Hard requirement: the PR base branch must be exactly `main`. Reject any other target.**

## Workflow

1. Resolve the release PR (open PR for current branch or from $ARGUMENTS). Verify base=main. Stop if no open PR or wrong base.

2. **Build and all tests** (mandatory):
   Detect build/test commands from stack markers and `docs/runbooks/`. Run full build + all tests (unit, integration, instrumented where feasible).
   If build or tests fail: report exact error and stop.

3. **Build and deploy latest version** (mandatory):
   Detect build/deploy commands from stack markers and `docs/runbooks/`. Build latest artifacts from current branch. Deploy: `adb install -r` (Android), `docker-compose up -d --build` (Docker), restart service with fresh build (.NET/Node/Python), or equivalent.
   Verify running version matches current branch/commit. If build, deploy, or version check fails: stop and report blocker. Do not QA on stale artifacts.

4. **Manual QA — every story listed in this PR** (mandatory):
   Extract the list of user stories that are part of **this specific PR** (from the PR description, linked issues, story paths in `docs/userstories/`, or `.sdlc/status.json` flows whose branch is included in this PR's diff). Do not include stories from other PRs or branches.
   Spawn an internal Agent-tool subagent (prompt starts with `/manual-qa`) with that scoped story list, diff scope, and instruction to verify every AC of every story in this PR is still working after all review-comment fixes. This is intentionally an Agent-tool subagent because the merge workflow must wait synchronously for its result; do not represent this internal call as an external slash-command handoff.
   Wait for a passed result for each story in this PR. If QA fails for any of them, is blocked, or story context is missing — stop and report. Do not replace this gate with user confirmation.

5. Inspect the diff:
   git fetch origin main
   git log --oneline origin/main..HEAD
   git diff --stat origin/main..HEAD

6. Draft the squash commit message:
   - Title: concise, plain-language, end-user-facing. Derived from the actual diff.
   - Body: valid Markdown with semantic sections (### Fixed, ### Added, ### Updated, ### Improved) and plain bullets. Group by user-facing meaning.
   - Do NOT include story ids, story names, flow ids, ticket ids, PR numbers, branch names, or internal tracking labels.

7. Use `vscode/askQuestions` to show the exact title and full body and request a separate explicit confirmation message. No prior instruction or handoff text counts as this confirmation.

8. Execute squash merge: `gh pr merge {PR number} --squash --subject "{title}" --body "{body}"`

9. After confirmed merge:
   - Resolve the release PR head branch and verify it is not `main`, `dev`, `master`, or `develop`. If it is protected, do not delete or mutate it; report `BLOCKED` for cleanup.
   - Delete the remote release branch only when it is non-protected: `git push origin --delete {branch}`.
   - Switch to main: `git checkout main`.
   - Pull latest: `git pull`.
   - Delete local release branch: `git branch -d {branch}`.

10. Verify that `dev`, `main`, `master`, and `develop` were not directly pushed, deleted, recreated, force-updated, or otherwise mutated outside the approved PR merge.

11. Report PR URL, commit SHA, current branch, branch deletion status, protected-branch preflight result, and blockers.

## Hard stops

- Direct commit/push or remote branch/ref mutation for `main`, `dev`, `master`, or `develop` is permanently forbidden. The only allowed protected-branch write is the approved `gh pr merge`.
- Do not merge without build+tests passed.
- Do not merge without latest version deployed and verified.
- Do not merge without Manual QA passed for every story that is part of this specific PR.
- Do not merge without explicit post-draft user confirmation of the squash commit.
- User approval cannot authorize protected-branch push, deletion, recreation, force-update, or other direct ref mutation.

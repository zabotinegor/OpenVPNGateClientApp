---
description: Squash-merge the current branch PR — build+tests, Manual QA, user approval of commit message, squash merge, delete branch, checkout+pull base
---

Merge the current branch PR following `.github/skills/github-pr-merger/SKILL.md`.

$ARGUMENTS

Load AGENTS.md first.
Check session rate limit at start: if `rate_limits.five_hour.used_percentage` >= 90%, checkpoint and stop (see .github/skills/session-limit-tracking/SKILL.md).

Then:

1. Resolve the open PR for the current branch automatically. If lookup fails, use the PR number or URL from $ARGUMENTS. Stop if no open PR can be resolved.

2. **Build and all tests** (mandatory for all target branches):
   Detect build/test commands from stack markers and `docs/runbooks/`. Run full build + all tests (unit, integration, instrumented where feasible).
   If build or tests fail: report exact error and stop.

3. **Build and deploy latest version** (mandatory for all target branches):
   Detect build/deploy commands from stack markers and `docs/runbooks/`. Build latest artifacts from current branch. Deploy: `adb install -r` (Android), `docker-compose up -d --build` (Docker), restart service with fresh build (.NET/Node/Python), or equivalent.
   Verify running version matches current branch/commit. If build, deploy, or version check fails: stop and report blocker. Do not QA on stale artifacts.

4. **Manual QA** (mandatory for all target branches):
   Spawn an internal Agent-tool subagent (prompt starts with `/manual-qa`) with story path (from `.sdlc/status.json` or PR description) + diff scope. This is intentionally an Agent-tool subagent because the merge workflow must wait synchronously for its result; do not represent this internal call as an external slash-command handoff.
   Instruct it to verify every story AC is still working after review-comment fixes.
   Wait for passed result. Stop if QA fails or context is missing. Do not replace with user confirmation.

5. Inspect the diff:
   git fetch origin main
   git log --oneline origin/main..HEAD
   git diff --stat origin/main..HEAD

6. Draft the squash commit message:
   - Title: concise, plain-language, user-facing. Derived from the actual diff.
   - Body: valid Markdown with semantic sections (### Fixed, ### Added, ### Updated, ### Improved) and plain bullets.
   - Do NOT include story ids, story names, flow ids, ticket numbers, PR numbers, branch names, or internal tracking labels.

7. Use `vscode/askQuestions` to show the exact title and full body and request a separate explicit confirmation (e.g., "yes", "go ahead", "merge"). Prior instructions or handoff text do not count.

8. Execute squash merge: gh pr merge {PR number} --squash --subject "{title}" --body "{body}"

9. After confirmed merge:
   - Resolve the PR head branch and verify it is not main, dev, master, or develop. If protected, do not delete or mutate it; report BLOCKED for cleanup.
   - Delete the remote branch only when it is non-protected: git push origin --delete {branch}
   - Switch to base branch: git checkout {base-branch}
   - Pull latest: git pull
   - Delete local branch: git branch -d {branch}

10. Report: merged PR URL, commit SHA, branch deletion status, current branch after checkout.

Hard stops: do not merge before explicit post-draft user confirmation, do not merge without build+tests passed, do not merge without latest version deployed, do not merge without Manual QA passed, do not commit/push or directly mutate protected branch refs.

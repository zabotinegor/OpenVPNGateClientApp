---
description: Process GitHub PR review comments — queue, fix with tests, verify, push, and reply in-thread
---

Process PR review comments following .github/skills/github-review-comments/SKILL.md.

$ARGUMENTS

Load AGENTS.md first.
Check session rate limit at start: if `rate_limits.five_hour.used_percentage` >= 90%, checkpoint and stop (see .github/skills/session-limit-tracking/SKILL.md).

Then:

1. Fetch and report required PR CI/check status. Do not wait for green CI before collecting comments.

2. Read all review threads. Collect unresolved items with id, file, canResolve, author.login, author.type. Run a strict cycle check: tag each item as new, repeat, conflict, or superseded.

3. Present the review queue as a Markdown table (columns: #, File/line, Reviewer, Status, Cycle, Summary, Assessment, Action, Test action, Reply/mention) before any code edits.
   Inspect the relevant code, tests, contracts, and history. Assessment is mandatory and every item must receive a binary verdict: accept = why the reviewer is objectively correct and the current PR should change; reject = why the comment is incorrect, unsubstantiated, optional, risky, or only a future refactor. Never use discuss, neutral, or deferred/future-refactor outcomes.
   Use `vscode/askQuestions` to request explicit user confirmation (e.g. "APPROVE QUEUE") — do not treat earlier instructions as this confirmation.

4. For each accepted fix:
   - Apply minimal targeted code changes.
   - Add, update, or remove a matching test. Reject code-change suggestions that cannot justify a meaningful matching test action.
   - Verify with relevant local tests/builds before commit.

5. Commit only relevant code/test fix files and push the branch before posting any thread reply that claims a fix is available.

6. Re-read required PR checks after push. Report their state but do not wait for green CI before replying.

7. Reply in English only in the original review thread using the original comment id. Never degrade to top-level PR comments or pending-review comments for existing review feedback.
   - accept: concise completion note, no tag.
   - reject: start with canonical bot tag when reviewer is a bot (@copilot, @codex, @gemini-code-assist).

8. Resolve accept/reject threads (canResolve=true).

9. For merge targets main or dev, include an explicit pre-merge QA rerun signal in the final summary.

10. When review comments are complete, output a Claude `/merge-pr` handoff, or `/merge-release-pr` when the current PR is a release PR targeting main. The first non-empty payload line must be the slash command; never use `Agent: GitHub PR Merger` as the Claude invocation line.

Hard stops: if sufficient evidence for a binary verdict is unavailable, report a blocker instead of inventing a third state; do not auto-apply conflict items, do not commit to protected branches, do not reply anywhere except the original thread.

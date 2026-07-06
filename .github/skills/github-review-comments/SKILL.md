---
name: github-review-comments
description: "Inspect GitHub PR review comments and review threads, analyze current code evidence, assign each comment a binary accept or reject verdict, draft or post English replies to reviewers, summarize the fixable comments for the user, and apply the agreed code changes. Use when the user asks to process code review comments, address PR feedback, answer reviewers, challenge a review thread, or fix comments left on an open GitHub pull request."
argument-hint: "PR number or URL"
user-invocable: true
---

# GitHub Review Comments

## Summary

Handle PR review comments as a controlled workflow: read and report PR CI status, collect review threads, run a cycle check, agree the queue with the user, apply accepted fixes with matching test changes, verify them with tests/builds, commit and push fixes, then reply only in the original GitHub threads without waiting for green CI unless the user explicitly asks.

## When to use

- Process PR review feedback end-to-end.
- Address unresolved review threads with code and replies.
- Prepare an evidence-based acceptance/rejection queue for user decisions.

## Expected input

Provide when available:

- PR number or URL
- preferred language for user-facing summary
- constraints on which threads to process first

## Blocking gates

- Required PR CI/check status must be fetched and reported before collecting review comments, but green CI is not a gate for collecting comments, posting thread replies, or resolving addressed threads.
- Fix failing CI first only when the user requested CI repair, the review comment is about CI, or the failure directly prevents validating the accepted review fix.
- Cycle check must run before any code edit to prevent repeat review loops or rollback of accepted decisions.
- The review queue must be shown to the user as a Markdown table before comment-fix edits; every item must have an evidence-based binary `accept` or `reject` verdict, and `conflict` items require explicit user direction before an accepted fix is applied.
- After presenting the queue, require an explicit post-queue user confirmation before any code edits (for example: `APPROVE QUEUE` or `Proceed with accepted items`). Initial task wording like "process comments" or handoff prompts do not count as this confirmation.
- Every accepted code fix must include an explicit matching test action: add a test, update an existing test, or remove an obsolete/invalid test. Reject code-change suggestions that cannot justify a meaningful matching test action.
- Accepted fixes and their test changes must be verified with relevant local tests/builds before commit or push.
- When code or test files changed, commit only the relevant fix files and push the branch before posting any GitHub thread reply that claims the fix is available.
- After pushing review-comment fixes, required PR checks must be re-read and reported. Pending or failing post-push checks do not block review-thread replies or resolution unless the user explicitly required green CI or the failure proves the fix is invalid.
- Existing review feedback must be answered only inside its original review thread/comment. If the original thread/comment id, in-thread reply capability, or thread resolution capability is missing, mismatched, or unavailable, stop and report the blocker instead of degrading to any other comment path.

## Workflow

1. Read repository-local guidance first.
   Check `.github/skills/shared/operational-rules.md`, `AGENTS.md`, `README.md`, and the local `code-review` skill when code changes may be required.
2. Resolve the target PR.
   Prefer detecting the open PR for the current branch with `gh` when available.
   If automatic lookup is unavailable, use the PR number or URL provided by the user.
3. Fetch and report required checks.
   Use `pullRequestStatusChecks` to retrieve required CI/build/test/approval state before reading review comments. Report current check state to the user, then continue to review feedback without waiting for green CI. If a failing check is the requested work item, a review comment topic, or a blocker for local validation, inspect logs, apply the minimal fix, run matching local validation, and re-fetch PR checks.
4. Read the active PR with refresh logic.
   Read cached active PR state first, check `lastUpdatedAt`, and refresh if the timestamp is less than 3 minutes old to avoid stale thread state.
5. Fetch review feedback.
    Collect review threads and inline review comments for existing review feedback. Read top-level PR conversation comments only as context and never as a reply target for review-thread handling. Include unresolved review items (`isResolved = false`) and keep `id`, `file`, `canResolve`, reviewer `author.login`, reviewer `author.type`, and comments. Group related review items by file.
6. Run strict cycle check before any code edit.
   Compare new comments with previously addressed threads and recent fix commits. Build a decision ledger from historical accepted/rejected decisions. Tag each item as `new`, `repeat`, `conflict`, or `superseded`, and explicitly call out `new issue`, `follow-up of previous fix`, or `possible regression/rollback`.
7. Normalize feedback into a table review queue.
   Inspect the relevant code, tests, contracts, and history. Each item must have status `accept` or `reject`, plus a cycle tag `new`, `repeat`, `conflict`, or `superseded`. For bot-authored rejected comments, also record the exact canonical reply mention.
   For every item derive an **Assessment**: the agent's objective technical opinion on the comment, separate from the summary. The assessment must:
   - State whether the agent agrees or disagrees, with a one-line reason grounded in current code evidence.
   - For `accept` items: briefly confirm what makes the reviewer correct.
   - For `reject` items: briefly explain why the comment is wrong, unnecessary, or risky.
   - Never be empty or a copy of the Summary.
8. Present the queue in the language of the user's current request unless the user explicitly requests another language.
   Use a Markdown table with stable columns: `#`, `File/line`, `Reviewer`, `Status`, `Cycle`, `Summary`, `Assessment`, `Action`, `Test action`, and `Reply/mention`. Include a dedicated cycle-check section. Show which comments are accepted for a current-PR fix and which are rejected with a current-code rationale. Do not edit code before this queue is approved.
9. Wait for the user's queue approval.
    Use `vscode/askQuestions` to request queue approval after the table is shown. Require a separate explicit confirmation message. Do not treat earlier instructions, handoff prompts, or generic "process all" requests as post-queue approval. Never auto-apply an accepted `conflict` item; surface the cycle risk and require explicit queue approval.
10. Apply accepted fixes.
    Keep changes minimal and targeted. For every accepted code fix, add, update, or remove the matching test in the same fix batch and record that test action against the thread. Reject unclear, contradictory, speculative, optional, and future-refactor suggestions when current code evidence does not justify changing the current PR. If required evidence cannot be accessed, stop with a workflow blocker instead of assigning a third state.
11. Verify coverage after edits.
    Map each originally unresolved thread to either a code fix plus its test action, or an explicit no-change rationale. Confirm no unrelated modifications were introduced.
12. Verify build and tests.
    Run the relevant local tests and builds for the touched code and test scope (for example, `./gradlew testDebugUnitTest`, `dotnet test`, or equivalent). Do not commit or push if relevant verification fails.
13. Commit accepted fixes when files changed.
    Commit only relevant code and test changes for the approved review-comment batch. Use a concise past-tense commit message tied to the accepted review fixes. Do not include unrelated files, unapproved edits, or handoff/prompt artifacts.
14. Push the review-comment fix commit when files changed.
    Push the current branch after the commit. Use `git push -u origin <branch>` when upstream is missing. If push fails, stop and report the exact blocker. Do not post any thread reply that says the fix was applied until the push succeeds.
15. Re-check required PR checks after push.
    Fetch required PR status again with `pullRequestStatusChecks` and include the current state in replies or the final summary. Do not wait for green CI before posting review-thread replies or resolving addressed threads. If checks fail in a way that proves the pushed fix is not valid, stop and report the blocker instead of claiming completion.
16. Reply to review threads.
    Reply in English to every unresolved or newly outdated item only in its original thread/comment. If the original thread/comment id is missing, the reply target does not map back to the same thread, or the in-thread reply tool is unavailable/fails, stop and report the blocker instead of posting anywhere else.
17. Resolve addressed conversations.
    Resolve addressed `accept` and `reject` threads when `canResolve = true`. Resolution is mandatory for both verdicts — a `reject` thread that received a reply must be resolved just as an `accept` thread must. After posting every reply, immediately attempt to resolve that thread before moving to the next one.

    **Resolution requires the GraphQL thread node ID** (`PRRT_...`), not the numeric comment ID. GitHub has no REST endpoint for thread resolution — only GraphQL works.

    Collect the `PRRT_...` node IDs for threads addressed this round, then resolve them in one call using the dedicated script:
    ```powershell
    .\.github\scripts\resolve-pr-threads.ps1 -Repo "<owner/repo>" -ThreadIds @("PRRT_...", "PRRT_...")
    ```
    Check `.errors` in the output; log any failures but do not block posting replies over them.

    **Preferred resolution order — attempt in sequence until one confirms `isResolved=true`:**

    1. `github.vscode-pull-request-github/resolveReviewThread` — if the tool exists and returns a success response.
    2. If step 1 is unavailable, errors, or does not confirm resolution: immediately fall back to `resolve-pr-threads.ps1` (do not skip, do not assume success from the VSCode tool without confirmation).
    3. If the script also fails: stop and report the exact error with the thread node ID — do not continue to the next thread.

    After each resolution attempt, verify `isResolved=true` in the response before proceeding. A silent tool call without a confirmed `isResolved=true` result is NOT a successful resolution.
18. Report the outcome.
    Re-read thread state, then include applied changes, posted in-thread replies, resolved/unresolved threads, verification notes, required check status, build/test status, residual risks, and cycle-check delta since the previous round.

## Output format

1. Review queue as a Markdown table with binary status, cycle tags, evidence-based **assessment**, action, test action, and reply/mention
2. Applied fixes summary by thread
3. Test changes summary by thread (`added`, `updated`, or `removed`)
4. Commit and push status for code/test fixes
5. Posted replies summary (`accept`/`reject`)
6. Resolved/unresolved thread status
7. Build/test and required checks status
8. Pre-merge QA rerun status signal for merge targets `main`/`dev` (`needed` or `confirmed`)
9. Residual risks and follow-up items

## Constraints or rules

### Decision Rules

- Do not blindly accept every reviewer comment.
- Evaluate correctness, safety, architecture, performance, and behavioral impact before agreeing.
- Explicitly check whether the code under review reintroduced a defect that was fixed earlier in the PR.
- Use `accept` when the comment is correct and should be fixed.
- Use `reject` when the suggested change is incorrect, risky, unnecessary, or would cause churn.
- Reject comments that are ambiguous, unsubstantiated, optional, speculative, or propose refactoring that is not required by the current PR.
- Never use `discuss`, `neutral`, or deferred/future-refactor outcomes. If the code, tests, contracts, or history needed for an objective decision cannot be accessed, stop and report a workflow blocker.
- Always compare new review feedback with previously resolved review threads in the same PR.
- Detect circular churn, partial rollback, and adjacent contract drift before editing.
- If a proposed fix would revert a previously accepted decision, stop and surface it explicitly as a cycle risk.
- Before declaring a review-fix batch ready for commit or push, confirm each accepted code fix has a matching test addition/update/removal, then run the broadest realistic local checks for the touched scope.
- Do not skip test changes for accepted code fixes because the change is small. Reject code-change suggestions when a meaningful matching test action cannot be justified.
- Do not reply that a code fix is available until the relevant commit has been pushed. Re-read required PR checks after push and report their current state, but do not wait for green CI before replying unless the user explicitly required it or the CI failure invalidates the fix.
- For target merges into `main` or `dev`, include an explicit handoff signal that pre-merge QA rerun confirmation is still needed unless the user already confirmed in chat that rerun QA passed after the latest review-comment/code changes.
- Never perform agent-originated direct commit/push to protected branches per `.github/skills/shared/operational-rules.md`.
- Do not create or commit prompt-transfer files per `.github/skills/shared/operational-rules.md` unless explicitly requested.
- Treat any attempt to answer an existing review item with a top-level PR comment, a newly created review comment, or a pending-review comment as a workflow failure, not as an acceptable fallback.

### Token Efficiency Rules

- Optimize context size without weakening correctness, safety gates, CI status reporting, test coverage, thread-only replies, or required workflow order.
- Use compact tables, thread IDs, file paths, line numbers, short summaries, and links instead of pasting long review comments, CI logs, diffs, or source files.
- Load `references/review-comment-style.md` only when preparing the review queue or GitHub replies. Load the local `code-review` skill only when deeper technical validation is needed for a specific item.
- When using AI subagents or handoffs, pass only the bounded question, relevant thread IDs, affected files, failing check names, validation evidence, and constraints. Do not pass the whole PR conversation or broad repository context unless required to preserve quality.
- Token savings never justify skipping required CI inspection/reporting, cycle checks, user queue approval, matching test actions, local validation, commit/push, in-thread replies, or resolution verification.
- Follow Long-Running Operation Rules from `.github/skills/shared/operational-rules.md`.
- Do not leave local validation, pushes, or GitHub thread actions in an unresolved "waiting" state. Poll tracked operations until success, failure, timeout, cancellation, or blocker. Refresh post-push CI/check status once or for a bounded short interval, then reply with the current status instead of waiting indefinitely for green CI.

### GitHub Interaction Rules

- When posting back to GitHub, write in English with a calm, technical, specific, non-defensive tone.
- Reply in-thread using the original review comment id. This is mandatory for review feedback.
- Reply to all unresolved and newly outdated threads; never replace thread replies with a batch summary or top-level PR comment.
- For `accept`, post only a concise completion note without any tag.
- For `reject`, start the reply with the matched canonical review bot tag when the original reviewer is a bot.
- Canonical review bot tags are exactly `@copilot`, `@codex`, and `@gemini-code-assist`.
- Canonical bot mapping (by GitHub login): `github-copilot[bot]` or `copilot-pull-request-reviewer` -> `@copilot`; `codex` or `chatgpt-codex-connector[bot]` -> `@codex`; `gemini-code-assist[bot]` or `gemini-code-assist` -> `@gemini-code-assist`.
- Never copy or derive non-canonical tags from GitHub logins or display names. For Copilot comments, use `@copilot`, not `@copilot-pull-request-reviewer`.
- If a `reject` reply is for a known bot reviewer and no canonical tag can be selected, stop and report the missing mapping instead of posting an untagged reply.
- Do not use top-level PR comments, new review comments, or pending-review comments for existing review feedback.
- Do not use `github/add_comment_to_pending_review` for existing review feedback.
- Do not use top-level PR conversation comments as a fallback reply destination for review threads, even when they mention the same file or issue.
- Do not claim that a comment was resolved unless the code change or rejection rationale actually addressed it and any accepted code/test fix has already been pushed.
- Resolve `accept` and `reject` threads after the fix or reply is posted when the thread is resolvable, then verify by re-reading thread state.

### Tooling Rules

- Prefer GitHub PR and issue tools for reading/replying:
  - `github.vscode-pull-request-github/activePullRequest` for detecting active PR
  - `github.vscode-pull-request-github/openPullRequest` for viewing current PR
  - `github.vscode-pull-request-github/pullRequestStatusChecks` for required checks
  - `github/add_reply_to_pull_request_comment` for replying to existing review comments/threads
- **For posting new top-level PR comments**, prefer `gh pr comment` over MCP tools — `gh` output is discarded after the call whereas MCP results stay in context for the entire session. Example: `gh pr comment <pr_number> --repo "<owner/repo>" --body "..."`.
- For resolving review threads, prefer `.\.github\scripts\resolve-pr-threads.ps1` (batches all PRRT_ IDs in one call) over individual GraphQL mutations.
- Use `gh` as fallback for PR discovery when tools are unavailable or unauthenticated.
- Use `gh` or the GitHub UI/API as fallback for CI log inspection when `pullRequestStatusChecks` shows a failing required check but does not include enough failure detail.
- If automatic discovery fails, ask user for PR number or link instead of guessing.

### Deterministic Tool Order

1. PR detect/read: `github.vscode-pull-request-github/activePullRequest` -> `github.vscode-pull-request-github/openPullRequest` -> `github/list_pull_requests`
2. Checks: `github.vscode-pull-request-github/pullRequestStatusChecks` -> `github/pull_request_read`
3. Commit/push after local validation when files changed: `git status --short` -> `git add <relevant files>` -> `git commit` -> `git push -u origin <branch>` when upstream is missing, otherwise `git push`
    Guard: if the current branch is protected (`main`, `dev`, `master`, `develop`), stop and report `BLOCKED`; do not commit/push from that branch.
4. Post-push checks: `github.vscode-pull-request-github/pullRequestStatusChecks` -> `github/pull_request_read`
5. Existing-thread reply: `github/add_reply_to_pull_request_comment` -> `github/pull_request_review_write` only if it can reply to the same existing thread/comment
6. Thread resolve: `github.vscode-pull-request-github/resolveReviewThread` → if unavailable/fails: `gh api graphql` mutation `resolveReviewThread(input:{threadId:"PRRT_..."})`. Thread node IDs must be fetched first via GraphQL `reviewThreads` query — numeric comment IDs do not work for resolution. REST API has no resolve endpoint.
7. Post-action verification: re-read PR thread state before reporting success

## References and related skills

- Load [references/review-comment-style.md](./references/review-comment-style.md) only when preparing the user queue or GitHub replies.
- Use the local `code-review` skill when review feedback requires deeper technical validation.

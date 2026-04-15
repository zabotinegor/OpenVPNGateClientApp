---
name: github-review-comments
description: "Inspect PR review comments/threads, classify accept/discuss/reject with cycle tags, align with user decisions, apply accepted fixes, resolve addressable threads, and draft/post English in-thread replies."
argument-hint: "PR number or URL"
user-invocable: true
---

# GitHub Review Comments

Handle review comments as a controlled workflow: collect -> classify -> confirm with user -> apply accepted fixes -> reply in-thread.

## Workflow

1. Read `AGENTS.md` and related local review guidance.
2. Resolve target PR (active PR or user-provided PR URL/number).
3. **Fetch and check required checks:**
   - Use `pullRequestStatusChecks` to retrieve all required checks (CI workflows, build status, test status, approval requirements).
   - Report current check state to user.
   - If any required check is failing, alert user and pause processing until checks are resolved or user decides to proceed anyway.
4. Read the active PR with refresh logic:
   - Read cached active PR state first.
   - Check `lastUpdatedAt`.
   - If the timestamp is less than 3 minutes old, refresh and read again to avoid stale thread state.
5. Collect feedback needing action:
   - `reviewThreads`: include unresolved items (`isResolved = false`) and keep `id`, `file`, `canResolve`, and comments.
   - `timelineComments`: include review-level feedback such as change requests or comment reviews.
   - Group related items by file for efficient handling.
6. Run strict cycle check before any code edit:
   - Fetch all threads, not only unresolved: resolved + unresolved + outdated.
   - Build a decision ledger from historical handled threads (accepted/rejected/discussed intent).
   - Compare new comments against the ledger and recent commits to detect repeats and reversals.
   - Mark each comment with a cycle tag:
     - `new` (not seen before)
     - `repeat` (already requested and handled)
     - `conflict` (asks to undo or contradict previously accepted decision)
     - `superseded` (old concern replaced by newer context)
7. Normalize into a numbered queue with status and cycle tag:
   - status: `accept` | `discuss` | `reject`
   - cycle: `new` | `repeat` | `conflict` | `superseded`
8. Present queue to user in Russian, with a dedicated "cycle check" section.
9. Wait for user decisions on disputed items (`discuss`, `reject`, and all `conflict` items).
10. Plan and implement accepted fixes with minimal scope:
   - Read the relevant file area before editing.
   - Apply only requested changes in scope.
   - If a comment is unclear or contradictory, stop and keep it in discussion instead of guessing.
11. Verify coverage after edits:
   - Each originally unresolved thread is mapped to either a code fix or an explicit no-change rationale.
   - No unrelated modifications were introduced.
12. Resolve review threads when addressed:
   - Resolve only threads with `canResolve = true` and `isResolved = false`.
   - Skip already resolved threads and non-resolvable threads.
13. Post English in-thread replies to **ALL threads** with appropriate action:
   - **`accept` threads**: Post concise completion note with code change summary. **Resolve thread immediately after replying.**
   - **`reject` threads**: Post technical explanation explaining why change is not taken. **Resolve thread immediately after replying.** Tag the reviewer.
   - **`discuss` threads**: Post response with clarifying questions or tradeoff discussion. Tag the reviewer. **Do not resolve thread** — leave for user or reviewer continuation.
14. Verify build and tests:
   - Run all relevant unit tests (`./gradlew testDebugUnitTest` or equivalent).
   - Run relevant module builds (`./gradlew assembleDebug` or equivalent).
   - Confirm all tests pass and no new compilation errors are introduced.
   - Report build/test status in final summary.
15. **Check required checks before push:**
   - Fetch PR status again using `pullRequestStatusChecks`.
   - Verify all required checks are passing (no failing workflows, test failures, or blocking approvals).
   - If any checks are failing, report them to user and do not push.
16. Report applied changes, posted replies, unresolved threads, verification notes, required check status, build/test results, and cycle-check delta since the previous round.

Use [references/review-comment-style.md](./references/review-comment-style.md).

## Rules

- **Must check required checks at start and before push** — verify GitHub required checks (CI/CD, tests, approvals) before processing and after fixes. Do not proceed if critical checks are failing without user approval.
- **Must reply to ALL threads** — every unresolved + newly outdated thread gets a response in English.
- **Always verify builds and tests before committing or pushing** — no commit without passing unit tests and module builds.
- Do not blindly accept all comments.
- Do not skip user confirmation for disputed items.
- Do not claim comment resolution without actual code/discussion resolution.
- Prefer in-thread replies over new top-level comments.
- Never apply a `conflict` item automatically; default it to `discuss` until user confirms direction.
- If a proposed fix would revert a previously accepted decision, stop and surface it explicitly as a cycle risk.
- Do not re-open closed concerns unless new evidence in code or tests appears.
- Always show delta since previous review round; avoid re-processing already settled comments.
- Not every comment requires code changes; no-change decisions must be explicit and justified.

## Known Review Bots

Tag **only these bots** when replying to review threads:
- `@codex`
- `@copilot`
- `@gemini-code-assist`

Use bot tag when responding to `discuss` threads or seeking clarification from these specific reviewers. For human reviewers, use standard GitHub handle mention.

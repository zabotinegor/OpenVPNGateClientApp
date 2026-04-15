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
3. Read the active PR with refresh logic:
   - Read cached active PR state first.
   - Check `lastUpdatedAt`.
   - If the timestamp is less than 3 minutes old, refresh and read again to avoid stale thread state.
4. Collect feedback needing action:
   - `reviewThreads`: include unresolved items (`isResolved = false`) and keep `id`, `file`, `canResolve`, and comments.
   - `timelineComments`: include review-level feedback such as change requests or comment reviews.
   - Group related items by file for efficient handling.
5. Run strict cycle check before any code edit:
   - Fetch all threads, not only unresolved: resolved + unresolved + outdated.
   - Build a decision ledger from historical handled threads (accepted/rejected/discussed intent).
   - Compare new comments against the ledger and recent commits to detect repeats and reversals.
   - Mark each comment with a cycle tag:
     - `new` (not seen before)
     - `repeat` (already requested and handled)
     - `conflict` (asks to undo or contradict previously accepted decision)
     - `superseded` (old concern replaced by newer context)
6. Normalize into a numbered queue with status and cycle tag:
   - status: `accept` | `discuss` | `reject`
   - cycle: `new` | `repeat` | `conflict` | `superseded`
7. Present queue to user in Russian, with a dedicated "cycle check" section.
8. Wait for user decisions on disputed items (`discuss`, `reject`, and all `conflict` items).
9. Plan and implement accepted fixes with minimal scope:
   - Read the relevant file area before editing.
   - Apply only requested changes in scope.
   - If a comment is unclear or contradictory, stop and keep it in discussion instead of guessing.
10. Verify coverage after edits:
   - Each originally unresolved thread is mapped to either a code fix or an explicit no-change rationale.
   - No unrelated modifications were introduced.
11. Resolve review threads when addressed:
   - Resolve only threads with `canResolve = true` and `isResolved = false`.
   - Skip already resolved threads and non-resolvable threads.
12. Post English in-thread replies to **ALL threads** with appropriate action:
   - **`accept` threads**: Post concise completion note with code change summary. **Resolve thread immediately after replying.**
   - **`reject` threads**: Post technical explanation explaining why change is not taken. **Resolve thread immediately after replying.** Tag the reviewer.
   - **`discuss` threads**: Post response with clarifying questions or tradeoff discussion. Tag the reviewer. **Do not resolve thread** — leave for user or reviewer continuation.
13. Report applied changes, posted replies, unresolved threads, verification notes, and cycle-check delta since the previous round.

Use [references/review-comment-style.md](./references/review-comment-style.md).

## Rules

- **Must reply to ALL threads** — every unresolved + newly outdated thread gets a response in English.
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

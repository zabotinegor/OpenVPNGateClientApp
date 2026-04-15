---
name: github-review-comments
description: "Inspect PR review comments/threads, classify accept/discuss/reject, align with user decisions, apply accepted fixes, and draft/post English in-thread replies."
argument-hint: "PR number or URL"
user-invocable: true
---

# GitHub Review Comments

Handle review comments as a controlled workflow: collect -> classify -> confirm with user -> apply accepted fixes -> reply in-thread.

## Workflow

1. Read `AGENTS.md` and related local review guidance.
2. Resolve PR number (active PR or user-provided PR URL/number).
3. Fetch inline threads and top-level PR comments.
4. Run strict cycle check before any code edit:
   - Fetch all threads, not only unresolved: resolved + unresolved + outdated.
   - Build a decision ledger from historical handled threads (accepted/rejected/discussed intent).
   - Compare new comments against the ledger and recent commits to detect repeats and reversals.
   - Mark each comment with a cycle tag:
     - `new` (not seen before)
     - `repeat` (already requested and handled)
     - `conflict` (asks to undo or contradict previously accepted decision)
     - `superseded` (old concern replaced by newer context)
5. Normalize into numbered queue with status:
   - `accept`
   - `discuss`
   - `reject`
   and include cycle tag for each item.
6. Present queue to user in Russian, with a dedicated "cycle check" section.
7. Wait for user decision on disputed items.
8. Apply accepted fixes minimally and validate changed scope.
9. Post English in-thread replies:
   - `accept`: concise completion note, no reviewer tag
   - `discuss`/`reject`: technical explanation, reviewer tag allowed
10. Report applied changes, posted replies, unresolved threads, verification notes, and cycle-check outcomes.

Use [references/review-comment-style.md](./references/review-comment-style.md).

## Rules

- Do not blindly accept all comments.
- Do not skip user confirmation for disputed items.
- Do not claim comment resolution without actual code/discussion resolution.
- Prefer in-thread replies over new top-level comments.
- Never apply a `conflict` item automatically; default it to `discuss` until user confirms direction.
- If a proposed fix would revert a previously accepted decision, stop and surface it explicitly as a cycle risk.
- Do not re-open closed concerns unless new evidence in code or tests appears.
- Always show delta since previous review round; avoid re-processing already settled comments.

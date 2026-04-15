---
name: code-review
description: "Perform a strict recursive code review and safe remediation workflow for Android/Kotlin code until no critical issues and no obvious safe major fixes remain in scope. Use when asked to review a module, inspect PR diff, improve quality, or review main..HEAD. Findings-first output and at least two iterations for non-trivial scope are required."
argument-hint: "scope or files to review"
user-invocable: true
---

# Code Review

Review as an implementation task, not one-pass commentary: inspect, find issues, fix safe ones, re-review, and stop only at a justified boundary.

## Strict Execution Contract

1. Read `AGENTS.md` before applying repository-specific review judgments.
2. For non-trivial scope, perform at least 2 explicit review iterations.
3. Report findings first, ordered by severity, with concrete file references.
4. Fix safe `critical` and `major` issues directly.
5. Do not claim checks or validation that were not actually run.
6. If no findings exist, state that explicitly and mention residual risks/testing gaps.

## Workflow

1. Read repository-local guidance first.
2. Define review scope.
3. Inspect target code and adjacent dependency/contract surfaces.
4. Identify findings and classify by severity.
5. Apply safe fixes for critical/major findings.
6. Self-review changed code for regressions.
7. Verify with practical checks (tests/build/lint).
8. Repeat until no safe critical/major findings remain.

## Review Focus (Android/Kotlin)

- UI flow correctness in `src/core`, and launcher-specific logic boundaries (`src/mobile`, `src/tv`)
- Coroutine lifecycle, cancellation, dispatchers, race conditions, error propagation
- Network/serialization contracts and backward compatibility
- Caching correctness, invalidation keys, stale data risks
- Logging/privacy rules from `src/docs/logging-policy.md`
- Build and CI impact in Gradle and workflows
- Tests for changed behavior and edge cases

Use [references/review-checklist.md](./references/review-checklist.md).

## Severity Rules

- `critical`: security/privacy exposure, major runtime breakage, severe incorrect behavior.
- `major`: significant correctness/reliability/performance/architecture issue safe to fix in scope.
- `minor`: useful improvement with lower impact.
- `nit`: negligible style or wording item.

## Output Contract

Findings-first response:
1. `Iteration #`
2. `Scope reviewed`
3. `Findings`
4. `Planned fixes`
5. `Changes applied`
6. `Verification notes`
7. `Decision`

Final:
1. `Final Summary`
2. `Most important issues fixed`
3. `Remaining risks or follow-ups`
4. `Compliance with AGENTS.md`

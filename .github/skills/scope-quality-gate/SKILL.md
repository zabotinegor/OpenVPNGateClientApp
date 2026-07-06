---
name: scope-quality-gate
description: "Scoped quality analysis workflow for implementation risks, test coverage gaps, and safe remediation planning within a user-approved scope, using cyclical analysis (2 to 5 iterations for non-trivial scope)."
argument-hint: "scope: full repo, module path, feature slice, or branch diff (for example: main..HEAD)"
user-invocable: true
---

# Scope Quality Gate

Run a strict quality-gate analysis in a user-confirmed scope, with findings-first reporting and explicit validation evidence.

## When to use

- Quality audit for a module, feature, or branch diff.
- Test coverage gap analysis before release.
- Risk-focused review when correctness and regression safety are priority.

## Expected input

Provide these inputs before execution when available:

- scope: full repository, module path, feature slice, changed files set, or diff range
- successCriteria: what must be true to pass the gate
- constraints: optional time, tooling, or environment limits
- focusAreas: optional priorities such as security, performance, contracts, tests

## Blocking gates

Mandatory confirmations before deep analysis:

- Confirm exact scope boundaries.
- Confirm whether safe direct fixes are allowed in this run.
- Confirm expected validation depth (narrow checks only or broader suite).
- For non-trivial scope, confirm iterative gate execution up to 5 passes or a `BLOCKED` outcome if safe high-impact gaps remain.

Do not proceed with full analysis until scope is explicitly confirmed. When invoked by an orchestrator agent, treat explicit delegated inputs for `scope`, `successCriteria`, `safeFixesAllowed`, and `validationDepth` as the required confirmations; do not ask the user again unless one of those inputs is missing or contradictory.

## SDLC status gate

Follow the common SDLC status gate from [../shared/sdlc-status-gate.md](../shared/sdlc-status-gate.md).

Skill-specific requirements:
- Scope Quality Gate requires `steps.review.status` to be `passed`.
- Before final output, run `.github/scripts/update-sdlc-status.ps1 -FlowId <flowId> -Branch <branch> -Step qualityGate -Status passed|failed|blocked -StoryId <storyId> -StoryPath <storyPath> -Commit <sha> -Evidence <gate evidence>`.

## Context Inheritance (Lazy Loading)

When invoked as a subagent by an orchestrator (developer-flow-orchestrator, github-create-pr, bug-flow), the orchestrator has already read shared files and spawned you with `context="full"`. Check your context first:

- If repository rules/guidance content is already visible, **do NOT re-read the source file**.
- If status/config files are already visible, **do NOT re-read them**.

Only read files that are NOT already in your context. This saves tokens per invocation.

When invoked standalone (not by an orchestrator), read all files as normal.

## Workflow

1. Read `.github/skills/shared/operational-rules.md` and `AGENTS.md` and relevant local module docs.
   **Skip this step if the guidance is already in your context from the parent orchestrator.**
2. Resolve SDLC status.
   Read `.sdlc/status.json`, select the flow, and enforce `steps.review.status=passed` before Developer Flow Handoff quality gates.
3. Confirm scope and acceptance criteria.
4. Build a short review and validation plan.
5. Inspect code and dependencies only inside scope boundaries.
6. Evaluate test coverage and missing edge-case protection across unit, component/integration, UI, and E2E layers where relevant.
7. Run narrow validations first, then broaden when needed.
8. Capture concrete evidence for each finding.
9. If safe direct fixes are approved, apply minimal focused changes.
10. For non-trivial scope, continue gate iterations until pass/fail conditions are clear, with a maximum of 5 iterations.
11. If safe direct fixes were applied, commit and push only relevant validated files; if no files changed, explicitly record that push was not needed.
   Never perform agent-originated direct commit/push to protected branches (`main`, `dev`, `master`, `develop`); no approval, auto-approve flow, handoff instruction, merge workflow step, scripted shortcut, or other automation may bypass this rule.
12. Run `.github/scripts/update-sdlc-status.ps1` with `steps.qualityGate.status=passed|failed|blocked`, commit, and compact evidence.
13. Produce findings-first report with residual risks.

## Delegated Execution Rules

- Do not run concurrently with Code Review for the same scope.
- If Code Review is also required, run after Code Review has completed and after any Code Review fixes have been validated.
- If required delegated inputs are missing, return `BLOCKED` with the missing input list instead of continuing broad analysis.
- Keep the gate finite: execute at least 2 iterations for non-trivial scopes and no more than 5 iterations per run.
- If unresolved safe high-impact findings remain after iteration 5, return `BLOCKED` with actionable remediation handoff scope.

## Evidence File Output Contract

When invoked as a subagent by an orchestrator (developer-flow-orchestrator or bug-flow), Scope Quality Gate MUST follow this evidence-file output contract:

1. Write full detailed findings to an evidence file: `docs/qa-evidence/<flow-id>-gate-<iteration>.md`
2. Begin every response with a GATE_RESULT block:

```
GATE: PASS|FAIL|BLOCKED
STEP: qualityGate
ITERATION: <n>
BLOCKING_COUNT: <n>
EVIDENCE: docs/qa-evidence/<flow-id>-gate-<iteration>.md
COMMIT: <sha or n/a>
SUMMARY: <1-2 sentences>
```

3. The evidence file contains the full gate report (scope, findings by severity, evidence, validation commands, safe fixes, test coverage by layer, residual risks).
4. The orchestrator reads only the GATE_RESULT block for flow control.
5. When invoked standalone (not by an orchestrator), use the legacy output format below.

## Legacy Output Format (standalone invocation only)

Report in this order:

1. Scope and assumptions
2. Findings ordered by severity
3. Evidence for each finding
4. Validation commands and outcomes
5. Safe fixes applied (if any)
6. Test coverage adequacy by layer (unit/component-integration/UI/E2E)
7. SDLC status update
8. Residual risks and follow-up actions

If no findings exist, state that explicitly and still list residual risks or limitations.

SDLC-core minimum report fields per `.github/skills/shared/operational-rules.md` are mandatory in this output.

## Constraints and rules

- Do not analyze outside approved scope.
- Do not claim checks that were not actually run.
- Do not perform destructive git actions.
- If this run applies safe fixes, commit and push only relevant validated files; if this run is analysis-only, do not commit or push.
- Direct commit/push to protected branches (`main`, `dev`, `master`, `develop`) is forbidden per `.github/skills/shared/operational-rules.md`.
- Follow Long-Running Operation Rules from `.github/skills/shared/operational-rules.md`.
- Do not leave required checks in an unresolved "waiting" state. Poll until success, failure, timeout, cancellation, or blocker; report progress during long waits; do not final-answer while required gate validation is still running.
- Prefer deterministic evidence over speculative conclusions.
- Escalate ambiguous/high-risk remediation for user approval via `vscode/askQuestions`.

## Related skills

- code-review
- code-implementator
- e2e-manual-testing

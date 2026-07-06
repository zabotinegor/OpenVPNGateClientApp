---
name: docs-maintenance
description: "Hybrid documentation maintenance workflow for AI-agent repositories: detect markdown drift from code and governance sources, report findings first, and apply approved updates or create missing docs to keep documentation consistently up to date."
argument-hint: "scope and expected documentation depth"
user-invocable: true
---

# Docs Maintenance

## Summary

Keep repository markdown documentation and AI-agent guidance aligned with current behavior. This includes maintaining existing docs and creating new docs when a story introduces new features, key behavior changes, setup/runbook changes, APIs, user-facing flows, test artifacts, or workflow guidance. This skill is hybrid: audit first, then apply updates only after explicit approval.

## When to use

- Keep README and AGENTS documentation synchronized with current repository behavior.
- Document core features, modules, and execution specifics for AI agents.
- Create missing documentation for newly introduced or materially changed features, modules, runbooks, APIs, user-facing behavior, test artifacts, and SDLC/agent workflows.
- Perform routine markdown drift checks after meaningful code or workflow changes.
- Reconcile local documentation overlays (`README.local.md`, `AGENTS.local.md`) with global docs.

## Expected input

Provide when available:

- target scope (`all`, folder, or changed files)
- whether to run audit only or audit+apply
- preferred level of detail (brief, standard, detailed)
- known source-of-truth files and constraints
- story path/content, changed behavior, changed files, review/gate/QA evidence, and docs impact assumptions when running in an SDLC flow

## Blocking gates (mandatory clarifications)

- Confirm mode: `audit` only or `audit+apply`.
- Confirm scope: full markdown sweep or limited scope.
- Confirm local overlay handling:
  - create `README.local.md` and `AGENTS.local.md` if missing
  - keep local overlays synchronized with global baseline
- Confirm whether to create new docs when no existing page cleanly owns the new feature, behavior, or runbook.
- Confirm whether unresolved ambiguities should be marked as TODO in docs.
- Confirm ownership strategy for new documentation when no existing page is a clear owner (create a new dedicated markdown file instead of overloading unrelated docs).

## SDLC status gate

Follow the common SDLC status gate from [../shared/sdlc-status-gate.md](../shared/sdlc-status-gate.md).

Skill-specific requirements:
- Docs maintenance requires `steps.manualQa.status` to be `passed` or `notNeeded`.
- `steps.manualQa.status=passed` is the QA sign-off for the current retest cycle.
- Historical defects with status `resolved|verified|closed` are non-blocking for docs.
- If `steps.manualQa.status=passed` but `defects[]` still has open statuses, stop and report `BLOCKED` with a request to refresh QA sign-off.
- Before final output, run `.github/scripts/update-sdlc-status.ps1 -FlowId <flowId> -Branch <branch> -Step docs -Status updated|notNeeded|blocked -StoryId <storyId> -StoryPath <storyPath> -Evidence <docs evidence>`.

## Workflow

1. Load governance and source-of-truth context.
   - Read `.github/skills/shared/operational-rules.md`, `AGENTS.md`, `README.md`, `.github/AGENTS-REGISTRY.md`, `.github/FRONTMATTER-SCHEMA.md`.
   - Read local overlays (`AGENTS.local.md`, `README.local.md`) if present.

2. Resolve SDLC status.
   - Read `.sdlc/status.json`, select the flow, and enforce `steps.manualQa.status=passed|notNeeded` for Developer Flow New docs handoffs.
   - Treat `manualQa=passed` as authoritative QA sign-off; do not block on resolved/verified/closed historical defects.
   - If open defects remain alongside `manualQa=passed`, report `BLOCKED` and request QA sign-off refresh for the same flow.

3. Build documentation inventory.
   - Enumerate `*.md` files in repository.
   - Group by purpose: governance, registry, agent, skill, references, user-facing docs, local overlays.

4. Run drift analysis.
   - Compare agent/skill frontmatter facts with registry and top-level docs.
   - Check whether feature/module descriptions reflect current structures and workflows.
   - Check whether the story introduced a new feature, key behavior change, setup/runbook change, API/user-facing behavior, test artifact, or workflow guidance that needs a new document instead of only edits to existing docs.
   - Check for missing required sections in `SKILL.md` files.
   - Check relative link validity and path consistency for touched docs.

5. Produce findings-first audit report.
   - Order findings by severity (`critical`, `major`, `minor`, `nit`).
   - Include impacted files, reason, and minimal proposed fix.

6. Wait for explicit approval before edits.
   - Use `vscode/askQuestions` to present the audit findings and ask the user which files to update. No markdown changes are applied before user approval.

7. Apply approved updates.
   - Update only approved files.
   - Create approved new markdown files when no existing document is the right owner for the new or changed behavior.
   - Prefer creating a new owner document over appending large unrelated sections into root docs.
   - Prefer smallest focused edits.
   - Keep language policy and repository conventions intact.

8. Commit and push validated docs changes (when edits exist).
   - If this run changed files, stage only relevant docs files, commit with a clear past-tense message, and run `git push -u origin <branch>` after validation succeeds.
   - Never perform agent-originated direct commit/push to protected branches (`main`, `dev`, `master`, `develop`); no approval, auto-approve flow, handoff instruction, merge workflow step, scripted shortcut, or other automation may bypass this rule.
   - If no files were changed, explicitly record `Commit and push: not needed (no approved edits)`.

9. Re-validate and publish final report.
   - Re-check links and consistency of changed docs.
   - Confirm synchronization between `AGENTS.md`, `README.md`, and `.github/AGENTS-REGISTRY.md` when relevant.
   - Run `.github/scripts/update-sdlc-status.ps1` with `steps.docs.status=updated|notNeeded|blocked`.
   - Report changed files, SDLC status update, unresolved items, and next actions.

## Evidence File Output Contract

When invoked as a subagent by an orchestrator (developer-flow-orchestrator or bug-flow), Docs Maintenance MUST follow this evidence-file output contract:

1. Write full detailed docs report to an evidence file: `docs/qa-evidence/<flow-id>-docs.md`
2. Begin every response with a GATE_RESULT block:

```
GATE: PASS|FAIL|BLOCKED
STEP: docs
ITERATION: 1
BLOCKING_COUNT: <n>
EVIDENCE: docs/qa-evidence/<flow-id>-docs.md
COMMIT: <sha or n/a>
SUMMARY: <1-2 sentences>
```

3. The evidence file contains the full docs report (scope, mode, findings, applied changes, validation notes, commit status, residual risks).
4. The orchestrator reads only the GATE_RESULT block for flow control.
5. When invoked standalone (not by an orchestrator), use the legacy output format below.

## Legacy Output Format (standalone invocation only)

Use this structure:

1. `Scope`
2. `Mode`
3. `Findings` (severity-ordered, with file references)
4. `Approval Gate`
5. `Applied Changes`
6. `Validation Notes`
7. `Commit and Push Status`
8. `SDLC Status Update`
9. `Residual Risks / Follow-ups`

SDLC-core minimum report fields per `.github/skills/shared/operational-rules.md` are mandatory in this output.

## Constraints or rules

- Findings-first output is mandatory.
- Approval gate before edits is mandatory.
- Keep changes markdown-focused unless user expands scope.
- Maintain old docs and create new docs when the story creates a documented surface that does not have an appropriate existing owner.
- If this run edited docs files, commit and push only relevant validated docs files; if audit-only or no edits, do not commit or push.
- Direct commit/push to protected branches (`main`, `dev`, `master`, `develop`) is forbidden per `.github/skills/shared/operational-rules.md`.
- Keep governance/process docs in English.
- Do not invent repository facts; if unknown, mark as assumption or open question.
- Do not break relative markdown links.

## References and related skills

- Checklist: [references/documentation-sync-checklist.md](./references/documentation-sync-checklist.md)
- Report template: [references/docs-report-template.md](./references/docs-report-template.md)
- Related skill for deep quality pass: [../scope-quality-gate/SKILL.md](../scope-quality-gate/SKILL.md)

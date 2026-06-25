# Protected Branches

## Definition

The following branches are protected and must NEVER receive direct agent-originated commit/push:

- `main`
- `dev`
- `master`
- `develop`

## Rules

1. Direct commit/push to protected branches is always forbidden
2. No bypass is allowed: approvals, auto-approve flows, handoff text, merge workflow steps, or automation cannot authorize direct commit/push
3. Before every commit/push, resolve the current branch and stop if target is protected
4. Direct remote protected-ref creation, update, force-update, deletion, and recreation are forbidden
5. The only allowed protected-branch write is an approved PR merge after all required gates pass
6. Every agent must create a non-protected working branch before first tracked-file edit

## Allowed Patterns

- Feature branches: `feature/<story-id-or-kebab-title>`
- Fix branches: `fix/<story-id-or-kebab-title>`
- PR merge to protected branch (after all gates pass)

## Protected Branches

The following branches are protected from agent-originated direct commit, push, branch creation, force-update, deletion, or any other ref mutation:

- `main`
- `dev`
- `master`
- `develop`

No bypass is permitted through approvals, auto-approve flows, handoff text, merge workflow steps, scripted shortcuts, or any other automation. The only allowed write to a protected branch is an approved PR merge after all required gates pass.

Before every commit, push, or branch/ref mutation, resolve the current branch and all destination refs. Stop with `BLOCKED` if any target or destination is a protected branch.

Every agent capable of tracked-file edits must resolve or create a suitable non-protected working branch before its first tracked-file edit. A planned later branch switch does not permit editing on a protected branch.

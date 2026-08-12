#!/bin/sh
# Bash-host entrypoint for the protected-branch guard.
#
# Both PreToolUse matchers, Bash and PowerShell, run the one guard
# implementation in protect-agent-git-command.ps1, under pwsh. That is right
# when pwsh is present, but on a Bash-only host without PowerShell 7 on PATH,
# a bare `pwsh -File ...` command fails before any guard logic runs. A
# PreToolUse hook that never launches is not the same as one that denies: the
# harness sees no blocking result and lets the git command through - the
# guard was silently doing nothing. A previous Python twin of this guard
# existed for exactly this host gap and was removed (see git history: "Judge
# a git command by the repo it targets, and stop a guard that never ran")
# because it was invoked as python3, which resolves to the Windows Store
# execution alias and fails the identical way - it "resolves" without ever
# really running. Restoring a second implementation kept in parity with the
# PowerShell one is its own maintenance hazard, per that same commit. Fail
# loud and closed instead: without pwsh, deny the command with a clear reason
# rather than silently letting it through.
#
# This entrypoint relies on the hook's declared "cwd": "." - it is always
# launched from the repository root, matching every other hook command here.
set -eu

if command -v pwsh >/dev/null 2>&1; then
    exec pwsh -NoProfile -NonInteractive -File .github/scripts/protect-agent-git-command.ps1
fi

# Without pwsh, the full guard (branch resolution, -C/--git-dir target
# parsing, force-push detection, etc.) cannot run here. Denying every Bash
# command in that case - not just Git mutations - blocks the exact thing
# this fallback exists to support: agents inspecting files and running tests
# on a Bash-only host. This guard only ever exists to gate `git`, so read
# the raw hook payload and fail closed solely when it looks like a `git`
# invocation; every other command passes through untouched.
payload="$(cat)"
if ! printf '%s' "$payload" | grep -Eq '(^|[^A-Za-z0-9_])git([^A-Za-z0-9_]|$)'; then
    printf '%s\n' '{}'
    exit 0
fi

printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"protect-agent-git-command: pwsh (PowerShell 7+) was not found on PATH, so the protected-branch guard cannot verify this git command on this host. Install pwsh (https://aka.ms/install-powershell) before running git commands here - refusing to allow this command ungated rather than letting it through unguarded."}}'

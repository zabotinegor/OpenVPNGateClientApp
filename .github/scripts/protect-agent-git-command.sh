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
    # -Shell bash tells the guard which grammar the command line it is about to
    # judge will actually be executed under. bash and PowerShell disagree on
    # backslash escaping ('\;' is one literal semicolon in bash, an argument plus
    # a real statement separator in PowerShell), and that decides where one
    # command ends and the next begins. The guard also reads the payload's
    # tool_name and defaults to the stricter PowerShell rules, so this is a hint,
    # not a requirement.
    exec pwsh -NoProfile -NonInteractive -File .github/scripts/protect-agent-git-command.ps1 -Shell bash
fi

printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"protect-agent-git-command: pwsh (PowerShell 7+) was not found on PATH, so the protected-branch guard cannot run on this host. Install pwsh (https://aka.ms/install-powershell) before running git commands here - refusing to allow this command ungated rather than letting it through unguarded."}}'

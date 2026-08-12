@echo off
rem Windows-host entrypoint for the protected-branch guard's PowerShell matcher.
rem
rem protected-branches.json used to wire the "powershell" matcher straight to a
rem bare `pwsh -File ...` command. That is right when pwsh (PowerShell 7+) is
rem on PATH, but on a Windows host that only has the built-in Windows
rem PowerShell (powershell.exe, 5.1) - no pwsh installed - that bare command
rem fails to launch at all. A PreToolUse hook that never launches is not the
rem same as one that denies: the harness sees no blocking result and lets the
rem git command through - the guard was silently doing nothing on exactly the
rem hosts most likely to hit this (stock Windows machines with only the
rem in-box PowerShell). See protect-agent-git-command.sh for the equivalent
rem gap that existed on Bash-only hosts.
rem
rem Unlike the Bash-only fallback, this one does not need to degrade to a
rem pattern-match approximation of the guard: protect-agent-git-command.ps1
rem uses no PowerShell 7-only syntax, so powershell.exe (5.1) can run the
rem exact same script and get the exact same, fully correct, verdict. Try
rem pwsh first, then powershell.exe, and only fail closed (deny) if this host
rem has neither - a state that should not be reachable in practice, since a
rem "PowerShell" tool invocation implies some PowerShell exists to run it.
rem
rem This entrypoint relies on the hook's declared "cwd": "." like every other
rem hook command here, but resolves the guard script from its own directory
rem (%~dp0) rather than the caller's cwd, so it does not depend on that.

where pwsh >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    pwsh -NoProfile -NonInteractive -File "%~dp0protect-agent-git-command.ps1"
    exit /b %ERRORLEVEL%
)

where powershell >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    powershell -NoProfile -NonInteractive -File "%~dp0protect-agent-git-command.ps1"
    exit /b %ERRORLEVEL%
)

echo {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"protect-agent-git-command: neither pwsh (PowerShell 7+) nor Windows PowerShell (powershell.exe) was found on PATH, so the protected-branch guard cannot run. Install PowerShell before running commands here - refusing to allow this command ungated rather than letting it through unguarded."}}
exit /b 0

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
rem A found-but-broken host is a THIRD case, distinct from "not found": `where`
rem can succeed while the resolved pwsh/powershell is stale, corrupt, or the
rem guard script itself throws before printing a verdict. That launch/exec
rem failure surfaces as a non-zero exit code, but this hook's caller only
rem treats exit code 2 as blocking - every other non-zero code (see
rem sync-copilot-assets.ps1's Remove-DeadHookEntries comment: "runs, fails,
rem and exits non-2, which the harness treats as a non-blocking error") is
rem waved through same as exit 0, silently allowing the protected-branch git
rem command the guard exists to stop. So a broken host's exit code must never
rem be forwarded as-is: fall back to the other PowerShell host first, and if
rem that also fails (or is absent), emit the same deny-JSON verdict used for
rem "neither found" with exit 0 - the one outcome this caller reliably reads
rem as a real decision, blocking or not.
rem
rem This entrypoint relies on the hook's declared "cwd": "." like every other
rem hook command here, but resolves the guard script from its own directory
rem (%~dp0) rather than the caller's cwd, so it does not depend on that.

rem Delayed expansion is required below: inside a parenthesized IF block,
rem cmd.exe expands every %VAR% reference once, at parse time, using the
rem value %VAR% held BEFORE the block started running - not the value set by
rem a command that already executed earlier in that same block. With plain
rem %ERRORLEVEL%, "exit /b %ERRORLEVEL%" on the line right after the launch
rem attempt would still read the *pre-block* value (the "where" success, 0),
rem not the launch's real exit code - so a broken/stale pwsh or powershell on
rem PATH that fails to launch would report success, skip the fallback (or the
rem deny below), and let the git command through unguarded. !ERRORLEVEL!
rem (delayed expansion) is evaluated at execution time instead, so it picks
rem up the actual launch result.
setlocal enabledelayedexpansion

where pwsh >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    pwsh -NoProfile -NonInteractive -File "%~dp0protect-agent-git-command.ps1"
    if !ERRORLEVEL! EQU 0 (
        exit /b 0
    )
    rem pwsh was on PATH but exited non-zero: it crashed, is broken/stale, or
    rem the guard script itself errored before it could print a verdict. Do
    rem NOT forward this code (the caller only blocks on exit 2 - see the
    rem header comment above) - fall through and try powershell.exe instead
    rem of treating a failed launch as an implicit allow.
)

where powershell >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    powershell -NoProfile -NonInteractive -File "%~dp0protect-agent-git-command.ps1"
    if !ERRORLEVEL! EQU 0 (
        exit /b 0
    )
    rem Same reasoning as the pwsh branch above: a non-zero exit here means
    rem powershell.exe also failed to produce a verdict. Fall through to the
    rem explicit deny below instead of forwarding the raw failure code.
)

echo {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"protect-agent-git-command: the protected-branch guard could not produce an allow/deny verdict on this host - either no PowerShell (pwsh or powershell.exe) was found on PATH, or the one(s) found failed to launch or run the guard script to completion. Refusing to allow this command ungated rather than treating a broken or missing guard as a green light."}}
exit /b 0

$ErrorActionPreference = 'Stop'

$protected = @('main', 'dev', 'master', 'develop')
$protectedPattern = '(?:main|dev|master|develop)'
$gitPrefixPattern = '\bgit(?:\s+-C\s+\S+|\s+--git-dir(?:=\S+|\s+\S+)|\s+--work-tree(?:=\S+|\s+\S+)|\s+--no-pager|\s+--paginate|\s+--bare|\s+-c\s+\S+|\s+--exec-path(?:=\S+|\s+\S+)|\s+--namespace=\S+|\s+--no-replace-objects|\s+--no-optional-locks|\s+--literal-pathspecs|\s+--no-literal-pathspecs|\s+--glob-pathspecs|\s+--noglob-pathspecs|\s+--icase-pathspecs)*'
$payloadText = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($payloadText)) {
    Write-Output '{}'
    exit 0
}

$payload = $null
try {
    $payload = $payloadText | ConvertFrom-Json
}
catch {
    Write-Output '{}'
    exit 0
}
if ($null -eq $payload) {
    Write-Output '{}'
    exit 0
}
$toolInput = if ($null -ne $payload.tool_input) { $payload.tool_input } else { $payload.toolArgs }
if ($toolInput -is [string]) {
    $rawString = $toolInput
    try { $toolInput = $toolInput | ConvertFrom-Json }
    catch { $toolInput = $rawString }
}
$command = if ($toolInput -is [string]) { $toolInput } elseif ($null -ne $toolInput -and $null -ne $toolInput.command) { [string]$toolInput.command } else { '' }
$cwd = if (-not [string]::IsNullOrWhiteSpace([string]$payload.cwd)) { [string]$payload.cwd } else { (Get-Location).Path }

$repoRoot = ''
$branch = ''
$previousEap = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $repoRoot = ((git -C $cwd rev-parse --show-toplevel 2>$null) | Out-String).Trim()
    $branch = ((git -C $cwd branch --show-current 2>$null) | Out-String).Trim().ToLowerInvariant()
}
finally {
    $ErrorActionPreference = $previousEap
}

if (-not [string]::IsNullOrWhiteSpace($repoRoot) -and (Test-Path -LiteralPath (Join-Path $repoRoot '.copilottools-source'))) {
    Write-Output '{}'
    exit 0
}

$normalized = ($command -replace '\s+', ' ').Trim()
$switchPattern = "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+(?:switch|checkout)\s+(?:-\S+\s+)*(?!--)([^\s;&|]+)"

$reason = $null
if ($normalized -match '(?i)(^|[;&|]\s*)git\s+') {
    # Commit: evaluate branch state at each commit occurrence independently so a
    # switch AFTER a reset (but before the commit) is correctly accounted for.
    foreach ($cm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+commit\b")) {
        $eff = $branch
        foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
            if ($sm.Index -ge $cm.Index) { break }
            $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
            if (-not $t.StartsWith('-')) { $eff = $t }
        }
        if ($protected -contains $eff) {
            $reason = "Direct commit on protected branch '$eff' is forbidden."
            break
        }
    }

    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+push\b") {
        $pushMatch = [regex]::Match($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+push\b")
        $eff = $branch
        if ($pushMatch.Success) {
            foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
                if ($sm.Index -ge $pushMatch.Index) { break }
                $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                if (-not $t.StartsWith('-')) { $eff = $t }
            }
        }
        if ($normalized -match '(?i)(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$|[;&|])') {
            $reason = 'Force-push is forbidden in client repositories.'
        }
        elseif ($normalized -match '(?i)(?:^|\s)(?:--all|--branches|--mirror)(?:\s|$|[;&|])') {
            $reason = 'Bulk push (--all/--branches/--mirror) may update protected refs and is forbidden.'
        }
        else {
            # Release-flow archive exception: permit the two dev-branch mutations that occur in
            # the archive step after a release squash merge (step 5 of release-flow-orchestrator).
            #   1. git push origin --delete dev   (remove old dev after pushing the archive branch)
            #   2. git push [-u] origin dev        (push new dev recreated from merged main)
            # Authorization condition for (1): origin/archive/archive-dev-* exists (archive was pushed).
            # Authorization condition for (2): same + local dev SHA == origin/main SHA.
            $allowReleaseArchivePush = $false
            $isDevPush   = $eff -eq 'dev' -or $normalized -match "(?i)\b(?:origin|upstream)\s+(?:-u\s+)?dev(?![-\w/.])"
            $isDevDelete = $normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+dev(?![-\w/.])"
            # Guard: if command also targets main/master/develop in any push/delete context,
            # the exception does not apply — e.g. `git push origin --delete dev main`.
            foreach ($op in @('main', 'master', 'develop')) {
                if ($normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+$op(?![-\w/.])" -or
                    $normalized -match "(?i)\b(?:origin|upstream)\s+\+?$op(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s):$op(?![-\w/.])" -or
                    $normalized -match "(?i)\brefs/heads/$op(?![-\w/.])") {
                    $isDevPush = $false; $isDevDelete = $false; break
                }
            }
            if ($isDevPush -or $isDevDelete) {
                $previousEap2 = $ErrorActionPreference
                try {
                    $ErrorActionPreference = 'Continue'
                    $remotes = (git -C $cwd branch -r 2>$null) -join "`n"
                    if ($remotes -match 'archive/archive-dev-') {
                        if ($isDevDelete) {
                            $allowReleaseArchivePush = $true
                        } elseif ($isDevPush) {
                            $devSha  = ((git -C $cwd rev-parse dev        2>$null) | Out-String).Trim()
                            $mainSha = ((git -C $cwd rev-parse origin/main 2>$null) | Out-String).Trim()
                            if ($devSha -and $mainSha -and $devSha -eq $mainSha) { $allowReleaseArchivePush = $true }
                        }
                    }
                }
                finally { $ErrorActionPreference = $previousEap2 }
            }
            if (-not $allowReleaseArchivePush) {
                if ($protected -contains $eff -and $normalized -notmatch '(?i)\bHEAD:') {
                    $reason = "Direct push from protected branch '$eff' is forbidden."
                }
                elseif (
                    $normalized -match "(?i)\b(?:origin|upstream)\s+$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)\b(?:origin|upstream)\s+\+$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s)\+(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)\brefs/heads/$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s):$protectedPattern(?![-\w/.])"
                ) {
                    $reason = 'Direct push, deletion, or recreation of a protected branch is forbidden.'
                }
            }
        }
    }

    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+update-ref\b[^\r\n]*refs/heads/$protectedPattern(?![-\w/.])") {
        $reason = 'Direct protected branch ref mutation is forbidden.'
    }

    # Reset: evaluate branch state at each reset occurrence independently.
    # Scope flag check to the argument slice of each reset so --hard from a later
    # reset does not falsely trigger on an earlier soft reset. Strip quotes from
    # switch targets so git switch "main" does not bypass the protected check.
    if (-not $reason) {
        foreach ($rm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+reset\b")) {
            $eff = $branch
            foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
                if ($sm.Index -ge $rm.Index) { break }
                $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                if (-not $t.StartsWith('-')) { $eff = $t }
            }
            $tail = $normalized.Substring($rm.Index + $rm.Length)
            $argSeg = ($tail -split '[;&|]+')[0]
            if ($protected -contains $eff -and
                    $argSeg -match '(?i)(?:^|\s)(?:--hard|--keep|--merge)(?:\s|$)') {
                $reason = "Hard reset on protected branch '$eff' is forbidden."
                break
            }
        }
    }

    # Branch -f: iterate each branch command independently so a later forced
    # protected branch is not masked by an earlier harmless branch command.
    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+branch\b") {
        foreach ($bm in [regex]::Matches($normalized, "(?i)$gitPrefixPattern\s+branch\b")) {
            $seg = ($normalized.Substring($bm.Index) -split '[;&|]+')[0]
            if ($seg -match '(?i)(?:^|\s)(?:-f|--force)(?:\s|$|[;&|])') {
                if ($seg -match "(?i)$gitPrefixPattern\s+branch\b((?:\s+-\S+)*)\s+([^\s-]\S*)") {
                    $branchTarget = ($Matches[2] -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                    if ($protected -contains $branchTarget) {
                        $reason = 'Forcing a protected branch pointer is forbidden.'
                        break
                    }
                }
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($reason)) {
    Write-Output '{}'
    exit 0
}

[ordered]@{
    permissionDecision = 'deny'
    permissionDecisionReason = $reason
    hookSpecificOutput = [ordered]@{
        hookEventName = 'PreToolUse'
        permissionDecision = 'deny'
        permissionDecisionReason = $reason
    }
} | ConvertTo-Json -Depth 5 -Compress

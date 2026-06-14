$ErrorActionPreference = 'Stop'

$protected = @('main', 'dev', 'master', 'develop')
$protectedPattern = '(?:main|dev|master|develop)'
$gitPrefixPattern = '\bgit(?:\s+-C\s+\S+|\s+--git-dir(?:=\S+|\s+\S+)|\s+--work-tree(?:=\S+|\s+\S+))*'
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
$switchPattern = "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+(?:switch|checkout)\s+(?:-\S+\s+)*(?!--)(\S+)\b"

$reason = $null
if ($normalized -match '(?i)(^|[;&|]\s*)git\s+') {
    # Commit: evaluate branch state at each commit occurrence independently so a
    # switch AFTER a reset (but before the commit) is correctly accounted for.
    foreach ($cm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+commit\b")) {
        $eff = $branch
        foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
            if ($sm.Index -ge $cm.Index) { break }
            $t = $sm.Groups[1].Value.ToLowerInvariant()
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
                $t = $sm.Groups[1].Value.ToLowerInvariant()
                if (-not $t.StartsWith('-')) { $eff = $t }
            }
        }
        if ($normalized -match '(?i)(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$)') {
            $reason = 'Force-push is forbidden in client repositories.'
        }
        elseif ($protected -contains $eff -and $normalized -notmatch '(?i)\bHEAD:') {
            $reason = "Direct push from protected branch '$eff' is forbidden."
        }
        elseif (
            $normalized -match "(?i)\b(?:origin|upstream)\s+$protectedPattern(?![-\w/.])" -or
            $normalized -match "(?i)\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
            $normalized -match "(?i)\brefs/heads/$protectedPattern(?![-\w/.])" -or
            $normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+$protectedPattern(?![-\w/.])" -or
            $normalized -match "(?i)(?:^|\s):$protectedPattern(?![-\w/.])"
        ) {
            $reason = 'Direct push, deletion, or recreation of a protected branch is forbidden.'
        }
    }

    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+update-ref\b[^\r\n]*refs/heads/$protectedPattern(?![-\w/.])") {
        $reason = 'Direct protected branch ref mutation is forbidden.'
    }

    # Reset: evaluate branch state at each reset occurrence independently.
    # Use [;&|] in the flag terminator so --hard;next (no space) is still caught.
    if (-not $reason) {
        foreach ($rm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+reset\b")) {
            $eff = $branch
            foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
                if ($sm.Index -ge $rm.Index) { break }
                $t = $sm.Groups[1].Value.ToLowerInvariant()
                if (-not $t.StartsWith('-')) { $eff = $t }
            }
            if ($protected -contains $eff -and
                    $normalized -match '(?i)(?:^|\s)(?:--hard|--keep|--merge)(?:\s|$|[;&|])') {
                $reason = "Hard reset on protected branch '$eff' is forbidden."
                break
            }
        }
    }

    # Branch -f: use [;&|] in flag terminator; strip quotes from extracted branch name.
    if (-not $reason -and
            $normalized -match "(?i)$gitPrefixPattern\s+branch\b" -and
            $normalized -match '(?i)(?:^|\s)(?:-f|--force)(?:\s|$|[;&|])') {
        if ($normalized -match "(?i)$gitPrefixPattern\s+branch\b((?:\s+-\S+)*)\s+([^\s-]\S*)") {
            $branchTarget = ($Matches[2] -replace "^['`"]+|['`"]+$").ToLowerInvariant()
            if ($protected -contains $branchTarget) {
                $reason = 'Forcing a protected branch pointer is forbidden.'
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

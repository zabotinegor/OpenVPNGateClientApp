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
    try { $toolInput = $toolInput | ConvertFrom-Json }
    catch { $toolInput = $null }
}
$command = if ($null -ne $toolInput -and $null -ne $toolInput.command) { [string]$toolInput.command } else { '' }
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
$reason = $null
if ($normalized -match '(?i)(^|[;&|]\s*)git\s+') {
    # Determine effective branch at the first mutation, accounting for preceding switches
    $effectiveBranch = $branch
    $mutateMatch = [regex]::Match($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+(?:commit|push)\b")
    $mutatePos = if ($mutateMatch.Success) { $mutateMatch.Index } else { $normalized.Length }
    foreach ($sm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+(?:switch|checkout)\s+(?:-\S+\s+)*(?!--)(\S+)\b")) {
        if ($sm.Index -ge $mutatePos) { break }
        $t = $sm.Groups[1].Value.ToLowerInvariant()
        if (-not $t.StartsWith('-')) { $effectiveBranch = $t }
    }

    if ($normalized -match "(?i)$gitPrefixPattern\s+commit\b" -and $protected -contains $effectiveBranch) {
        $reason = "Direct commit on protected branch '$effectiveBranch' is forbidden."
    }
    elseif ($normalized -match "(?i)$gitPrefixPattern\s+push\b") {
        if ($normalized -match '(?i)(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$)') {
            $reason = 'Force-push is forbidden in client repositories.'
        }
        elseif ($protected -contains $effectiveBranch -and $normalized -notmatch '(?i)\bHEAD:') {
            $reason = "Direct push from protected branch '$effectiveBranch' is forbidden."
        }
        elseif (
            $normalized -match "(?i)\b(?:origin|upstream)\s+$protectedPattern\b" -or
            $normalized -match "(?i)\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?$protectedPattern\b" -or
            $normalized -match "(?i)\brefs/heads/$protectedPattern\b" -or
            $normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+$protectedPattern\b" -or
            $normalized -match "(?i)(?:^|\s):$protectedPattern\b"
        ) {
            $reason = 'Direct push, deletion, or recreation of a protected branch is forbidden.'
        }
    }
    elseif ($normalized -match "(?i)$gitPrefixPattern\s+update-ref\b[^\r\n]*refs/heads/$protectedPattern\b") {
        $reason = 'Direct protected branch ref mutation is forbidden.'
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

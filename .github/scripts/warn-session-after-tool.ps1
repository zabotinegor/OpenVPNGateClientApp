param(
    [string]$WorkingDirectory = (Get-Location).Path
)

# Fast inline implementation -- no child process spawning.
# Fires after every tool call via PostToolUse hook; must stay cheap.
# Also inlines the rate-limit cache read so session.json is kept current
# without waiting for the Stop hook.

function Resolve-RepoRoot {
    param([string]$StartPath)
    $resolved = (Resolve-Path -LiteralPath $StartPath).Path
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $gitRoot = git -C $resolved rev-parse --show-toplevel 2>$null
    }
    finally { $ErrorActionPreference = $previousEap }

    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitRoot)) {
        return (Resolve-Path -LiteralPath $gitRoot.Trim()).Path
    }
    return $resolved
}

function ConvertTo-UtcOffset {
    param($v)
    if ($v -is [datetime]) {
        $utcDt = if ($v.Kind -eq [DateTimeKind]::Local) { $v.ToUniversalTime() } else { $v }
        return [DateTimeOffset]::new($utcDt, [TimeSpan]::Zero)
    }
    return [DateTimeOffset]::Parse($v.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
}

function ConvertTo-UtcZ { param($v)
    if ($v -is [datetime]) {
        $utcDt = if ($v.Kind -eq [DateTimeKind]::Local) { $v.ToUniversalTime() } else { $v }
        return $utcDt.ToString("yyyy-MM-ddTHH:mm:ss'Z'")
    }
    if ($v -is [DateTimeOffset]) { return $v.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'") }
    return [DateTimeOffset]::Parse($v.ToString(), [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
}

try {
    $repoRoot    = Resolve-RepoRoot -StartPath $WorkingDirectory
    $sessionPath = Join-Path $repoRoot '.sdlc' 'session.json'

    if (-not (Test-Path -LiteralPath $sessionPath)) { exit 0 }

    $raw = Get-Content -LiteralPath $sessionPath -Raw
    if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }

    $session = $raw | ConvertFrom-Json
    if ($session.status -ne 'active') { exit 0 }

    $now      = [DateTimeOffset]::UtcNow
    $usedPct  = [double]$session.usedPercentage
    $resetsAt = $null

    # --- Inline cache read: update session.json if fresh API data is available ---
    # Reads ~/.claude/rate-limit-cache.json directly (written by Chrome extension + Node server).
    # No subprocess spawn -- keeps PostToolUse hook cheap.
    try {
        $cachePath = Join-Path $HOME '.claude' 'rate-limit-cache.json'
        if (Test-Path -LiteralPath $cachePath) {
            $cacheRaw = Get-Content -LiteralPath $cachePath -Raw
            if (-not [string]::IsNullOrWhiteSpace($cacheRaw)) {
                $cache = $cacheRaw | ConvertFrom-Json
                if ($cache.success -eq $true -and $cache.PSObject.Properties['fetchedAtUtc'] -and $null -ne $cache.fetchedAtUtc) {
                    # Reject if session window has already reset (data is from old window)
                    $cacheValid = $true
                    if ($cache.PSObject.Properties['resetsAtUtc'] -and $null -ne $cache.resetsAtUtc) {
                        try {
                            $cacheResetsAt = ConvertTo-UtcOffset $cache.resetsAtUtc
                            if ($now -gt $cacheResetsAt) { $cacheValid = $false }
                        } catch { $cacheValid = $false }
                    }
                    # Safety net: never use data older than 6 hours
                    $fetchedDt = ConvertTo-UtcOffset $cache.fetchedAtUtc
                    if (($now - $fetchedDt).TotalHours -gt 6) { $cacheValid = $false }
                    if ($cacheValid) {
                        # Fresh data -- update session.json and use real values for the warning
                        $apiResetsIso = ConvertTo-UtcZ $cache.resetsAtUtc
                        $usedPct  = [double]$cache.usedPercentage
                        $resetsAt = [DateTimeOffset]::Parse($apiResetsIso, [System.Globalization.CultureInfo]::InvariantCulture)
                        $session.usedPercentage = $usedPct
                        $session.trackingMode   = 'api'
                        $session.resetsAtUtc    = $apiResetsIso
                        $session.lastCheckUtc   = $now.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                        $encoding = New-Object System.Text.UTF8Encoding($false)
                        [System.IO.File]::WriteAllText($sessionPath, ($session | ConvertTo-Json -Depth 10) + "`r`n", $encoding)
                    }  # cacheValid
                }
            }
        }
    } catch { }

    # Parse resetsAt from session if not already set from cache
    if ($null -eq $resetsAt -and -not [string]::IsNullOrWhiteSpace($session.resetsAtUtc)) {
        try { $resetsAt = ConvertTo-UtcOffset $session.resetsAtUtc } catch { }
    }

    # Window already expired -- nothing to warn about
    if ($resetsAt -and $now -gt $resetsAt) { exit 0 }

    if ($usedPct -ge 75) {
        $resetsAtStr = if ($resetsAt) { $resetsAt.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'") } else { $session.resetsAtUtc }
        $level = if ($usedPct -ge 90) { 'CRITICAL' } elseif ($usedPct -ge 80) { 'HIGH' } else { 'WARNING' }
        Write-Output "[SESSION $level] Usage: $usedPct% (resets at $resetsAtStr). Finish current atomic action and checkpoint before the next reasoning block."
    }
} catch {
    # Never throw from a hook
}

exit 0

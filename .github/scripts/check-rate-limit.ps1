param(
    [string]$WorkingDirectory = (Get-Location).Path
)

$ErrorActionPreference = 'Stop'

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

function Read-SessionJson {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $raw = Get-Content -LiteralPath $Path -Raw
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    try { return $raw | ConvertFrom-Json }
    catch { return $null }
}

$repoRoot = Resolve-RepoRoot -StartPath $WorkingDirectory
$sessionPath = Join-Path $repoRoot '.sdlc' 'session.json'
$session = Read-SessionJson -Path $sessionPath

# Pure reader — never calls init-session.ps1 or fetch-usage-api.ps1.
# Agents must run init-session.ps1 explicitly as their first step.
if ($null -eq $session) {
    @{ status = 'no-session'; used_percentage = 0; remaining_minutes = 300; resets_at = $null } | ConvertTo-Json -Depth 5
    exit 0
}

# If the rate-limit window has already expired, the session is effectively fresh regardless
# of what session.json says (exhausted/checkpointed from the previous window are stale).
$_windowExpired = $false
if (-not [string]::IsNullOrWhiteSpace($session.resetsAtUtc)) {
    try {
        $rv = $session.resetsAtUtc
        $_resetsAtCheck = if ($rv -is [datetime]) {
            $utcDt = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
            [DateTimeOffset]::new($utcDt, [TimeSpan]::Zero)
        } else {
            [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
        }
        if ([DateTimeOffset]::UtcNow -gt $_resetsAtCheck) { $_windowExpired = $true }
    } catch {}
}
if ($_windowExpired) {
    @{ status = 'ok'; used_percentage = 0; remaining_minutes = 300; resets_at = $null; session_id = $session.sessionId; window_reset = $true } | ConvertTo-Json -Depth 5
    exit 0
}

if ($session.status -eq 'completed') {
    @{ status = 'completed'; used_percentage = 0; remaining_minutes = 300; resets_at = $null } | ConvertTo-Json -Depth 5
    exit 0
}

if ($session.status -eq 'checkpointed') {
    $cp = $session.checkpoint
    $cpResetsAt = $null
    if (-not [string]::IsNullOrWhiteSpace($session.resetsAtUtc)) {
        try {
            $rv = $session.resetsAtUtc
            $cpResetsAt = if ($rv -is [datetime]) {
                $utcDt = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
                $utcDt.ToString("yyyy-MM-ddTHH:mm:ss'Z'")
            } else { [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'") }
        } catch { $cpResetsAt = $session.resetsAtUtc }
    }
    @{ status = 'checkpointed'; used_percentage = [double]$session.usedPercentage; remaining_minutes = 0; resets_at = $cpResetsAt; checkpoint = $cp } | ConvertTo-Json -Depth 10
    exit 0
}

$usedPct = [double]$session.usedPercentage
$remainingMinutes = 300
$now = [DateTimeOffset]::UtcNow
$resetsAt = $null

# Inline cache refresh: update session.json from the Chrome extension cache whenever
# fresh API data is available. Runs on every PreToolUse (via check-session-before-tool)
# so the session is current before each tool decision, not just after.
try {
    $cachePath = Join-Path $HOME '.claude' 'rate-limit-cache.json'
    if (Test-Path -LiteralPath $cachePath) {
        $cacheRaw = Get-Content -LiteralPath $cachePath -Raw
        if (-not [string]::IsNullOrWhiteSpace($cacheRaw)) {
            $cache = $cacheRaw | ConvertFrom-Json
            if ($cache.success -eq $true -and $cache.PSObject.Properties['fetchedAtUtc'] -and $null -ne $cache.fetchedAtUtc) {
                $fv = $cache.fetchedAtUtc
                $fetchedDt = if ($fv -is [datetime]) {
                    $utcDt = if ($fv.Kind -eq [DateTimeKind]::Local) { $fv.ToUniversalTime() } else { $fv }
                    [DateTimeOffset]::new($utcDt, [TimeSpan]::Zero)
                } else {
                    [DateTimeOffset]::Parse($fv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
                }
                if (($now - $fetchedDt).TotalMinutes -le 30) {
                    $rv = $cache.resetsAtUtc
                    $apiResetsIso = if ($rv -is [datetime]) {
                        $utcDt = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
                        $utcDt.ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    } elseif ($rv -is [DateTimeOffset]) {
                        $rv.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    } else {
                        [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    }
                    $apiPct = [double]$cache.usedPercentage
                    $session.usedPercentage = $apiPct
                    $session.trackingMode   = 'api'
                    $session.resetsAtUtc    = $apiResetsIso
                    $session.lastCheckUtc   = $now.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    $encoding = New-Object System.Text.UTF8Encoding($false)
                    [System.IO.File]::WriteAllText($sessionPath, ($session | ConvertTo-Json -Depth 10) + "`r`n", $encoding)
                    $usedPct = $apiPct
                }
            }
        }
    }
} catch { }

if (-not [string]::IsNullOrWhiteSpace($session.resetsAtUtc)) {
    $resetsAtValue = $session.resetsAtUtc
    try {
        $resetsAt = if ($resetsAtValue -is [datetime]) {
            $utcDt = if ($resetsAtValue.Kind -eq [DateTimeKind]::Local) { $resetsAtValue.ToUniversalTime() } else { $resetsAtValue }
            [DateTimeOffset]::new($utcDt, [TimeSpan]::Zero)
        } else {
            [DateTimeOffset]::Parse($resetsAtValue, [System.Globalization.CultureInfo]::InvariantCulture)
        }
        $remainingMinutes = [Math]::Max(0, [Math]::Floor(($resetsAt - $now).TotalMinutes))
    } catch { }
}

$status = 'ok'
if ($usedPct -ge 100) {
    $status = 'exhausted'
} elseif ($usedPct -ge 80) {
    $status = 'warning'
}

# Normalize resets_at to Z suffix so callers always see UTC regardless of how
# ConvertFrom-Json coerced the stored timestamp (Z -> Kind=Utc, +00:00 -> Kind=Local).
$resetsAtOut = $null
if (-not [string]::IsNullOrWhiteSpace($session.resetsAtUtc)) {
    try {
        $rv = $session.resetsAtUtc
        $resetsAtOut = if ($rv -is [datetime]) {
            $utcDt = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
            $utcDt.ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        } elseif ($rv -is [DateTimeOffset]) {
            $rv.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        } else {
            [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        }
    } catch { $resetsAtOut = $session.resetsAtUtc }
}

@{
    status = $status
    used_percentage = $usedPct
    remaining_minutes = $remainingMinutes
    resets_at = $resetsAtOut
    session_id = $session.sessionId
    tracking_mode = $session.trackingMode
} | ConvertTo-Json -Depth 5

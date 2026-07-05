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

$repoRoot = Resolve-RepoRoot -StartPath $WorkingDirectory
$sdlcDir = Join-Path $repoRoot '.sdlc'
$sessionPath = Join-Path $sdlcDir 'session.json'

if (-not (Test-Path -LiteralPath $sdlcDir)) {
    New-Item -ItemType Directory -Path $sdlcDir -Force | Out-Null
}

$now      = [DateTimeOffset]::UtcNow
$oldResetsAt = $null

if (Test-Path -LiteralPath $sessionPath) {
    $raw = Get-Content -LiteralPath $sessionPath -Raw
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $existing = $raw | ConvertFrom-Json
            if ($existing.status -eq 'active') {
                # If the rate-limit window has already expired, fall through and reinitialize.
                $windowExpired = $false
                if (-not [string]::IsNullOrWhiteSpace($existing.resetsAtUtc)) {
                    try {
                        $rv = $existing.resetsAtUtc
                        $oldResetsAt = if ($rv -is [datetime]) {
                            $utcDt = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
                            [DateTimeOffset]::new($utcDt, [TimeSpan]::Zero)
                        } else {
                            [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
                        }
                        if ($now -gt $oldResetsAt) { $windowExpired = $true }
                    } catch {}
                }
                if (-not $windowExpired) {
                    # Refresh usedPercentage from the extension cache before returning,
                    # so each user message starts with the latest known value.
                    try {
                        $cachePath = Join-Path $HOME '.claude' 'rate-limit-cache.json'
                        if (Test-Path -LiteralPath $cachePath) {
                            $cacheRaw = Get-Content -LiteralPath $cachePath -Raw
                            if (-not [string]::IsNullOrWhiteSpace($cacheRaw)) {
                                $cache = $cacheRaw | ConvertFrom-Json
                                if ($cache.success -eq $true) {
                                    $cacheValid = $true
                                                    if ($cache.PSObject.Properties['resetsAtUtc'] -and $null -ne $cache.resetsAtUtc) {
                                        try {
                                            $rv = $cache.resetsAtUtc
                                            $cr = if ($rv -is [datetime]) {
                                                $utc = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
                                                [DateTimeOffset]::new($utc, [TimeSpan]::Zero)
                                            } else {
                                                [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
                                            }
                                            if ($now -gt $cr) { $cacheValid = $false }
                                        } catch { $cacheValid = $false }
                                    }
                                    if ($cache.PSObject.Properties['fetchedAtUtc'] -and $null -ne $cache.fetchedAtUtc) {
                                        try {
                                            $fv = $cache.fetchedAtUtc
                                            $ft = if ($fv -is [datetime]) {
                                                $utc = if ($fv.Kind -eq [DateTimeKind]::Local) { $fv.ToUniversalTime() } else { $fv }
                                                [DateTimeOffset]::new($utc, [TimeSpan]::Zero)
                                            } else {
                                                [DateTimeOffset]::Parse($fv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
                                            }
                                            if (($now - $ft).TotalHours -gt 6) { $cacheValid = $false }
                                        } catch { $cacheValid = $false }
                                    }
                                    if ($cacheValid) {
                                        $existing.usedPercentage = [double]$cache.usedPercentage
                                        $existing.trackingMode   = 'api'
                                        $existing.resetsAtUtc    = $cache.resetsAtUtc.ToString()
                                        $existing.lastCheckUtc   = $now.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                                        $enc = New-Object System.Text.UTF8Encoding($false)
                                        [System.IO.File]::WriteAllText($sessionPath, ($existing | ConvertTo-Json -Depth 10) + "`r`n", $enc)
                                    }
                                }
                            }
                        }
                    } catch {}
                    @{ created = $false; reason = 'active-session-exists'; path = $sessionPath } | ConvertTo-Json
                    exit 0
                }
            } elseif (-not [string]::IsNullOrWhiteSpace($existing.resetsAtUtc)) {
                # Capture old window boundary from non-active sessions too (checkpointed/completed)
                try {
                    $rv = $existing.resetsAtUtc
                    $oldResetsAt = if ($rv -is [datetime]) {
                        $utcDt = if ($rv.Kind -eq [DateTimeKind]::Local) { $rv.ToUniversalTime() } else { $rv }
                        [DateTimeOffset]::new($utcDt, [TimeSpan]::Zero)
                    } else {
                        [DateTimeOffset]::Parse($rv.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
                    }
                } catch {}
            }
        } catch {}
    }
}

# Fetch live rate-limit data via CDP (Chrome DevTools Protocol).
# Falls back to the Chrome extension push cache. No time-based estimation.
$windowStart  = $null
$windowEnd    = $null
$initialPct   = 0.0
$trackingMode = 'unknown'

$fetchScript = Join-Path $repoRoot '.github' 'scripts' 'fetch-usage-api.ps1'
$apiFetched  = $false

if (Test-Path -LiteralPath $fetchScript) {
    try {
        $fetchOut = & pwsh -NoProfile -NonInteractive -File $fetchScript 2>$null
        if (-not [string]::IsNullOrWhiteSpace($fetchOut)) {
            $fetchData = $fetchOut | ConvertFrom-Json
            if ($fetchData.success -eq $true) {
                $initialPct = [double]$fetchData.usedPercentage
                try {
                    $raw = $fetchData.resetsAtUtc
                    $windowEnd = if ($raw -is [DateTimeOffset]) {
                        $raw.ToUniversalTime()
                    } elseif ($raw -is [DateTime]) {
                        [DateTimeOffset]::new([DateTime]::SpecifyKind($raw, [DateTimeKind]::Utc))
                    } else {
                        [DateTimeOffset]::Parse($raw.ToString(), [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime()
                    }
                    $windowStart  = $windowEnd.AddHours(-5)
                    $trackingMode = 'api'
                    $apiFetched   = $true
                } catch { }
            }
        }
    } catch { }
}

$sessionId = [guid]::NewGuid().ToString('N').Substring(0, 12)

$sessionHash = [ordered]@{
    version        = 1
    sessionId      = $sessionId
    lastCheckUtc   = $now.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
    usedPercentage = $initialPct
    resetsAtUtc    = if ($apiFetched) { $windowEnd.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'") } else { $null }
    sessionStartUtc = if ($apiFetched) { $windowStart.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'") } else { $null }
    trackingMode   = $trackingMode
    status         = 'active'
}

$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($sessionPath, (($sessionHash | ConvertTo-Json -Depth 5) + "`r`n"), $encoding)

@{
    created      = $true
    path         = $sessionPath
    sessionId    = $sessionId
    trackingMode = $trackingMode
    resetsAt     = if ($apiFetched) { $windowEnd.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'") } else { $null }
    usedPct      = $initialPct
} | ConvertTo-Json -Depth 5

#!/usr/bin/env pwsh
# Tests for sync-agent-assets.ps1 — specifically the AGENTS.md governance-marker
# injection gate. That injection is gated behind -AllowRootMdSync (CopilotTools PR
# #3 / tf-20260905): a plain sync must not touch a client AGENTS.md, markers
# included; a sync WITH -AllowRootMdSync injects/refreshes the
# <!-- BEGIN AGENT SYNC -->..<!-- END AGENT SYNC --> block while leaving content
# outside the markers byte-for-byte unchanged.
# Run: pwsh -File test-sync-agent-assets.ps1 [-WorkingDirectory <repo>]
# Exits 0 on all pass, 1 on any failure.

param([string]$WorkingDirectory = (Get-Location).Path)

$ErrorActionPreference = 'Stop'
$pass = 0; $fail = 0

function Assert {
    param([string]$Label, [bool]$Condition)
    if ($Condition) { Write-Host "  PASS  $Label" -ForegroundColor Green; $script:pass++ }
    else            { Write-Host "  FAIL  $Label" -ForegroundColor Red;   $script:fail++ }
}

function Resolve-RepoRoot {
    param([string]$StartPath)
    $r = (Resolve-Path -LiteralPath $StartPath -ErrorAction SilentlyContinue).Path
    if (-not $r) { return $StartPath }
    $g = git -C $r rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -eq 0 -and $g) { return (Resolve-Path $g.Trim()).Path }
    return $r
}

$repoRoot = Resolve-RepoRoot $WorkingDirectory
$script = Join-Path $repoRoot '.github' 'scripts' 'sync-agent-assets.ps1'

if (-not (Test-Path -LiteralPath $script)) {
    Write-Host "Results: 0 passed, 1 failed" -ForegroundColor Red
    Write-Host "  FAIL  sync-agent-assets.ps1 not found at $script" -ForegroundColor Red
    exit 1
}

$governanceBody = @(
    '## Universal Governance'
    ''
    'These rules propagate to every client repository.'
    ''
    '- Rule one.'
    '- Rule two.'
)

function New-GitRepo {
    param([string]$Path)
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    git -c init.defaultBranch=main init --quiet $Path 2>&1 | Out-Null
    git -C $Path config user.email 'test@example.com' 2>&1 | Out-Null
    git -C $Path config user.name  'Test Runner'       2>&1 | Out-Null
    git -C $Path config commit.gpgsign false           2>&1 | Out-Null
}

function New-SourceRepo {
    # A throwaway CopilotTools stand-in: one in-scope agent file plus the shared
    # governance rules file the injection reads.
    $src = Join-Path ([System.IO.Path]::GetTempPath()) ("sync-src-" + [System.Guid]::NewGuid().ToString('N'))
    New-GitRepo -Path $src

    $agentsDir = Join-Path $src '.github/agents'
    New-Item -ItemType Directory -Path $agentsDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $agentsDir 'example.agent.md') -Value "---`nname: Example`n---`nBody.`n" -NoNewline

    $sharedDir = Join-Path $src '.github/skills/shared'
    New-Item -ItemType Directory -Path $sharedDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $sharedDir 'agents-core-rules.md') -Value ([string]::Join("`n", $governanceBody) + "`n") -NoNewline

    git -C $src add -A 2>&1 | Out-Null
    git -C $src commit --quiet -m 'fixture' 2>&1 | Out-Null
    return $src
}

function New-TargetRepo {
    param([string]$AgentsMdContent)
    $tgt = Join-Path ([System.IO.Path]::GetTempPath()) ("sync-tgt-" + [System.Guid]::NewGuid().ToString('N'))
    New-GitRepo -Path $tgt

    $agentsDir = Join-Path $tgt '.github/agents'
    New-Item -ItemType Directory -Path $agentsDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $agentsDir 'example.agent.md') -Value "---`nname: Example`n---`nBody.`n" -NoNewline

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $tgt 'AGENTS.md'), $AgentsMdContent, $utf8)

    git -C $tgt add -A 2>&1 | Out-Null
    git -C $tgt commit --quiet -m 'initial' 2>&1 | Out-Null
    return $tgt
}

function Invoke-Sync {
    param([string]$Source, [string]$Target, [switch]$AllowRootMdSync)
    $params = @{
        SourceRepo = $Source
        SourceRef  = 'main'
        TargetRoot = $Target
        Scope      = @('.github/agents')
    }
    if ($AllowRootMdSync) { $params['AllowRootMdSync'] = $true }
    $raw = & $script @params 2>$null
    return ($raw | Out-String | ConvertFrom-Json)
}

function Get-Bytes { param([string]$Path) return ,([System.IO.File]::ReadAllBytes($Path)) }
function Bytes-Equal {
    param([byte[]]$A, [byte[]]$B)
    if ($A.Length -ne $B.Length) { return $false }
    for ($i = 0; $i -lt $A.Length; $i++) { if ($A[$i] -ne $B[$i]) { return $false } }
    return $true
}

$src = $null; $created = @()
try {
    $src = New-SourceRepo
    $created += $src

    $clientPreamble = @(
        '# Client AGENTS.md'
        ''
        'Project-specific guidance that agent-sync must never disturb.'
        ''
    )
    $noMarkers = [string]::Join("`n", $clientPreamble) + "`n"

    $staleSection = @(
        '<!-- BEGIN AGENT SYNC -->'
        '## Stale Governance'
        ''
        '- Old rule that no longer matches source.'
        '<!-- END AGENT SYNC -->'
    )
    $clientTail = @(
        ''
        '## Client-Specific Section'
        ''
        'More content below the markers that must survive verbatim.'
    )
    $withStaleMarkers = [string]::Join("`n", ($clientPreamble + $staleSection + $clientTail)) + "`n"

    Write-Host "== 1. Default run (no -AllowRootMdSync), AGENTS.md has no markers =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $before = Get-Bytes (Join-Path $t 'AGENTS.md')
    $res = Invoke-Sync -Source $src -Target $t
    $after = Get-Bytes (Join-Path $t 'AGENTS.md')
    Assert "AGENTS.md is byte-identical after a default sync" (Bytes-Equal $before $after)
    Assert "no markers were injected" (-not ((Get-Content -LiteralPath (Join-Path $t 'AGENTS.md') -Raw) -match 'BEGIN AGENT SYNC'))
    Assert "agentsCoreRulesInjection is null on a default run" ($null -eq $res.agentsCoreRulesInjection)
    Assert "AGENTS.md is not in the changed list" (@($res.changed) -notcontains 'AGENTS.md')
    Assert "verification passed" ($res.verification -eq 'passed')

    Write-Host "== 2. Default run (no -AllowRootMdSync), AGENTS.md already has stale markers =="
    $t = New-TargetRepo -AgentsMdContent $withStaleMarkers
    $created += $t
    $before = Get-Bytes (Join-Path $t 'AGENTS.md')
    $res = Invoke-Sync -Source $src -Target $t
    $after = Get-Bytes (Join-Path $t 'AGENTS.md')
    Assert "existing marker block is NOT refreshed on a default run (byte-identical)" (Bytes-Equal $before $after)
    Assert "stale governance text is still present (not replaced)" ((Get-Content -LiteralPath (Join-Path $t 'AGENTS.md') -Raw) -match 'Stale Governance')
    Assert "agentsCoreRulesInjection is null on a default run" ($null -eq $res.agentsCoreRulesInjection)
    Assert "AGENTS.md is not in the changed list" (@($res.changed) -notcontains 'AGENTS.md')

    Write-Host "== 3. -AllowRootMdSync run injects markers into an AGENTS.md that had none =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $res = Invoke-Sync -Source $src -Target $t -AllowRootMdSync
    $content = Get-Content -LiteralPath (Join-Path $t 'AGENTS.md') -Raw
    Assert "BEGIN marker was injected" ($content -match '<!-- BEGIN AGENT SYNC -->')
    Assert "END marker was injected" ($content -match '<!-- END AGENT SYNC -->')
    Assert "source governance body was injected" ($content -match 'These rules propagate to every client repository\.')
    Assert "pre-existing client content is preserved" ($content -match 'Project-specific guidance that agent-sync must never disturb\.')
    Assert "agentsCoreRulesInjection reports a change" ($null -ne $res.agentsCoreRulesInjection -and $res.agentsCoreRulesInjection.changed -eq $true)
    Assert "agentsCoreRulesInjection action is added-markers" ($res.agentsCoreRulesInjection.action -eq 'added-markers')
    Assert "AGENTS.md is in the changed list" (@($res.changed) -contains 'AGENTS.md')
    Assert "verification passed" ($res.verification -eq 'passed')

    Write-Host "== 4. -AllowRootMdSync run refreshes a stale marker block, content outside untouched =="
    $t = New-TargetRepo -AgentsMdContent $withStaleMarkers
    $created += $t
    $res = Invoke-Sync -Source $src -Target $t -AllowRootMdSync
    $content = Get-Content -LiteralPath (Join-Path $t 'AGENTS.md') -Raw
    Assert "stale governance text was removed" (-not ($content -match 'Old rule that no longer matches source'))
    Assert "fresh source governance body is present" ($content -match 'These rules propagate to every client repository\.')
    Assert "content before the markers is preserved verbatim" ($content -match 'Project-specific guidance that agent-sync must never disturb\.')
    Assert "content after the markers is preserved verbatim" ($content -match 'More content below the markers that must survive verbatim\.')
    Assert "exactly one BEGIN marker remains" ((([regex]::Matches($content, 'BEGIN AGENT SYNC')).Count) -eq 1)
    Assert "agentsCoreRulesInjection action is replaced-section" ($res.agentsCoreRulesInjection.action -eq 'replaced-section')

    Write-Host "== 5. A second -AllowRootMdSync run is idempotent =="
    # $t is the repo from scenario 4, now carrying a fresh marker block.
    $before = Get-Bytes (Join-Path $t 'AGENTS.md')
    $res = Invoke-Sync -Source $src -Target $t -AllowRootMdSync
    $after = Get-Bytes (Join-Path $t 'AGENTS.md')
    Assert "AGENTS.md is byte-identical on a repeat -AllowRootMdSync run" (Bytes-Equal $before $after)
    Assert "agentsCoreRulesInjection action is no-change" ($res.agentsCoreRulesInjection.action -eq 'no-change')
    Assert "AGENTS.md is not in the changed list" (@($res.changed) -notcontains 'AGENTS.md')

    Write-Host "== 6. AGENTS.md explicitly in scope without -AllowRootMdSync is refused =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $threw = $false
    try {
        & $script -SourceRepo $src -SourceRef 'main' -TargetRoot $t -Scope @('.github/agents', 'AGENTS.md') 2>$null | Out-Null
    } catch { $threw = $true }
    Assert "scope containing AGENTS.md is rejected without -AllowRootMdSync" ($threw -or $LASTEXITCODE -ne 0)
    Assert "AGENTS.md was left untouched after the refusal" ((Get-Content -LiteralPath (Join-Path $t 'AGENTS.md') -Raw) -notmatch 'BEGIN AGENT SYNC')
}
finally {
    foreach ($d in $created) {
        if ($d -and (Test-Path -LiteralPath $d)) {
            Remove-Item -LiteralPath $d -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host ""
if ($fail -eq 0) { Write-Host "Results: $pass passed, 0 failed" -ForegroundColor Green; exit 0 }
Write-Host "Results: $pass passed, $fail failed" -ForegroundColor Red
exit 1

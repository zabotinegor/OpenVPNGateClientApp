#!/usr/bin/env pwsh
# Tests for sync-agent-assets.ps1 — specifically the AGENTS.md governance-marker
# injection gate. That injection is gated behind -AllowRootMdSync: a plain
# sync must not touch a client AGENTS.md, markers
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
    # governance rules file the injection reads. -PathPrefix lets a caller force
    # a space into the checkout path (e.g. 'sync src spaced-') to regression-test
    # argument quoting through Invoke-ExternalCommand's git invocations.
    param([string]$PathPrefix = 'sync-src-', [switch]$WithConfigMerge, [switch]$WithEscapingAdapter, [string]$EvilAdapterPath = '')
    $src = Join-Path ([System.IO.Path]::GetTempPath()) ($PathPrefix + [System.Guid]::NewGuid().ToString('N'))
    New-GitRepo -Path $src

    $agentsDir = Join-Path $src '.github/agents'
    New-Item -ItemType Directory -Path $agentsDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $agentsDir 'example.agent.md') -Value "---`nname: Example`n---`nBody.`n" -NoNewline

    $sharedDir = Join-Path $src '.github/skills/shared'
    New-Item -ItemType Directory -Path $sharedDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $sharedDir 'agents-core-rules.md') -Value ([string]::Join("`n", $governanceBody) + "`n") -NoNewline

    # The sync core now resolves shared/runtime inventory from source-owned
    # distribution metadata. Keep this fixture intentionally minimal while
    # proving that a runtime post-sync adapter is discovered without a core edit.
    $distributionDir = Join-Path $src '.github/copilottools'
    $runtimeDir = Join-Path $distributionDir 'runtimes'
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    @{
        schemaVersion = 1
        sharedAssets = @('.github/agents', '.github/skills', '.github/scripts', '.githooks', '.copilottools')
        platformAssets = @{ githubCopilot = @('.github/agents') }
        sharedMergeJsonPaths = @('.copilottools/config.json')
        seedOnlyConfigKeys = @(@{ path = '.copilottools/config.json'; keys = @('anonymous_resource_telemetry_enabled', 'anonymous_resource_telemetry_repository', 'anonymous_resource_telemetry_branch') })
        retiredLegacyDirectories = @(@{ path = '.kilo/command'; replacedBy = '.kilo/commands' })
        assetCategories = @{}
        sharedTransientIgnorePaths = @('/.sdlc/telemetry/', '/.sdlc/telemetry/**', '**/.sdlc/telemetry/**')
        excludeSyncPathPatterns = @('(^|/)chatgpt-[^/]+(?:/|$)')
        blockedBroadGitignorePatterns = @()
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $distributionDir 'distribution.json') -Encoding UTF8
    @{
        id = 'fixture-runtime'
        distribution = @{
            assets = @('.fixture-runtime/commands')
            reportCategory = 'fixtureRuntime'
            includeInParity = $false
            postSyncAdapter = '.github/scripts/runtime/fixture-runtime/post-sync.ps1'
            legacyPaths = @(@{ path = '.fixture-runtime/command'; replacedBy = '.fixture-runtime/commands' })
        }
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runtimeDir 'fixture-runtime.json') -Encoding UTF8
    New-Item -ItemType Directory -Path (Join-Path $src '.fixture-runtime/commands') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $src '.fixture-runtime/commands/example.md') -Value 'example' -NoNewline
    New-Item -ItemType Directory -Path (Join-Path $src '.kilo/commands') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $src '.kilo/commands/example.md') -Value 'example' -NoNewline

    if ($WithConfigMerge) {
        # Codex-style generated config: the adapter keeps target-only settings and enforces one managed line.
        @{
            id = 'cfgmerge'
            distribution = @{
                assets = @('.cfgmerge/config.toml')
                reportCategory = 'cfgmerge'
                includeInParity = $false
                configMerges = @(@{ path = '.cfgmerge/config.toml'; adapterScript = '.github/scripts/runtime/cfgmerge/merge.ps1' })
            }
        } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runtimeDir 'cfgmerge.json') -Encoding UTF8
        New-Item -ItemType Directory -Path (Join-Path $src '.cfgmerge') -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $src '.cfgmerge/config.toml') -Value "managed = 2`nnew_key = 1`n" -NoNewline
        New-Item -ItemType Directory -Path (Join-Path $src '.github/scripts/runtime/cfgmerge') -Force | Out-Null
        $mergeStub = @'
param([Parameter(Mandatory)][string]$SourcePath, [Parameter(Mandatory)][string]$TargetPath)
$ErrorActionPreference = 'Stop'
$srcLines = @(Get-Content -LiteralPath $SourcePath -Encoding UTF8)
$tgtLines = @(Get-Content -LiteralPath $TargetPath -Encoding UTF8)
$managed = @($srcLines | Where-Object { $_ -match '^managed\s*=' })[0]
$out = New-Object System.Collections.Generic.List[string]
$seen = $false
foreach ($l in $tgtLines) { if ($l -match '^managed\s*=') { $out.Add($managed); $seen = $true } else { $out.Add($l) } }
if (-not $seen) { $out.Add($managed) }
[ordered]@{ schemaVersion = 1; content = (($out -join "`n") + "`n") } | ConvertTo-Json -Compress
'@
        Set-Content -LiteralPath (Join-Path $src '.github/scripts/runtime/cfgmerge/merge.ps1') -Value $mergeStub -NoNewline
    }

    if ($WithEscapingAdapter) {
        # An adapter that wants to change a path owned by a different runtime than the one in scope.
        @{
            id = 'escaper'
            distribution = @{
                assets = @('.escaper')
                reportCategory = 'escaper'
                includeInParity = $false
                postSyncAdapter = '.github/scripts/runtime/escaper/post-sync.ps1'
            }
        } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runtimeDir 'escaper.json') -Encoding UTF8
        New-Item -ItemType Directory -Path (Join-Path $src '.escaper') -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $src '.escaper/a.md') -Value 'a' -NoNewline
        New-Item -ItemType Directory -Path (Join-Path $src '.github/scripts/runtime/escaper') -Force | Out-Null
        $escStub = @'
param([Parameter(Mandatory)][string]$SourceRoot, [Parameter(Mandatory)][string]$TargetRoot, [switch]$DryRun)
$ErrorActionPreference = 'Stop'
$victim = Join-Path $TargetRoot '.unrelated/victim.txt'
if (-not $DryRun -and (Test-Path -LiteralPath $victim)) { Remove-Item -LiteralPath $victim -Force }
[ordered]@{ schemaVersion = 1; changed = $true; changedPaths = @('.unrelated/victim.txt'); warnings = @() } | ConvertTo-Json -Compress
'@
        Set-Content -LiteralPath (Join-Path $src '.github/scripts/runtime/escaper/post-sync.ps1') -Value $escStub -NoNewline
    }

    if ($EvilAdapterPath) {
        @{
            id = 'evil'
            distribution = @{
                assets = @('.evil')
                reportCategory = 'evil'
                includeInParity = $false
                postSyncAdapter = $EvilAdapterPath
            }
        } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runtimeDir 'evil.json') -Encoding UTF8
        New-Item -ItemType Directory -Path (Join-Path $src '.evil') -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $src '.evil/a.md') -Value 'a' -NoNewline
    }
    $configDir = Join-Path $src '.copilottools'
    New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    @{
        claude_session_recovery_enabled = $false
        anonymous_resource_telemetry_enabled = $true
        anonymous_resource_telemetry_repository = 'zabotinegor/CopilotTools'
        anonymous_resource_telemetry_branch = 'resource-telemetry'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $configDir 'config.json') -Encoding UTF8

    $runtimeAdapterDir = Join-Path $src '.github/scripts/runtime/fixture-runtime'
    New-Item -ItemType Directory -Path $runtimeAdapterDir -Force | Out-Null
    # Inline stub with the same contract as the real adapter (retire an empty legacy directory, report
    # the bare path). Nothing here is copied from the working checkout, so the suite runs from a clean one.
    $fixtureStub = @'
param([Parameter(Mandatory)][string]$SourceRoot, [Parameter(Mandatory)][string]$TargetRoot, [switch]$DryRun)
$ErrorActionPreference = 'Stop'
$stale = Join-Path $TargetRoot '.fixture-runtime/command'
$changed = $false
if ((Test-Path -LiteralPath $stale) -and @(Get-ChildItem -LiteralPath $stale -File -Recurse -ErrorAction SilentlyContinue).Count -eq 0) {
    $changed = $true
    if (-not $DryRun) { Remove-Item -LiteralPath $stale -Recurse -Force }
}
[ordered]@{ schemaVersion = 1; changed = $changed; changedPaths = @(if ($changed) { '.fixture-runtime/command' }); warnings = @() } | ConvertTo-Json -Compress
'@
    Set-Content -LiteralPath (Join-Path $runtimeAdapterDir 'post-sync.ps1') -Value $fixtureStub -NoNewline

    foreach ($rel in @('.githooks/post-checkout', '.githooks/pre-commit', '.githooks/pre-push',
                      '.github/scripts/sync-agent-assets.ps1', '.github/scripts/runtime-layer-hash.ps1',
                      '.github/scripts/runtime-distribution-lib.ps1')) {
        $dst = Join-Path $src $rel
        New-Item -ItemType Directory -Path (Split-Path -Parent $dst) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repoRoot $rel) -Destination $dst -Force
    }

    git -C $src add -A 2>&1 | Out-Null
    git -C $src commit --quiet -m 'fixture' 2>&1 | Out-Null
    return $src
}

function New-TargetRepo {
    # -PathPrefix lets a caller force a space into the checkout path (e.g.
    # 'sync tgt spaced-') to regression-test argument quoting through
    # Invoke-ExternalCommand's git invocations (e.g. 'git -C <Root> config ...').
    param([string]$AgentsMdContent, [switch]$StaleFixtureCommand, [string]$PathPrefix = 'sync-tgt-')
    $tgt = Join-Path ([System.IO.Path]::GetTempPath()) ($PathPrefix + [System.Guid]::NewGuid().ToString('N'))
    New-GitRepo -Path $tgt

    $agentsDir = Join-Path $tgt '.github/agents'
    New-Item -ItemType Directory -Path $agentsDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $agentsDir 'example.agent.md') -Value "---`nname: Example`n---`nBody.`n" -NoNewline

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $tgt 'AGENTS.md'), $AgentsMdContent, $utf8)
    if ($StaleFixtureCommand) {
        New-Item -ItemType Directory -Path (Join-Path $tgt '.fixture-runtime/command') -Force | Out-Null
    }

    git -C $tgt add -A 2>&1 | Out-Null
    git -C $tgt commit --quiet -m 'initial' 2>&1 | Out-Null
    return $tgt
}

function Invoke-Sync {
    param([string]$Source, [string]$Target, [switch]$AllowRootMdSync, [string[]]$Scope = @('.github/agents'))
    $params = @{
        SourceRepo = $Source
        SourceRef  = 'main'
        TargetRoot = $Target
        Scope      = $Scope
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

    Write-Host "== 7. Stale singular fixture runtime directory reports a bare changed path =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers -StaleFixtureCommand
    $created += $t
    $res = Invoke-Sync -Source $src -Target $t -Scope @('.github/agents', '.fixture-runtime/commands')
    Assert "stale .fixture-runtime/command directory is removed" (-not (Test-Path -LiteralPath (Join-Path $t '.fixture-runtime/command')))
    Assert "changed list contains the bare stale directory path" (@($res.changed) -contains '.fixture-runtime/command')
    Assert "changed list has no descriptive stale-directory suffix" (@($res.changed | Where-Object { $_ -match '\s' }).Count -eq 0)

    Write-Host "== 7a. Hidden-only retired directory must be preserved =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $legacyDir = Join-Path $t '.kilo/command'
    New-Item -ItemType Directory -Path $legacyDir -Force | Out-Null
    $hiddenFile = Join-Path $legacyDir '.hidden.md'
    Set-Content -LiteralPath $hiddenFile -Value 'user-owned' -NoNewline
    if ($IsWindows) {
        (Get-Item -LiteralPath $hiddenFile -Force).Attributes = [IO.FileAttributes]::Hidden
    }
    $res = Invoke-Sync -Source $src -Target $t -Scope @('.github/agents', '.kilo/commands')
    Assert "hidden-file-only legacy directory is preserved" (Test-Path -LiteralPath $hiddenFile)
    Assert "hidden-file-only legacy directory is not reported removed" (@($res.changed) -notcontains '.kilo/command')

    Write-Host "== 7b. Subdirectory-only retired directory must be preserved =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $legacyDir = Join-Path $t '.kilo/command'
    New-Item -ItemType Directory -Path (Join-Path $legacyDir 'nested') -Force | Out-Null
    $res = Invoke-Sync -Source $src -Target $t -Scope @('.github/agents', '.kilo/commands')
    Assert "subdirectory-only legacy directory is preserved" (Test-Path -LiteralPath (Join-Path $legacyDir 'nested') -PathType Container)
    Assert "subdirectory-only legacy directory is not reported removed" (@($res.changed) -notcontains '.kilo/command')

    Write-Host "== 7c. Successful cleanup of an empty retired directory is verified =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $legacyDir = Join-Path $t '.kilo/command'
    New-Item -ItemType Directory -Path $legacyDir -Force | Out-Null
    $res = Invoke-Sync -Source $src -Target $t -Scope @('.github/agents', '.kilo/commands')
    Assert "empty retired legacy directory was removed" (-not (Test-Path -LiteralPath $legacyDir))
    Assert "successful retired directory cleanup reports the bare path" (@($res.changed) -contains '.kilo/command')

    Write-Host "== 7d. Removal error is never reported as successful cleanup =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $legacyDir = Join-Path $t '.kilo/command'
    New-Item -ItemType Directory -Path $legacyDir -Force | Out-Null
    # Simulated access denial gives a reliable regression test on any OS.
    $global:SyncTestBlockedLegacyPath = $legacyDir
    function global:Remove-Item {
        [CmdletBinding(DefaultParameterSetName = 'Path')]
        param(
            [Parameter(ParameterSetName = 'LiteralPath')][string[]]$LiteralPath,
            [Parameter(ParameterSetName = 'Path')][string[]]$Path,
            [switch]$Recurse,
            [switch]$Force
        )
        if (@($LiteralPath) -contains $global:SyncTestBlockedLegacyPath) {
            throw 'Simulated access denied for legacy directory'
        }
        Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
    }
    $cleanupRejected = $false
    try {
        try {
            Invoke-Sync -Source $src -Target $t -Scope @('.github/agents', '.kilo/commands') | Out-Null
        } catch {
            $cleanupRejected = $true
        }
    } finally {
        Microsoft.PowerShell.Management\Remove-Item -LiteralPath Function:\global:Remove-Item -Force
        Remove-Variable -Name SyncTestBlockedLegacyPath -Scope Global -ErrorAction SilentlyContinue
    }
    Assert "failed legacy directory removal fails the sync" $cleanupRejected
    Assert "failed legacy directory removal leaves the directory intact" (Test-Path -LiteralPath $legacyDir -PathType Container)

    Write-Host "== 8. Bootstrap helper dot-sourced by the entrypoint stays trackable =="
    $srcScripts = Join-Path $src '.github/scripts'
    New-Item -ItemType Directory -Path $srcScripts -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $srcScripts 'runtime-layer-hash.ps1') -Value '# helper' -NoNewline
    Set-Content -LiteralPath (Join-Path $srcScripts 'other-tool.ps1') -Value '# other' -NoNewline
    git -C $src add -A 2>&1 | Out-Null
    git -C $src commit --quiet -m 'scripts' 2>&1 | Out-Null
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    [System.IO.File]::WriteAllText((Join-Path $t '.gitignore'), "# BEGIN synced-agent-assets`n/.github/scripts/runtime-layer-hash.ps1`n# END synced-agent-assets`n", (New-Object System.Text.UTF8Encoding($false)))
    & $script -SourceRepo $src -SourceRef 'main' -TargetRoot $t -Scope @('.github/agents', '.github/scripts') 2>$null | Out-Null
    $ignore = Get-Content -LiteralPath (Join-Path $t '.gitignore') -Raw
    Assert "helper file was synced" (Test-Path -LiteralPath (Join-Path $t '.github/scripts/runtime-layer-hash.ps1'))
    Assert "helper is not in the managed .gitignore block" ($ignore -notmatch 'runtime-layer-hash')
    Assert "stale helper ignore line was removed" ($ignore -notmatch '(?m)^/\.github/scripts/runtime-layer-hash\.ps1$')
    Assert "non-bootstrap script is still ignored" ($ignore -match '/\.github/scripts/other-tool\.ps1')

    Write-Host "== 9. Generated asset lock is gitignored; config.json stays trackable =="
    $t = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $t
    $res = Invoke-Sync -Source $src -Target $t -Scope @('.github/agents', '.copilottools')
    $lockPath = Join-Path $t '.copilottools/asset-lock.json'
    Assert "sync wrote the asset lock" (Test-Path -LiteralPath $lockPath)
    git -C $t check-ignore -q -- '.copilottools/asset-lock.json' 2>&1 | Out-Null
    Assert "asset lock is ignored by git" ($LASTEXITCODE -eq 0)
    $clientConfigPath = Join-Path $t '.copilottools/config.json'
    Assert "config.json is synchronized to the client" (Test-Path -LiteralPath $clientConfigPath)
    git -C $t check-ignore -q -- '.copilottools/config.json' 2>&1 | Out-Null
    Assert "config.json is not ignored" ($LASTEXITCODE -ne 0)
    $clientConfig = Get-Content -LiteralPath $clientConfigPath -Raw | ConvertFrom-Json
    Assert "anonymous resource telemetry config is synchronized" ($clientConfig.anonymous_resource_telemetry_enabled -eq $true -and $clientConfig.anonymous_resource_telemetry_branch -eq 'resource-telemetry')
    New-Item -ItemType Directory -Path (Join-Path $t '.sdlc/telemetry/outbox') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $t '.sdlc/telemetry/outbox/test.json') -Value '{}' -Encoding UTF8
    git -C $t check-ignore -q -- '.sdlc/telemetry/outbox/test.json' 2>&1 | Out-Null
    Assert "telemetry runtime data is ignored by git" ($LASTEXITCODE -eq 0)
    $status = @(git -C $t status --porcelain --untracked-files=all | Where-Object { $_ -match 'asset-lock|\.sdlc/telemetry' })
    Assert "generated lock and telemetry data do not appear as untracked" ($status.Count -eq 0)

    Write-Host "== 10. Source and target checkout paths containing a space are handled correctly (regression: Start-Process -ArgumentList quoting gap in Invoke-ExternalCommand) =="
    # A source or target checkout under a spaced Windows path (e.g. 'C:\Users\Jane
    # Doe\...') must not have its git invocations silently split into multiple
    # argv entries by Invoke-ExternalCommand. This exercises 'git clone'/'git
    # checkout' (source path with a space) and 'git -C <target> config ...'
    # (target path with a space, via Set-RepositoryGitHooksPath) end to end.
    $spacedSrc = New-SourceRepo -PathPrefix 'sync src spaced '
    $created += $spacedSrc
    $spacedTgt = New-TargetRepo -AgentsMdContent $noMarkers -PathPrefix 'sync tgt spaced '
    $created += $spacedTgt
    Assert "fixture source path contains a space" ($spacedSrc -match ' ')
    Assert "fixture target path contains a space" ($spacedTgt -match ' ')
    $spacedRes = $null
    $spacedThrew = $false
    try { $spacedRes = Invoke-Sync -Source $spacedSrc -Target $spacedTgt -Scope @('.github/agents', '.githooks') } catch { $spacedThrew = $true }
    Assert "sync with spaced source/target paths did not throw" (-not $spacedThrew)
    Assert "sync with spaced source/target paths reports verification passed" ($null -ne $spacedRes -and $spacedRes.verification -eq 'passed')
    $spacedHooksPath = ((git -C $spacedTgt config --local --get core.hooksPath 2>$null) | Out-String).Trim()
    Assert "git config core.hooksPath was set on the spaced-path target (git -C <spaced path> config ... survived argument quoting)" (-not [string]::IsNullOrWhiteSpace($spacedHooksPath))

    Write-Host "== 10b. Trusted Git hooks remain immutable across malicious branch checkout =="
    $secureSrc = New-SourceRepo
    $created += $secureSrc
    $secureTgt = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $secureTgt
    $null = Invoke-Sync -Source $secureSrc -Target $secureTgt -Scope @('.github/agents', '.githooks')
    $hookDir = ((git -C $secureTgt config --local --get core.hooksPath) | Out-String).Trim()
    Assert "hooks installed in Git directory, outside versioned files" ([IO.Path]::IsPathRooted($hookDir) -and $hookDir -match '[\\/]\.git[\\/]')
    Assert "installed hook matches source after newline normalization" (([string]::Join([char]10, @(Get-Content -LiteralPath (Join-Path $hookDir 'post-checkout')))) -eq ([string]::Join([char]10, @(Get-Content -LiteralPath (Join-Path $secureSrc '.githooks/post-checkout')))))
    Assert "installed bootstrap matches source after newline normalization" (([string]::Join([char]10, @(Get-Content -LiteralPath (Join-Path $hookDir 'trusted-runtime/sync-agent-assets.ps1')))) -eq ([string]::Join([char]10, @(Get-Content -LiteralPath (Join-Path $secureSrc '.github/scripts/sync-agent-assets.ps1')))))
    $refresh = Join-Path $secureTgt '.github/scripts/refresh-agent-assets.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent $refresh) -Force | Out-Null
    Set-Content -LiteralPath $refresh -Value '# offline fixture'
    git -C $secureTgt checkout -q -b feature/safe
    $hook = Join-Path $secureTgt '.githooks/post-checkout'
    $ps = Join-Path $secureTgt '.github/scripts/sync-agent-assets.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent $hook) -Force | Out-Null
    @('#!/bin/sh','exit 0') | Set-Content -LiteralPath $hook
    New-Item -ItemType Directory -Path (Split-Path -Parent $ps) -Force | Out-Null
    Set-Content -LiteralPath $ps -Value '# benign'
    git -C $secureTgt add -f -- '.githooks/post-checkout' '.github/scripts/sync-agent-assets.ps1' | Out-Null
    git -C $secureTgt commit -q -m 'safe' | Out-Null
    git -C $secureTgt checkout -q -b feature/evil
    @('#!/bin/sh','touch branch-hook-executed') | Set-Content -LiteralPath $hook
    Set-Content -LiteralPath $ps -Value "New-Item -ItemType File -Path (Join-Path (Get-Location).Path 'branch-script-executed') -Force"
    git -C $secureTgt add -f -- '.githooks/post-checkout' '.github/scripts/sync-agent-assets.ps1' | Out-Null
    git -C $secureTgt commit -q -m 'evil' | Out-Null
    git -C $secureTgt checkout -q feature/safe
    git -C $secureTgt checkout -q feature/evil
    Assert "branch hook was not executed" (-not (Test-Path (Join-Path $secureTgt 'branch-hook-executed')))
    Assert "branch bootstrap was not executed" (-not (Test-Path (Join-Path $secureTgt 'branch-script-executed')))

    Write-Host "== 10c. Upgrading a legacy relative hooksPath installs trusted hooks in Git metadata =="
    $legacySrc = New-SourceRepo
    $created += $legacySrc
    $legacyTgt = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $legacyTgt
    git -C $legacyTgt config --local core.hooksPath '.githooks'
    $legacyGitCommon = ((git -C $legacyTgt rev-parse --path-format=absolute --git-common-dir) | Out-String).Trim()
    $legacyExpected = Join-Path $legacyGitCommon 'copilottools-trusted-hooks'
    $legacyResult = Invoke-Sync -Source $legacySrc -Target $legacyTgt -Scope @('.github/agents', '.githooks')
    $legacyConfigured = ((git -C $legacyTgt config --local --get core.hooksPath) | Out-String).Trim()
    Assert "legacy core.hooksPath=.githooks is upgraded into Git metadata" ($legacyConfigured -eq $legacyExpected)
    Assert "trusted pre-commit hook exists outside checkout" (Test-Path -LiteralPath (Join-Path $legacyExpected 'pre-commit') -PathType Leaf)
    Assert "no trusted-hook directory is created in the legacy checkout" (-not (Test-Path -LiteralPath (Join-Path $legacyTgt 'copilottools-trusted-hooks')))
    Assert "legacy relative hook upgrade verifies successfully" ($legacyResult.verification -eq 'passed')
    $legacyRepeat = Invoke-Sync -Source $legacySrc -Target $legacyTgt -Scope @('.github/agents', '.githooks')
    Assert "legacy hook upgrade is idempotent" ($legacyRepeat.gitHooksConfiguration.status -eq 'already-configured')

    Write-Host "== 10d. A linked worktree shares trusted hooks in the common Git directory =="
    $linkedSrc = New-SourceRepo
    $created += $linkedSrc
    $linkedMain = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $linkedMain
    git -C $linkedMain config --local core.hooksPath '.githooks'
    $linkedTgt = Join-Path ([System.IO.Path]::GetTempPath()) ('sync linked checkout ' + [System.Guid]::NewGuid().ToString('N'))
    git -C $linkedMain worktree add --quiet -b 'feature/linked-legacy-hooks' $linkedTgt
    if ($LASTEXITCODE -ne 0) { throw 'Could not create linked worktree for trusted-hooks regression test.' }
    $created += $linkedTgt
    $linkedCommon = ((git -C $linkedTgt rev-parse --path-format=absolute --git-common-dir) | Out-String).Trim()
    $linkedExpected = Join-Path $linkedCommon 'copilottools-trusted-hooks'
    $linkedResult = Invoke-Sync -Source $linkedSrc -Target $linkedTgt -Scope @('.github/agents', '.githooks')
    $linkedConfigured = ((git -C $linkedTgt config --local --get core.hooksPath) | Out-String).Trim()
    $mainConfigured = ((git -C $linkedMain config --local --get core.hooksPath) | Out-String).Trim()
    Assert "linked worktree config resolves to shared Git metadata hooks" ($linkedConfigured -eq $linkedExpected -and $mainConfigured -eq $linkedExpected)
    Assert "shared trusted pre-push exists in common Git metadata" (Test-Path -LiteralPath (Join-Path $linkedExpected 'pre-push') -PathType Leaf)
    Assert "no disposable linked-worktree trusted directory exists" (-not (Test-Path -LiteralPath (Join-Path $linkedTgt 'copilottools-trusted-hooks')))
    Assert "no primary-worktree trusted directory exists" (-not (Test-Path -LiteralPath (Join-Path $linkedMain 'copilottools-trusted-hooks')))
    Assert "linked worktree legacy upgrade verifies successfully" ($linkedResult.verification -eq 'passed')
    $linkedRepeat = Invoke-Sync -Source $linkedSrc -Target $linkedTgt -Scope @('.github/agents', '.githooks')
    Assert "linked worktree trusted hooks remain idempotent" ($linkedRepeat.gitHooksConfiguration.status -eq 'already-configured')

    Write-Host "== 11. Telemetry opt-out is target-owned: seed-only keys survive later syncs, new source keys still propagate =="
    $aSrc = New-SourceRepo
    $created += $aSrc
    $aTgt = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $aTgt
    $aConfigPath = Join-Path $aTgt '.copilottools/config.json'
    $null = Invoke-Sync -Source $aSrc -Target $aTgt -Scope @('.github/agents', '.copilottools')
    $first = Get-Content -LiteralPath $aConfigPath -Raw | ConvertFrom-Json
    Assert "first provisioning seeds telemetry enabled from the source" ($first.anonymous_resource_telemetry_enabled -eq $true)
    # The client opts out by hand.
    $manual = Get-Content -LiteralPath $aConfigPath -Raw | ConvertFrom-Json
    $manual.anonymous_resource_telemetry_enabled = $false
    [System.IO.File]::WriteAllText($aConfigPath, ($manual | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
    $null = Invoke-Sync -Source $aSrc -Target $aTgt -Scope @('.github/agents', '.copilottools')
    $second = Get-Content -LiteralPath $aConfigPath -Raw | ConvertFrom-Json
    Assert "opt-out (false) survives a later sync while the source still says true" ($second.anonymous_resource_telemetry_enabled -eq $false)
    # The source gains an unrelated field and flips a non-seed key.
    $srcCfgPath = Join-Path $aSrc '.copilottools/config.json'
    $srcCfg = Get-Content -LiteralPath $srcCfgPath -Raw | ConvertFrom-Json
    $srcCfg | Add-Member -NotePropertyName 'brand_new_source_field' -NotePropertyValue 'propagated' -Force
    $srcCfg.claude_session_recovery_enabled = $true
    [System.IO.File]::WriteAllText($srcCfgPath, ($srcCfg | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
    git -C $aSrc add -A 2>&1 | Out-Null
    git -C $aSrc commit --quiet -m 'config update' 2>&1 | Out-Null
    $null = Invoke-Sync -Source $aSrc -Target $aTgt -Scope @('.github/agents', '.copilottools')
    $third = Get-Content -LiteralPath $aConfigPath -Raw | ConvertFrom-Json
    Assert "opt-out still survives when other source keys change" ($third.anonymous_resource_telemetry_enabled -eq $false)
    Assert "an unrelated new source field propagates" ($third.brand_new_source_field -eq 'propagated')
    Assert "a non-seed source key still updates the target" ($third.claude_session_recovery_enabled -eq $true)
    $beforeBytes = Get-Bytes $aConfigPath
    $idem = Invoke-Sync -Source $aSrc -Target $aTgt -Scope @('.github/agents', '.copilottools')
    Assert "repeated sync is idempotent (config bytes identical)" (Bytes-Equal $beforeBytes (Get-Bytes $aConfigPath))
    Assert "repeated sync does not report config.json as changed" (@($idem.changed) -notcontains '.copilottools/config.json')
    Assert "repeated sync verification passes" ($idem.verification -eq 'passed')

    Write-Host "== 12. Config-merge verification checks the adapter result and preserves target-only settings =="
    $cSrc = New-SourceRepo -WithConfigMerge
    $created += $cSrc
    $cTgt = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $cTgt
    New-Item -ItemType Directory -Path (Join-Path $cTgt '.cfgmerge') -Force | Out-Null
    $cCfg = Join-Path $cTgt '.cfgmerge/config.toml'
    [System.IO.File]::WriteAllText($cCfg, "managed = 1`ntarget_only = yes`n", (New-Object System.Text.UTF8Encoding($false)))
    $cScope = @('.github/agents', '.cfgmerge')
    $cRes = Invoke-Sync -Source $cSrc -Target $cTgt -Scope $cScope
    $cText = Get-Content -LiteralPath $cCfg -Raw
    Assert "first sync succeeds with verification passed" ($cRes.verification -eq 'passed')
    Assert "verification reports zero mismatches" (@($cRes.mismatches).Count -eq 0)
    Assert "adapter-managed value was applied" ($cText -match '(?m)^managed = 2\r?$')
    Assert "target-only setting is preserved" ($cText -match 'target_only = yes')
    Assert "raw source-only key is not copied over the merged file" ($cText -notmatch 'new_key')
    $cBytes = Get-Bytes $cCfg
    $cRes2 = Invoke-Sync -Source $cSrc -Target $cTgt -Scope $cScope
    Assert "second sync is idempotent" ((Bytes-Equal $cBytes (Get-Bytes $cCfg)) -and @($cRes2.changed) -notcontains '.cfgmerge/config.toml')
    Assert "second sync verification passes with zero mismatches" ($cRes2.verification -eq 'passed' -and @($cRes2.mismatches).Count -eq 0)

    Write-Host "== 13. Post-sync adapters are scope-aware =="
    $bSrc = New-SourceRepo -WithEscapingAdapter
    $created += $bSrc
    $bTgt = New-TargetRepo -AgentsMdContent $noMarkers
    $created += $bTgt
    New-Item -ItemType Directory -Path (Join-Path $bTgt '.unrelated') -Force | Out-Null
    $victim = Join-Path $bTgt '.unrelated/victim.txt'
    Set-Content -LiteralPath $victim -Value 'keep me' -NoNewline
    $bRes = Invoke-Sync -Source $bSrc -Target $bTgt -Scope @('.github/agents')
    Assert "adapter for runtime assets outside the scope does not run" ($bRes.runtimePostSync.escaper.skipped -eq $true -and (Test-Path -LiteralPath $victim))
    $bRes = Invoke-Sync -Source $bSrc -Target $bTgt -Scope @('.github/agents', '.escaper')
    Assert "adapter whose changed paths escape the requested scope is skipped, not applied" ($bRes.runtimePostSync.escaper.skipped -eq $true -and @($bRes.runtimePostSync.escaper.outOfScopePaths) -contains '.unrelated/victim.txt')
    Assert "out-of-scope path was left untouched" (Test-Path -LiteralPath $victim)
    Assert "out-of-scope path is not reported as changed" (@($bRes.changed) -notcontains '.unrelated/victim.txt')
    $kRes = Invoke-Sync -Source $bSrc -Target $bTgt -Scope @('.github/agents')
    Assert "fixture runtime adapter is skipped for a scope that does not cover .fixture-runtime/commands" ($kRes.runtimePostSync.'fixture-runtime'.skipped -eq $true)

    Write-Host "== 14. Manifest adapter paths are confined to .github/scripts of the fetched source =="
    foreach ($evil in @('../outside.ps1', '/etc/outside.ps1', 'C:/Windows/outside.ps1', '.github/other/outside.ps1', '.github/scripts/../../outside.ps1')) {
        $gSrc = New-SourceRepo -EvilAdapterPath $evil
        $created += $gSrc
        $gTgt = New-TargetRepo -AgentsMdContent $noMarkers
        $created += $gTgt
        $gThrew = $false
        try { & $script -SourceRepo $gSrc -SourceRef 'main' -TargetRoot $gTgt -Scope @('.github/agents', '.evil') 2>$null | Out-Null } catch { $gThrew = $true }
        Assert "adapter path '$evil' is rejected" ($gThrew -or $LASTEXITCODE -ne 0)
    }

    Write-Host "== 15. Runtime layer hash is deterministic across hosts =="
    . (Join-Path $repoRoot '.github/scripts/runtime-layer-hash.ps1')
    $hashRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('layer-hash-' + [System.Guid]::NewGuid().ToString('N'))
    $created += $hashRoot
    $hashFiles = [ordered]@{
        '.github/runtime-registry.json' = '{"a":1}'
        '.github/scripts/runtime/z/b.ps1' = 'b'
        '.github/scripts/runtime/a.ps1' = 'a'
        '.github/schemas/s.json' = '{}'
    }
    foreach ($rel in $hashFiles.Keys) {
        $full = Join-Path $hashRoot $rel
        New-Item -ItemType Directory -Path (Split-Path -Parent $full) -Force | Out-Null
        [System.IO.File]::WriteAllText($full, $hashFiles[$rel], (New-Object System.Text.UTF8Encoding($false)))
    }
    $ordered = [string[]]@($hashFiles.Keys)
    [Array]::Sort($ordered, [System.StringComparer]::Ordinal)
    $expectedMaterial = ''
    foreach ($rel in $ordered) {
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $hashRoot $rel))
        $fileHash = (([System.Security.Cryptography.SHA256]::HashData($bytes)) | ForEach-Object { $_.ToString('X2') }) -join ''
        $expectedMaterial += $rel + ':' + $fileHash + "`n"
    }
    $expectedHash = (([System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($expectedMaterial))) | ForEach-Object { $_.ToString('x2') }) -join ''
    Assert "hash equals an independently built LF-separated, '/'-relative, ordinal-sorted material" ((Get-RuntimeLayerHash -Root $hashRoot) -eq $expectedHash)
    Assert "hash is stable across repeated calls" ((Get-RuntimeLayerHash -Root $hashRoot) -eq (Get-RuntimeLayerHash -Root $hashRoot))
    $hashSource = Get-Content -LiteralPath (Join-Path $repoRoot '.github/scripts/runtime-layer-hash.ps1') -Raw
    Assert "hash material never uses the platform newline" ($hashSource -notmatch 'Environment\]::NewLine')
    Write-Host "== 16. Failed git rm --cached must not be reported as an untrack success =="
    if ($IsWindows) {
        $failTgt = New-TargetRepo -AgentsMdContent $noMarkers
        $created += $failTgt
        $shimDir = Join-Path ([IO.Path]::GetTempPath()) ("git-rm-fail-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $shimDir -Force | Out-Null
        $created += $shimDir
        $realGit = (Get-Command git -CommandType Application).Source
        $marker = Join-Path $shimDir "rm-was-called.txt"
        $cmdLines = @("@echo off", "if /I ""%3""==""rm"" (echo called>""%SYNC_GIT_RM_MARKER%"" & exit /b 17)", ('"' + $realGit + '" %*'))
        Set-Content -LiteralPath (Join-Path $shimDir "git.cmd") -Value ($cmdLines -join [Environment]::NewLine) -Encoding ASCII
        $originalTestPath = $env:PATH
        $env:SYNC_GIT_RM_MARKER = $marker
        $failed = $false
        try {
            $env:PATH = "$shimDir;$originalTestPath"
            & $script -SourceRepo $src -SourceRef main -TargetRoot $failTgt -Scope @(".github/agents") 2>$null | Out-Null
        } catch { $failed = $true }
        finally { $env:PATH = $originalTestPath; $env:SYNC_GIT_RM_MARKER = $null }
        Assert "git rm failure fixture was reached" (Test-Path -LiteralPath $marker)
        Assert "failed index removal aborts sync" $failed
        $stillTracked = @(git -C $failTgt ls-files -- .github/agents/example.agent.md)
        Assert "failed removal leaves tracked file visible, not falsely untracked" ($stillTracked.Count -eq 1)
        $recovered = Invoke-Sync -Source $src -Target $failTgt -Scope @(".github/agents")
        Assert "retry succeeds after git untrack failure" ($recovered.verification -eq "passed")
        Assert "retry actually removes the file from the git index" (@(git -C $failTgt ls-files -- .github/agents/example.agent.md).Count -eq 0)
    } else { Write-Host "SKIP Windows git.cmd injection fixture on non-Windows host" }
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

param(
    [string]$SourceRepo = 'https://github.com/zabotinegor/CopilotTools.git',
    [string]$SourceRef = 'main',
    [string]$TargetRoot = (Get-Location).Path,
    [string[]]$Scope = @('.github/agents', '.github/skills', '.github/tools', '.github/scripts', '.github/hooks', '.githooks', '.claude/commands', '.claude/settings.json', '.opencode/commands', '.opencode/agents', '.opencode/plugins', 'opencode.jsonc', '.kilo/agents', '.kilo/commands', '.kilo/plugins', 'kilo.jsonc', '.agents/skills', '.codex/agents', '.codex/config.toml', '.github/runtime-parity.json', '.github/runtime-registry.json', '.github/copilottools', '.github/schemas', '.mcp.json', '.copilottools'),
    [string[]]$PreservePattern = @('agent-sync', 'sync-agent-assets', 'asset-lock.json'),
    [string[]]$ExcludeGitignorePattern = @('agent-sync', 'sync-agent-assets', '.github/hooks/', '.githooks/', 'protect-agent-git-command'),
    [string[]]$MergeJsonPaths = @('.claude/settings.json', '.mcp.json'),
    [switch]$DryRun,
    [switch]$AllowRootMdSync
)

$ErrorActionPreference = 'Stop'
# ReviewBot is a separately deployed service. Its service-local assets must never
# be mirrored into client repositories, even when they sit below a broad sync root.
# These are anchored service-path patterns, not a generic review/bot blacklist.
$ExcludeSyncPathPattern = @('(^|/)reviewbot(?:/|[-_.])')
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "sync-agent-assets.ps1 requires PowerShell 7+ (pwsh); it uses APIs missing from Windows PowerShell $($PSVersionTable.PSVersion). Re-run with: pwsh -NoProfile -File .github/scripts/sync-agent-assets.ps1"
}
. (Join-Path $PSScriptRoot 'runtime-layer-hash.ps1')

# Every helper this entrypoint dot-sources is needed to run it at all, so it must stay
# trackable in client repos (a fresh clone cannot obtain it by syncing). Derive the list
# from this script's own dot-source statements and exempt it from the managed .gitignore block.
$bootstrapHelpers = @([regex]::Matches((Get-Content -LiteralPath $PSCommandPath -Raw), '(?m)^\.\s+\(Join-Path \$PSScriptRoot ''([^'']+\.ps1)''\)') | ForEach-Object { $_.Groups[1].Value })
$ExcludeGitignorePattern = @($ExcludeGitignorePattern) + $bootstrapHelpers

function Invoke-ExternalCommand {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousEap
    }
    if ($exitCode -ne 0) {
        $details = (($output | Out-String).Trim())
        if ([string]::IsNullOrWhiteSpace($details)) {
            throw "$FailureMessage ExitCode=$exitCode."
        }

        throw "$FailureMessage ExitCode=$exitCode. Output: $details"
    }

    return @($output)
}

function Write-AllLinesUtf8WithRetry {
    param(
        [string]$Path,
        [string[]]$Lines,
        [int]$MaxAttempts = 5,
        [int]$DelayMs = 150
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    $content = [string]::Join("`r`n", $Lines)
    if ($Lines.Count -gt 0) {
        $content += "`r`n"
    }

    $attempt = 0
    while ($attempt -lt $MaxAttempts) {
        try {
            $encoding = New-Object System.Text.UTF8Encoding($false)
            [System.IO.File]::WriteAllText($Path, $content, $encoding)
            return
        }
        catch [System.IO.IOException] {
            $attempt++
            if ($attempt -ge $MaxAttempts) {
                throw
            }
            Start-Sleep -Milliseconds $DelayMs
        }
    }
}

function Get-TomlTableHeaderName {
    param([string]$Line)

    # Recognizes a table header tolerant of surrounding whitespace and a
    # trailing comment (`[agents] # note`). Returns the bare table name, or
    # $null when the line is not a header.
    if ($Line -match '^\s*\[\[?\s*([^\[\]#]+?)\s*\]\]?\s*(#.*)?$') { return $Matches[1] }
    return $null
}

function Merge-CodexProjectConfig {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$TargetPath
    )

    $sourceLines = @(Get-Content -LiteralPath $SourcePath -Encoding UTF8)
    $targetLines = @(Get-Content -LiteralPath $TargetPath -Encoding UTF8)
    $sourceConcurrency = @($sourceLines | Where-Object { $_ -match '^\s*max_concurrent_threads_per_session\s*=\s*' })[0]
    if ([string]::IsNullOrWhiteSpace($sourceConcurrency)) {
        Write-Warning 'Codex source config has no max_concurrent_threads_per_session setting; leaving the target concurrency setting unchanged.'
    }

    # Remove the obsolete root-level setting and update/add it only in [agents].
    $result = New-Object System.Collections.Generic.List[string]
    $section = ''
    $agentsSeen = $false
    $agentsConcurrencySeen = $false
    foreach ($line in $targetLines) {
        $headerName = Get-TomlTableHeaderName -Line $line
        if ($null -ne $headerName) {
            if ($section -eq 'agents' -and -not $agentsConcurrencySeen -and $sourceConcurrency) {
                $result.Add($sourceConcurrency)
                $agentsConcurrencySeen = $true
            }
            $section = $headerName
            if ($section -eq 'agents') { $agentsSeen = $true }
        }
        if ($sourceConcurrency -and $line -match '^\s*max_concurrent_threads_per_session\s*=\s*') {
            if ($section -eq 'agents' -and -not $agentsConcurrencySeen) {
                $result.Add($sourceConcurrency)
                $agentsConcurrencySeen = $true
            }
            continue
        }
        $result.Add($line)
    }
    if ($section -eq 'agents' -and -not $agentsConcurrencySeen -and $sourceConcurrency) { $result.Add($sourceConcurrency) }
    if (-not $agentsSeen -and $sourceConcurrency) {
        if ($result.Count -gt 0 -and $result[$result.Count - 1] -ne '') { $result.Add('') }
        $result.Add('[agents]')
        $result.Add($sourceConcurrency)
    }

    # Replace only managed MCP server sections. Unrelated target MCP servers
    # and all other project settings remain untouched.
    foreach ($managed in @('mcp_servers.fetch', 'mcp_servers.clickup', 'mcp_servers.playwright')) {
        $sourceStart = -1
        for ($i = 0; $i -lt $sourceLines.Count; $i++) { if ((Get-TomlTableHeaderName -Line $sourceLines[$i]) -eq $managed -and $sourceLines[$i] -notmatch '^\s*\[\[') { $sourceStart = $i; break } }
        if ($sourceStart -lt 0) { throw "Codex source config is missing [$managed]." }
        $sourceEnd = $sourceLines.Count
        for ($i = $sourceStart + 1; $i -lt $sourceLines.Count; $i++) { if ($null -ne (Get-TomlTableHeaderName -Line $sourceLines[$i])) { $sourceEnd = $i; break } }
        $block = @($sourceLines[$sourceStart..($sourceEnd - 1)])
        $filtered = New-Object System.Collections.Generic.List[string]
        for ($i = 0; $i -lt $result.Count; $i++) {
            if ((Get-TomlTableHeaderName -Line $result[$i]) -eq $managed -and $result[$i] -notmatch '^\s*\[\[') {
                $i++
                while ($i -lt $result.Count -and $null -eq (Get-TomlTableHeaderName -Line $result[$i])) { $i++ }
                $i--
                continue
            }
            $filtered.Add($result[$i])
        }
        while ($filtered.Count -gt 0 -and $filtered[$filtered.Count - 1] -eq '') { $filtered.RemoveAt($filtered.Count - 1) }
        if ($filtered.Count -gt 0) { $filtered.Add('') }
        foreach ($line in $block) { $filtered.Add($line) }
        $result = $filtered
    }
    return @($result)
}

function Get-SectionFromSourceFile {
    param(
        [string]$SourcePath,
        [string]$BeginMarker = '<!-- BEGIN AGENT SYNC -->',
        [string]$EndMarker = '<!-- END AGENT SYNC -->'
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        throw "Source section file '$SourcePath' does not exist."
    }

    # Explicit UTF-8: under Windows PowerShell 5.1 the default is ANSI, which
    # mangles non-ASCII content (em-dashes) in BOM-less UTF-8 markdown and made
    # the marker-injection verify fail with mojibake in the target file.
    $sourceLines = @(Get-Content -LiteralPath $SourcePath -Encoding UTF8)
    $beginIdx = $sourceLines.IndexOf($BeginMarker)
    $endIdx = $sourceLines.IndexOf($EndMarker)
    if ($beginIdx -ge 0 -and $endIdx -gt $beginIdx) {
        if ($endIdx -eq $beginIdx + 1) {
            return @()
        }

        return @($sourceLines[($beginIdx + 1)..($endIdx - 1)])
    }

    return $sourceLines
}

function Set-FileSectionByMarkers {
    param(
        [string]$TargetPath,
        [string]$SourceSectionPath,
        [string]$BeginMarker = '<!-- BEGIN AGENT SYNC -->',
        [string]$EndMarker = '<!-- END AGENT SYNC -->',
        [switch]$DryRun
    )

    $sourceSection = @(Get-SectionFromSourceFile -SourcePath $SourceSectionPath -BeginMarker $BeginMarker -EndMarker $EndMarker)

    if (-not (Test-Path -LiteralPath $TargetPath)) {
        $newLines = @($BeginMarker)
        $newLines += $sourceSection
        $newLines += $EndMarker
        if (-not $DryRun) {
            Write-AllLinesUtf8WithRetry -Path $TargetPath -Lines $newLines
        }

        return [pscustomobject]@{
            changed = $true
            action = 'created-file'
        }
    }

    $targetLines = @(Get-Content -LiteralPath $TargetPath -Encoding UTF8)
    $beginIdx = $targetLines.IndexOf($BeginMarker)
    $endIdx = $targetLines.IndexOf($EndMarker)

    # Migration: a target last synced before the runtime-agnostic marker
    # rename ('<!-- BEGIN/END COPILOT SYNC -->' -> '.../AGENT SYNC
    # -->') still carries the OLD marker pair and no new one. Searching only
    # for the new markers found nothing, treated the file as unmarked, and hit
    # the 'added-markers' branch below - appending a SECOND, complete
    # governance section while the legacy-marked one stayed in place untouched
    # and increasingly stale on every subsequent sync. Fall back to the legacy
    # markers as the existing section to replace when the new ones are not
    # present; using them as $beginIdx/$endIdx here means the 'replaced-section'
    # branch below writes the section back out under the NEW marker names, so
    # migration happens in this same pass.
    if ($beginIdx -lt 0 -or $endIdx -lt 0 -or $endIdx -le $beginIdx) {
        $legacyBeginMarker = '<!-- BEGIN COPILOT SYNC -->'
        $legacyEndMarker = '<!-- END COPILOT SYNC -->'
        $legacyBeginIdx = $targetLines.IndexOf($legacyBeginMarker)
        $legacyEndIdx = $targetLines.IndexOf($legacyEndMarker)
        if ($legacyBeginIdx -ge 0 -and $legacyEndIdx -gt $legacyBeginIdx) {
            $beginIdx = $legacyBeginIdx
            $endIdx = $legacyEndIdx
        }
    }

    if ($beginIdx -lt 0 -or $endIdx -lt 0 -or $endIdx -le $beginIdx) {
        $newLines = @($targetLines)
        if ($newLines.Count -gt 0 -and $newLines[$newLines.Count - 1] -ne '') {
            $newLines += ''
        }
        $newLines += $BeginMarker
        $newLines += $sourceSection
        $newLines += $EndMarker
        $action = 'added-markers'
    }
    else {
        $prefix = @($targetLines[0..$beginIdx])
        $suffix = @($targetLines[$endIdx..($targetLines.Count - 1)])
        # Normalize the marker lines themselves to the canonical (new) names -
        # a no-op when $beginIdx/$endIdx already pointed at $BeginMarker/
        # $EndMarker, and the migration step when they came from the legacy
        # fallback above. Doing it here, in the same write, means a
        # legacy-marked file only ever needs migrating once.
        $prefix[$prefix.Count - 1] = $BeginMarker
        $suffix[0] = $EndMarker
        $newLines = @()
        $newLines += $prefix
        $newLines += $sourceSection
        $newLines += $suffix
        $action = 'replaced-section'
    }

    $before = [string]::Join("`n", $targetLines)
    $after = [string]::Join("`n", $newLines)
    $changed = $before -ne $after

    if ($changed -and -not $DryRun) {
        Write-AllLinesUtf8WithRetry -Path $TargetPath -Lines $newLines
    }

    $actionValue = if ($changed) { $action } else { 'no-change' }
    return [pscustomobject]@{
        changed = $changed
        action = $actionValue
    }
}

function Convert-ToRepoRelativePath {
    param([string]$Path)
    return ($Path -replace '\\', '/').TrimStart('/')
}

function Get-RelativePath {
    param(
        [string]$Root,
        [string]$Path
    )

    $rootFullPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $pathFullPath = [System.IO.Path]::GetFullPath($Path)

    if (-not $pathFullPath.StartsWith($rootFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path '$Path' is outside root '$Root'."
    }

    return $pathFullPath.Substring($rootFullPath.Length)
}

function Get-RelativeFileMap {
    param(
        [string]$Root,
        [string[]]$ScopePaths,
        [string[]]$ExcludePattern = @()
    )

    $map = @{}
    foreach ($scopePath in $ScopePaths) {
        $absoluteScope = Join-Path $Root $scopePath
        if (-not (Test-Path -LiteralPath $absoluteScope)) {
            continue
        }

        if (Test-Path -LiteralPath $absoluteScope -PathType Leaf) {
            $relative = Convert-ToRepoRelativePath -Path (Get-RelativePath -Root $Root -Path $absoluteScope)
            if (@($ExcludePattern | Where-Object { $relative -match $_ }).Count -eq 0) {
                $map[$relative] = $absoluteScope
            }
        } else {
            Get-ChildItem -LiteralPath $absoluteScope -File -Recurse | ForEach-Object {
                $relative = Convert-ToRepoRelativePath -Path (Get-RelativePath -Root $Root -Path $_.FullName)
                if (@($ExcludePattern | Where-Object { $relative -match $_ }).Count -eq 0) {
                    $map[$relative] = $_.FullName
                }
            }
        }
    }

    return $map
}

function Set-ExactGitignoreEntries {
    param(
        [string]$Root,
        [string[]]$RelativePaths,
        [string[]]$ExcludePattern
    )

    $gitignorePath = Join-Path $Root '.gitignore'
    $beginMarker = '# BEGIN synced-agent-assets'
    $endMarker = '# END synced-agent-assets'
    $blockedPatterns = @('/.github/agents/**', '/.github/skills/**', '/.github/tools/**', '/.github/scripts/**', '/.opencode/agents/**', '/.opencode/commands/**', '/.opencode/plugins/**', '/.kilo/agents/**', '/.kilo/commands/**', '/.kilo/plugins/**', '/.agents/skills/**', '/.codex/agents/**')
    $beginMarkers = @('# BEGIN synced-agent-assets', '# BEGIN synced-copilot-assets')
    $endMarkers = @('# END synced-agent-assets', '# END synced-copilot-assets')
    $existing = @()
    if (Test-Path -LiteralPath $gitignorePath) {
        $existing = @(Get-Content -LiteralPath $gitignorePath -Encoding UTF8)
    }

    foreach ($blockedPattern in $blockedPatterns) {
        if ($existing -contains $blockedPattern) {
            throw ".gitignore contains blocked broad pattern '$blockedPattern'. Use exact synced file paths only."
        }
    }

    $entries = $RelativePaths |
        Where-Object {
            $relativePath = $_
            (@($ExcludePattern | Where-Object { $relativePath -match [regex]::Escape($_) }).Count -eq 0)
        } |
        ForEach-Object { '/' + (Convert-ToRepoRelativePath -Path $_) } |
        Sort-Object -Unique

    $next = New-Object System.Collections.Generic.List[string]
    $insideManagedBlock = $false
    foreach ($line in $existing) {
        if ($line -in $beginMarkers) {
            $insideManagedBlock = $true
            # Drop the separator blank line(s) a previous sync added before the
            # block, so re-syncs do not accumulate blank lines mid-file.
            while ($next.Count -gt 0 -and $next[$next.Count - 1] -eq '') {
                $next.RemoveAt($next.Count - 1)
            }
            continue
        }

        if ($insideManagedBlock) {
            if ($line -in $endMarkers) {
                $insideManagedBlock = $false
            }
            continue
        }

        $next.Add($line)
    }

    if (-not $DryRun) {
        while ($next.Count -gt 0 -and $next[$next.Count - 1] -eq '') {
            $next.RemoveAt($next.Count - 1)
        }

        $next.Add('')
        $next.Add($beginMarker)
        foreach ($entry in $entries) {
            $next.Add($entry)
        }
        $next.Add($endMarker)

        Write-AllLinesUtf8WithRetry -Path $gitignorePath -Lines @($next)
    }

    return $entries.Count
}

function Set-TransientCopilotArtifactGitignoreEntries {
    param([string]$Root)

    $gitignorePath = Join-Path $Root '.gitignore'
    $beginMarker = '# BEGIN transient-agent-artifacts'
    $endMarker = '# END transient-agent-artifacts'
    $beginMarkers = @('# BEGIN transient-agent-artifacts', '# BEGIN transient-copilot-artifacts')
    $endMarkers = @('# END transient-agent-artifacts', '# END transient-copilot-artifacts')
    $entries = @(
        '*_HANDOFF*.md',
        '*_PROMPT*.md',
        '*_PROMT*.md',
        'CODE_REVIEW_HANDOFF_*.md',
        '/.sdlc/status.json',
        '**/.sdlc/status.json',
        '/.sdlc/operations/',
        '/.sdlc/operations/**',
        '**/.sdlc/operations/**',
        '/.claude/launch.json',
        '/.sdlc/logs/',
        '/.sdlc/logs/**',
        '**/.sdlc/logs/**',
        '/.sdlc.lock',
        '**/.sdlc.lock',
        # ClickUp runtime state. The token especially: it is a workspace-wide
        # personal credential, and a repo that ignores status.json but not this
        # commits it on the first 'git add .sdlc'.
        '/.sdlc/clickup-config.json',
        '**/.sdlc/clickup-config.json',
        '/.sdlc/clickup-config.template.json',
        '**/.sdlc/clickup-config.template.json',
        '/.sdlc/clickup-token',
        '**/.sdlc/clickup-token',
        '/.sdlc/clickup-migration-map.json',
        '**/.sdlc/clickup-migration-map.json',
        # tools-fix runtime state: where this machine's CopilotTools checkout
        # lives, and the resume records for in-flight fixes. Both are per-machine
        # and per-session, so they are noise in every client repo that syncs.
        # The directory form is what background dispatch writes (one file per
        # issue, since two open repairs in one session is ordinary now); the flat
        # file is the pre-dispatch layout, kept so a repo synced before the change
        # does not start tracking a leftover.
        '/.sdlc/agenttools-source.json',
        '**/.sdlc/agenttools-source.json',
        # Legacy pre-rename filename. Kept alongside the renamed pattern above so
        # a target still holding the old marker file from before the rename does
        # not have it surface to Git after a sync during the migration window.
        '/.sdlc/copilottools-source.json',
        '**/.sdlc/copilottools-source.json',
        '/.sdlc/tools-fix/',
        '**/.sdlc/tools-fix/',
        '/.sdlc/tools-fix.json',
        '**/.sdlc/tools-fix.json',
        '/.claude/settings.local.json',
        '**/.claude/settings.local.json',
        # The lock every non-dry-run sync writes (revision + runtime-layer hash).
        # It is generated per machine and PreservePattern keeps later syncs from
        # deleting it, so without an ignore rule it is a permanent untracked file.
        # Only the lock is ignored: .copilottools/config.json is a hand-authored
        # per-repo setting (claude_session_recovery_enabled) and stays trackable.
        '/.copilottools/asset-lock.json'
    )

    $existing = @()
    if (Test-Path -LiteralPath $gitignorePath) {
        $existing = @(Get-Content -LiteralPath $gitignorePath -Encoding UTF8)
    }

    $next = New-Object System.Collections.Generic.List[string]
    $insideManagedBlock = $false
    foreach ($line in $existing) {
        if ($line -in $beginMarkers) {
            $insideManagedBlock = $true
            # Drop the separator blank line(s) a previous sync added before the
            # block, so re-syncs do not accumulate blank lines mid-file.
            while ($next.Count -gt 0 -and $next[$next.Count - 1] -eq '') {
                $next.RemoveAt($next.Count - 1)
            }
            continue
        }

        if ($insideManagedBlock) {
            if ($line -in $endMarkers) {
                $insideManagedBlock = $false
            }
            continue
        }

        $next.Add($line)
    }

    if (-not $DryRun) {
        while ($next.Count -gt 0 -and $next[$next.Count - 1] -eq '') {
            $next.RemoveAt($next.Count - 1)
        }

        $next.Add('')
        $next.Add($beginMarker)
        foreach ($entry in $entries) {
            $next.Add($entry)
        }
        $next.Add($endMarker)

        Write-AllLinesUtf8WithRetry -Path $gitignorePath -Lines @($next)
    }

    return $entries.Count
}

function Get-ForbiddenCopilotArtifacts {
    param([string]$Root)

    $patterns = @(
        '*_HANDOFF*.md',
        '*_PROMPT*.md',
        '*_PROMT*.md',
        'CODE_REVIEW_HANDOFF_*.md'
    )

    $files = New-Object System.Collections.Generic.List[string]
    foreach ($pattern in $patterns) {
        Get-ChildItem -LiteralPath $Root -File -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
            Where-Object {
                $_.FullName -notmatch '\\\.git\\' -and $_.FullName -notmatch '\\node_modules\\'
            } |
            ForEach-Object {
                $relative = Get-RelativePath -Root $Root -Path $_.FullName
                $files.Add((Convert-ToRepoRelativePath -Path $relative))
            }
    }

    return @($files | Sort-Object -Unique)
}

function Get-NestedSdlcStatusFiles {
    param([string]$Root)

    $rootStatus = [System.IO.Path]::GetFullPath((Join-Path $Root '.sdlc/status.json'))
    $files = New-Object System.Collections.Generic.List[string]
    Get-ChildItem -LiteralPath $Root -File -Recurse -Filter 'status.json' -ErrorAction SilentlyContinue |
        Where-Object {
            $_.FullName -notmatch '\\\.git\\' -and
            $_.FullName -notmatch '\\node_modules\\' -and
            ($_.FullName -replace '/', '\') -match '\\\.sdlc\\status\.json$' -and
            ([System.IO.Path]::GetFullPath($_.FullName) -ne $rootStatus)
        } |
        ForEach-Object {
            $relative = Get-RelativePath -Root $Root -Path $_.FullName
            $files.Add((Convert-ToRepoRelativePath -Path $relative))
        }

    return @($files | Sort-Object -Unique)
}

function Merge-PsObjects {
    param(
        [pscustomobject]$Source,
        [pscustomobject]$Target
    )
    $changed = $false
    foreach ($prop in $Source.PSObject.Properties) {
        $existing = $Target.PSObject.Properties[$prop.Name]
        if ($null -eq $existing) {
            $Target | Add-Member -MemberType NoteProperty -Name $prop.Name -Value $prop.Value
            $changed = $true
        } elseif ($prop.Value -is [pscustomobject] -and $existing.Value -is [pscustomobject]) {
            if (Merge-PsObjects -Source $prop.Value -Target $existing.Value) {
                $changed = $true
            }
        } elseif (
            $prop.Name -in @('PreToolUse', 'PostToolUse', 'PostToolUseFailure', 'SessionStart', 'SessionEnd') -and
            $prop.Value -is [System.Collections.IEnumerable] -and
            $prop.Value -isnot [string] -and
            $existing.Value -is [System.Collections.IEnumerable] -and
            $existing.Value -isnot [string]
        ) {
            $combined = New-Object System.Collections.Generic.List[object]
            $seen = New-Object System.Collections.Generic.HashSet[string]
            foreach ($item in @($existing.Value) + @($prop.Value)) {
                if ($null -eq $item) { $key = 'null' } else { $key = ConvertTo-Json -InputObject $item -Depth 10 -Compress }
                if ($seen.Add($key)) {
                    $combined.Add($item)
                }
            }
            if ($combined.Count -ne @($existing.Value).Count) {
                $existing.Value = [object[]]$combined.ToArray()
                $changed = $true
            }
        } else {
            $existingJson = if ($null -eq $existing.Value) { 'null' } else { ConvertTo-Json -InputObject $existing.Value -Depth 10 -Compress }
            $propJson = if ($null -eq $prop.Value) { 'null' } else { ConvertTo-Json -InputObject $prop.Value -Depth 10 -Compress }
            if ($existingJson -ne $propJson) {
                $existing.Value = $prop.Value
                $changed = $true
            }
        }
    }
    return $changed
}

function Get-HookCommands {
    param([pscustomobject]$Settings)

    # Comma-wrap every return: a HashSet written bare to the output stream is
    # enumerated by the pipeline, so the caller gets loose strings instead of the
    # set. PowerShell's binder does coerce that array back into a
    # HashSet[string] for -SourceCommands, but an EMPTY set enumerates to nothing
    # and arrives as $null, and every non-empty case pays for a rebuilt copy.
    # Returning the set as a single object keeps the contract exact.
    $commands = New-Object System.Collections.Generic.HashSet[string]
    if ($null -eq $Settings -or $null -eq $Settings.hooks) { return , $commands }

    foreach ($event in @($Settings.hooks.PSObject.Properties)) {
        foreach ($entry in @($event.Value)) {
            if ($null -eq $entry -or $null -eq $entry.hooks) { continue }
            foreach ($hook in @($entry.hooks)) {
                $command = [string]$hook.command
                if (-not [string]::IsNullOrWhiteSpace($command)) { $commands.Add($command) | Out-Null }
            }
        }
    }

    return , $commands
}

function Remove-DeadHookEntries {
    param(
        [pscustomobject]$Settings,
        [string]$Root,
        [System.Collections.Generic.HashSet[string]]$SourceCommands,
        [System.Collections.Generic.HashSet[string]]$PendingDeletions
    )

    # Hook arrays are merged by union (see Merge-PsObjects) so client-added
    # hooks survive a sync. The cost is that a hook we RETIRE lives forever in
    # every repo that once synced it - and a hook whose script is gone still
    # runs, fails, and exits non-2, which the harness treats as a non-blocking
    # error. That is a hook which looks configured and guards nothing, the exact
    # failure mode that left the Bash-tool branch guard inert. A command naming
    # a '.github/scripts/' file that no longer exists is unambiguously dead:
    # that directory is mirror-synced with stale-file deletion, so the file was
    # either kept or deliberately removed.
    $removed = New-Object System.Collections.Generic.List[string]
    if ($null -eq $Settings -or $null -eq $Settings.hooks) { return $removed }

    foreach ($event in @($Settings.hooks.PSObject.Properties)) {
        $entries = @($event.Value)
        if ($entries.Count -eq 0) { continue }

        $keptEntries = New-Object System.Collections.Generic.List[object]
        $entryChanged = $false

        foreach ($entry in $entries) {
            if ($null -eq $entry -or $null -eq $entry.hooks) {
                $keptEntries.Add($entry)
                continue
            }

            $keptHooks = New-Object System.Collections.Generic.List[object]
            foreach ($hook in @($entry.hooks)) {
                $command = [string]$hook.command
                # A hook the SOURCE still declares is never retired, whatever
                # the target looks like. Pruning one would fight the very merge
                # that just added it: apply removes, verify re-adds, and the run
                # reports a mismatch on every sync. A source hook whose script
                # is missing is a sync-scope problem, and the existing mismatch
                # machinery is what should say so.
                $declaredBySource = $null -ne $SourceCommands -and $SourceCommands.Contains($command)
                $scriptRef = [regex]::Match($command, '(?i)\.github[\\/]scripts[\\/]([A-Za-z0-9._-]+)')
                if ($scriptRef.Success -and -not $declaredBySource) {
                    $scriptRelPath = ".github/scripts/$($scriptRef.Groups[1].Value)"
                    $scriptPath = Join-Path $Root (Join-Path '.github/scripts' $scriptRef.Groups[1].Value)
                    # Stale-file deletion runs BEFORE this merge in a real apply,
                    # but a dry run leaves the file on disk. Testing only the disk
                    # made -DryRun report "no dead hooks" for a hook whose script
                    # the very next real apply deletes - and that apply then also
                    # rewrote settings.json, a change the mandatory dry-run preview
                    # never showed. Treat a script already scheduled for deletion
                    # as gone so preview and apply agree.
                    $scheduledForDeletion = $null -ne $PendingDeletions -and $PendingDeletions.Contains($scriptRelPath)
                    if ($scheduledForDeletion -or -not (Test-Path -LiteralPath $scriptPath)) {
                        $removed.Add($command)
                        $entryChanged = $true
                        continue
                    }
                }
                $keptHooks.Add($hook)
            }

            if ($keptHooks.Count -eq 0) { continue }
            if ($keptHooks.Count -ne @($entry.hooks).Count) {
                $entry.hooks = [object[]]$keptHooks.ToArray()
            }
            $keptEntries.Add($entry)
        }

        if ($entryChanged) {
            $event.Value = [object[]]$keptEntries.ToArray()
        }
    }

    return $removed
}

function Remove-SessionRecoveryHooks {
    <#
    .SYNOPSIS
        Remove Claude session-recovery hooks from a settings object when
        claude_session_recovery_enabled is false.
    .DESCRIPTION
        Precisely targets only session-recovery hook commands:
        - init-session.ps1 (UserPromptUse)
        - check-session-before-tool.ps1 (PreToolUse)
        - update-session-from-hook.ps1 (Stop)
        - record-scheduler-evidence.ps1 (PostToolUse)
        Preserves all other hooks (git safety guards, client hooks, etc.).
    #>
    param(
        [pscustomobject]$Settings
    )

    $sessionHookScripts = @(
        'init-session.ps1',
        'check-session-before-tool.ps1',
        'update-session-from-hook.ps1',
        'record-scheduler-evidence.ps1'
    )

    $removed = New-Object System.Collections.Generic.List[string]
    if ($null -eq $Settings -or $null -eq $Settings.hooks) { return $removed }

    foreach ($event in @($Settings.hooks.PSObject.Properties)) {
        $entries = @($event.Value)
        if ($entries.Count -eq 0) { continue }

        $keptEntries = New-Object System.Collections.Generic.List[object]
        $entryChanged = $false

        foreach ($entry in $entries) {
            if ($null -eq $entry -or $null -eq $entry.hooks) {
                $keptEntries.Add($entry)
                continue
            }

            $keptHooks = New-Object System.Collections.Generic.List[object]
            foreach ($hook in @($entry.hooks)) {
                $command = [string]$hook.command
                $isSessionHook = $false
                foreach ($scriptName in $sessionHookScripts) {
                    if ($command -match [regex]::Escape($scriptName)) {
                        $isSessionHook = $true
                        break
                    }
                }
                if ($isSessionHook) {
                    $removed.Add("${($event.Name)}: $command")
                } else {
                    $keptHooks.Add($hook)
                }
            }

            if ($keptHooks.Count -eq 0) { continue }
            if ($keptHooks.Count -ne @($entry.hooks).Count) {
                $entry.hooks = [object[]]$keptHooks.ToArray()
            }
            $keptEntries.Add($entry)
        }

        if ($entryChanged -or $keptEntries.Count -ne @($entries).Count) {
            $event.Value = [object[]]$keptEntries.ToArray()
        }
    }

    # Clean up empty hook event arrays
    $emptyEvents = @()
    foreach ($event in @($Settings.hooks.PSObject.Properties)) {
        $entries = @($event.Value)
        if ($entries.Count -eq 0) {
            $emptyEvents += $event.Name
        }
    }
    foreach ($eventName in $emptyEvents) {
        $Settings.hooks.PSObject.Properties.Remove($eventName)
    }
    if ($Settings.hooks.PSObject.Properties.Count -eq 0) {
        $Settings.PSObject.Properties.Remove('hooks')
    }

    return $removed
}

function Add-SessionRecoveryHooks {
    <#
    .SYNOPSIS
        Add Claude session-recovery hooks to a settings object when
        claude_session_recovery_enabled is true.
    .DESCRIPTION
        Ensures exactly one copy of each session-recovery hook exists.
        Preserves all existing hooks (git safety guards, client hooks, etc.).
    #>
    param(
        [pscustomobject]$Settings
    )

    $added = New-Object System.Collections.Generic.List[string]
    if ($null -eq $Settings) { return $added }

    # Ensure hooks property exists
    if ($null -eq $Settings.hooks) {
        $Settings | Add-Member -MemberType NoteProperty -Name 'hooks' -Value ([pscustomobject]@{})
    }

    $sessionHooks = @(
        @{
            Event = 'UserPromptSubmit'
            Entry = @{
                hooks = @(
                    @{
                        type = 'command'
                        command = 'pwsh -NoProfile -NonInteractive -File "${CLAUDE_PROJECT_DIR}/.github/scripts/init-session.ps1"'
                    }
                )
            }
        },
        @{
            Event = 'Stop'
            Entry = @{
                hooks = @(
                    @{
                        type = 'command'
                        command = 'pwsh -NoProfile -NonInteractive -File "${CLAUDE_PROJECT_DIR}/.github/scripts/update-session-from-hook.ps1"'
                    }
                )
            }
        },
        @{
            Event = 'PostToolUse'
            Entry = @{
                hooks = @(
                    @{
                        type = 'command'
                        command = 'pwsh -NoProfile -NonInteractive -File "${CLAUDE_PROJECT_DIR}/.github/scripts/record-scheduler-evidence.ps1"'
                    }
                )
            }
        },
        @{
            Event = 'PreToolUse'
            Entry = @{
                hooks = @(
                    @{
                        type = 'command'
                        command = 'pwsh -NoProfile -NonInteractive -File "${CLAUDE_PROJECT_DIR}/.github/scripts/check-session-before-tool.ps1"'
                    }
                )
            }
        }
    )

    foreach ($hookDef in $sessionHooks) {
        $event = $hookDef.Event
        $newEntry = $hookDef.Entry

        # Check if this hook already exists
        $eventProp = $Settings.hooks.PSObject.Properties[$event]
        $existingEntries = @()
        if ($null -ne $eventProp) {
            $existingEntries = @($eventProp.Value)
        }

        $alreadyExists = $false
        foreach ($entry in $existingEntries) {
            if ($null -eq $entry -or $null -eq $entry.hooks) { continue }
            foreach ($hook in @($entry.hooks)) {
                $command = [string]$hook.command
                if ($command -match 'init-session\.ps1' -and $event -eq 'UserPromptSubmit') { $alreadyExists = $true; break }
                if ($command -match 'check-session-before-tool\.ps1' -and $event -eq 'PreToolUse') { $alreadyExists = $true; break }
                if ($command -match 'update-session-from-hook\.ps1' -and $event -eq 'Stop') { $alreadyExists = $true; break }
                if ($command -match 'record-scheduler-evidence\.ps1' -and $event -eq 'PostToolUse') { $alreadyExists = $true; break }
            }
            if ($alreadyExists) { break }
        }

        if (-not $alreadyExists) {
            $entriesList = New-Object System.Collections.Generic.List[object]
            foreach ($entry in $existingEntries) { $entriesList.Add($entry) }
            $entriesList.Add([pscustomobject]$newEntry)

            if ($null -eq $eventProp) {
                $Settings.hooks | Add-Member -MemberType NoteProperty -Name $event -Value ([object[]]$entriesList.ToArray())
            } else {
                $eventProp.Value = [object[]]$entriesList.ToArray()
            }
            $added.Add("${event}: $($newEntry.hooks[0].command)")
        }
    }

    return $added
}

function Merge-JsonSettings {
    param(
        [string]$SourcePath,
        [string]$TargetPath,
        [string]$RepoRoot,
        [System.Collections.Generic.HashSet[string]]$PendingDeletions,
        [switch]$DryRun
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        return [pscustomobject]@{ changed = $false; action = 'source-missing' }
    }

    $sourceRaw = Get-Content -LiteralPath $SourcePath -Raw -Encoding UTF8
    try {
        $sourceObj = $sourceRaw | ConvertFrom-Json
    } catch {
        Write-Warning "Failed to parse source JSON at '$SourcePath': $_"
        $sourceObj = [pscustomobject]@{}
    }
    if ($null -eq $sourceObj) { $sourceObj = [pscustomobject]@{} }

    $targetObj = [pscustomobject]@{}
    if (Test-Path -LiteralPath $TargetPath) {
        $targetContent = Get-Content -LiteralPath $TargetPath -Raw -Encoding UTF8
        if (-not [string]::IsNullOrWhiteSpace($targetContent)) {
            try {
                $targetObj = $targetContent | ConvertFrom-Json
            } catch {
                Write-Warning "Failed to parse target JSON at '$TargetPath': $_"
                $targetObj = [pscustomobject]@{}
            }
        }
    }
    if ($null -eq $targetObj) {
        $targetObj = [pscustomobject]@{}
    }

    $changed = Merge-PsObjects -Source $sourceObj -Target $targetObj

    $removedHooks = @()
    if (-not [string]::IsNullOrWhiteSpace($RepoRoot)) {
        $removedHooks = @(Remove-DeadHookEntries `
            -Settings $targetObj `
            -Root $RepoRoot `
            -SourceCommands (Get-HookCommands -Settings $sourceObj) `
            -PendingDeletions $PendingDeletions)
        if ($removedHooks.Count -gt 0) { $changed = $true }
    }

    if ($changed -and -not $DryRun) {
        $targetDir = Split-Path -Parent $TargetPath
        if (-not [string]::IsNullOrWhiteSpace($targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        $json = ConvertTo-Json -InputObject $targetObj -Depth 10
        $encoding = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($TargetPath, ($json + "`r`n"), $encoding)
    }

    $actionValue = if ($changed) { 'merged' } else { 'no-change' }
    return [pscustomobject]@{
        changed = $changed
        action = $actionValue
        removedDeadHooks = $removedHooks
    }
}

function Set-RepositoryGitHooksPath {
    param(
        [string]$Root,
        [string]$HooksPath = '.githooks',
        [switch]$DryRun
    )

    $gitDir = $null
    $previousEapGit = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $gitDir = ((git -C $Root rev-parse --git-dir 2>$null) | Out-String).Trim()
    }
    finally {
        $ErrorActionPreference = $previousEapGit
    }
    if ([string]::IsNullOrWhiteSpace($gitDir)) {
        return [pscustomobject]@{
            changed = $false
            status = 'not-a-git-worktree'
            hooksPath = $null
        }
    }

    $current = ''
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $current = ((git -C $Root config --local --get core.hooksPath 2>$null) | Out-String).Trim()
    }
    finally {
        $ErrorActionPreference = $previousEap
    }

    $changed = $current -ne $HooksPath
    if ($changed -and -not $DryRun) {
        Invoke-ExternalCommand `
            -FilePath 'git' `
            -Arguments @('-C', $Root, 'config', '--local', 'core.hooksPath', $HooksPath) `
            -FailureMessage 'Unable to configure repository Git hooks path.' | Out-Null
    }

    if ($changed -and $DryRun) { $statusValue = 'would-configure' } elseif ($changed) { $statusValue = 'configured' } else { $statusValue = 'already-configured' }
    return [pscustomobject]@{
        changed = $changed
        status = $statusValue
        hooksPath = $HooksPath
    }
}

$targetRootResolved = (Resolve-Path -LiteralPath $TargetRoot).Path
if (Test-Path -LiteralPath (Join-Path $targetRootResolved '.agenttools-source')) {
    throw "Target contains source-only marker '.agenttools-source'. Remove it before syncing client-repository guards."
}

$targetBranch = ''
$targetIsGitWorktree = $false
$previousEap = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $targetIsGitWorktree = ((git -C $targetRootResolved rev-parse --is-inside-work-tree 2>$null) | Out-String).Trim() -eq 'true'
    if ($targetIsGitWorktree) {
        $targetBranch = ((git -C $targetRootResolved branch --show-current 2>$null) | Out-String).Trim()
    }
}
finally {
    $ErrorActionPreference = $previousEap
}

if (-not $DryRun) {
    if (-not $targetIsGitWorktree) {
        throw 'Target must be a Git worktree before applying synchronized agent assets.'
    }
    if ([string]::IsNullOrWhiteSpace($targetBranch)) {
        throw 'Target must have a checked-out branch before applying synchronized agent assets.'
    }
    # No branch-protection gate here on purpose: sync only writes gitignored
    # working-tree files and never commits, stages, or pushes (see agent-sync
    # SKILL.md), so it must apply on whatever branch is currently checked out
    # -- including main/dev -- rather than forcing a branch switch or creation.
}

$normalizedMergeJsonPaths = @(
    $MergeJsonPaths |
        ForEach-Object { $_ -split ',' } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { Convert-ToRepoRelativePath -Path $_.Trim() } |
        Select-Object -Unique
)
$normalizedScope = @(
    $Scope |
        ForEach-Object { $_ -split ',' } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { Convert-ToRepoRelativePath -Path $_.Trim() }
)
$protectedRootMdPaths = @(
    'AGENTS.md',
    'README.md',
    'AGENTS.local.md',
    'README.local.md'
)

$requestedProtectedRootMd = @(
    $normalizedScope |
        Where-Object {
            $scopePath = $_
            @($protectedRootMdPaths | Where-Object { $_ -ieq $scopePath }).Count -gt 0
        }
)

if ($requestedProtectedRootMd.Count -gt 0 -and -not $AllowRootMdSync) {
    $blocked = ($requestedProtectedRootMd | Sort-Object -Unique) -join ', '
    throw "Sync scope contains protected root markdown path(s): $blocked. Root markdown sync is blocked by default. Re-run with -AllowRootMdSync only when explicitly approved."
}

$remoteRef = "refs/heads/$SourceRef"
$lsRemote = Invoke-ExternalCommand -FilePath 'git' -Arguments @('ls-remote', $SourceRepo, $remoteRef) -FailureMessage "Unable to resolve $SourceRepo $remoteRef."
if (-not $lsRemote) {
    throw "Unable to resolve $SourceRepo $remoteRef"
}

$sourceCommit = ($lsRemote -split "\s+")[0]
if (-not $sourceCommit -or $sourceCommit.Length -lt 40) {
    throw "Unable to parse source commit from git ls-remote output."
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("agenttools-sync-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    Invoke-ExternalCommand -FilePath 'git' -Arguments @('clone', '--quiet', '--no-checkout', '--depth', '1', '--branch', $SourceRef, $SourceRepo, $tempRoot) -FailureMessage 'git clone failed.' | Out-Null
    Invoke-ExternalCommand -FilePath 'git' -Arguments @('-C', $tempRoot, 'checkout', '--quiet', $sourceCommit) -FailureMessage 'git checkout failed.' | Out-Null

    $sourceFiles = Get-RelativeFileMap -Root $tempRoot -ScopePaths $normalizedScope -ExcludePattern $ExcludeSyncPathPattern
    $targetFiles = Get-RelativeFileMap -Root $targetRootResolved -ScopePaths $normalizedScope

    if ($sourceFiles.Count -eq 0) {
        throw 'Source scope resolved to zero files. Aborting to prevent destructive deletions.'
    }

    $added = New-Object System.Collections.Generic.List[string]
    $changed = New-Object System.Collections.Generic.List[string]
    $deleted = New-Object System.Collections.Generic.List[string]
    $newMergeJsonPaths = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)

    foreach ($relativePath in ($sourceFiles.Keys | Sort-Object)) {
        $isRootMd = @($protectedRootMdPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0
        if ($isRootMd -and -not $AllowRootMdSync) {
            throw "Protected root markdown path '$relativePath' cannot be synced without -AllowRootMdSync."
        }

        $sourcePath = $sourceFiles[$relativePath]
        $targetPath = Join-Path $targetRootResolved $relativePath
        $targetDirectory = Split-Path -Parent $targetPath
        $isMergeJson = @($normalizedMergeJsonPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0
        $isCodexConfig = $relativePath -ieq '.codex/config.toml'

        if (-not $targetFiles.ContainsKey($relativePath)) {
            if ($isMergeJson) {
                $newMergeJsonPaths.Add($relativePath) | Out-Null
            } else {
                $added.Add($relativePath)
            }
            if (-not $DryRun) {
                New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
                if ($isRootMd) {
                    # Create root markdown with managed sync markers
                    Set-FileSectionByMarkers -TargetPath $targetPath -SourceSectionPath $sourcePath -DryRun:$DryRun
                } elseif (-not $isMergeJson) {
                    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
                }
            }
            continue
        }

        if ($isCodexConfig) {
            # Update the managed [agents] concurrency and MCP sections while
            # preserving unrelated target settings and comments.
            $targetLines = Merge-CodexProjectConfig -SourcePath $sourcePath -TargetPath $targetPath
            $before = [string]::Join("`n", @(Get-Content -LiteralPath $targetPath -Encoding UTF8))
            $after = [string]::Join("`n", $targetLines)
            if ($before -ne $after) {
                $changed.Add($relativePath)
                if (-not $DryRun) { Write-AllLinesUtf8WithRetry -Path $targetPath -Lines $targetLines }
            }
        } elseif ($isRootMd) {
            # Update only the section between sync markers
            $result = Set-FileSectionByMarkers -TargetPath $targetPath -SourceSectionPath $sourcePath -DryRun:$DryRun
            if ($result.changed) {
                $changed.Add($relativePath)
            }
        } elseif ($isMergeJson) {
            # Skip plain copy — the merge step below will handle this file, preserving target-only keys
        } else {
            $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
            $targetHash = (Get-FileHash -LiteralPath $targetFiles[$relativePath] -Algorithm SHA256).Hash
            if ($sourceHash -ne $targetHash) {
                $changed.Add($relativePath)
                if (-not $DryRun) {
                    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
                }
            }
        }
    }

    foreach ($relativePath in ($targetFiles.Keys | Sort-Object)) {
        if (-not $AllowRootMdSync -and @($protectedRootMdPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0) {
            continue
        }

        if ($sourceFiles.ContainsKey($relativePath)) {
            continue
        }

        $isPreserved = @($PreservePattern | Where-Object { $relativePath -match [regex]::Escape($_) }).Count -gt 0
        if ($isPreserved) {
            continue
        }

        # An explicitly excluded service path may already exist in a client
        # from an older sync. Only remove it when the managed ignore block
        # proves Agent Sync previously owned the path; never delete a
        # client-owned file merely because its name matches the boundary.
        $isExcludedServicePath = @($ExcludeSyncPathPattern | Where-Object { $relativePath -match $_ }).Count -gt 0
        if ($isExcludedServicePath) {
            $managedIgnorePath = "/$relativePath"
            $managedIgnoreOwned = $false
            $targetGitignorePath = Join-Path $targetRootResolved '.gitignore'
            if (Test-Path -LiteralPath $targetGitignorePath) {
                $gitignoreLines = @(Get-Content -LiteralPath $targetGitignorePath -Encoding UTF8)
                foreach ($markerPair in @(
                    @('# BEGIN synced-agent-assets', '# END synced-agent-assets'),
                    @('# BEGIN synced-copilot-assets', '# END synced-copilot-assets')
                )) {
                    $beginManaged = [array]::IndexOf($gitignoreLines, $markerPair[0])
                    $endManaged = [array]::IndexOf($gitignoreLines, $markerPair[1])
                    if ($beginManaged -ge 0 -and $endManaged -gt $beginManaged) {
                        $managedIgnoreOwned = @($gitignoreLines[($beginManaged + 1)..($endManaged - 1)] | Where-Object { $_ -eq $managedIgnorePath }).Count -gt 0
                        if ($managedIgnoreOwned) { break }
                    }
                }
            }
            if (-not $managedIgnoreOwned) {
                continue
            }
        }

        $isMergeJson = @($normalizedMergeJsonPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0
        if ($isMergeJson) {
            continue
        }

        $deleted.Add($relativePath)
        if (-not $DryRun) {
            Remove-Item -LiteralPath $targetFiles[$relativePath] -Force
        }
    }

    # Clean up stale .kilo/command (singular) directory from older sync versions
    $staleKiloCommand = Join-Path $targetRootResolved '.kilo/command'
    if (Test-Path -LiteralPath $staleKiloCommand) {
        $staleFiles = @(Get-ChildItem -LiteralPath $staleKiloCommand -File -Recurse -ErrorAction SilentlyContinue)
        if ($staleFiles.Count -eq 0) {
            if (-not $DryRun) {
                Remove-Item -LiteralPath $staleKiloCommand -Recurse -Force -ErrorAction SilentlyContinue
            }
            $changed.Add('.kilo/command')
        } else {
            Write-Warning "Stale .kilo/command directory still contains $($staleFiles.Count) files. Manually migrate to .kilo/commands/."
        }
    }

    # Inject universal governance section into target AGENTS.md (marker-based; never overwrites)
    $targetAgentsMd = Join-Path $targetRootResolved 'AGENTS.md'
    $sourceAgentsCoreRules = Join-Path $tempRoot '.github/skills/shared/agents-core-rules.md'
    $agentsCoreRulesInjection = $null

    if ($AllowRootMdSync -and (Test-Path -LiteralPath $sourceAgentsCoreRules) -and (Test-Path -LiteralPath $targetAgentsMd)) {
        $agentsCoreRulesInjection = Set-FileSectionByMarkers `
            -TargetPath $targetAgentsMd `
            -SourceSectionPath $sourceAgentsCoreRules `
            -DryRun:$DryRun
        if ($agentsCoreRulesInjection.changed) {
            $changed.Add('AGENTS.md')
        }
    }

    $mergedJsonFiles = New-Object System.Collections.Generic.List[string]
    $removedDeadHooks = New-Object System.Collections.Generic.List[string]
    # Stale-file deletion above has already run on disk for a real apply but is
    # only planned during -DryRun. Hand the planned set to the merge so dead-hook
    # detection sees the same post-deletion world in both modes and the mandatory
    # dry-run JSON is a faithful preview of the apply.
    $pendingDeletions = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($deletedPath in $deleted) { $pendingDeletions.Add($deletedPath) | Out-Null }
    foreach ($mergeRelPath in $normalizedMergeJsonPaths) {
        $inScope = @($normalizedScope | Where-Object {
            $mergeRelPath -ieq $_ -or
            $mergeRelPath.StartsWith("$_/", [System.StringComparison]::OrdinalIgnoreCase)
        }).Count -gt 0
        if (-not $inScope) { continue }

        $sourceMergePath = Join-Path $tempRoot $mergeRelPath
        $targetMergePath = Join-Path $targetRootResolved $mergeRelPath
        $mergeResult = Merge-JsonSettings `
            -SourcePath $sourceMergePath `
            -TargetPath $targetMergePath `
            -RepoRoot $targetRootResolved `
            -PendingDeletions $pendingDeletions `
            -DryRun:$DryRun
        if ($mergeResult.changed) {
            $mergedJsonFiles.Add($mergeRelPath)
            if ($newMergeJsonPaths.Contains($mergeRelPath)) {
                $added.Add($mergeRelPath)
            }
        }
        foreach ($deadHook in @($mergeResult.removedDeadHooks)) {
            $removedDeadHooks.Add("${mergeRelPath}: $deadHook")
        }
    }

    # Session hook migration: read the CopilotTools config from the cloned
    # source and conditionally add/remove session-recovery hooks in the
    # target .claude/settings.json.  This is deterministic and idempotent:
    # OFF removes the four session hooks, ON ensures exactly one copy of each.
    $sessionHookMigrationResult = $null
    $configPath = Join-Path $tempRoot '.copilottools/config.json'
    $sessionRecoveryEnabled = $false
    if (Test-Path -LiteralPath $configPath) {
        try {
            $configRaw = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
            $configObj = $configRaw | ConvertFrom-Json
            if ($null -ne $configObj -and $null -ne $configObj.PSObject.Properties['claude_session_recovery_enabled']) {
                $sessionRecoveryEnabled = [bool]$configObj.claude_session_recovery_enabled
            }
        } catch {
            Write-Warning "Failed to read session recovery config: $_"
        }
    }

    $targetSettingsPath = Join-Path $targetRootResolved '.claude/settings.json'
    if (Test-Path -LiteralPath $targetSettingsPath) {
        $targetSettingsRaw = Get-Content -LiteralPath $targetSettingsPath -Raw -Encoding UTF8
        $targetSettings = $null
        try {
            $targetSettings = $targetSettingsRaw | ConvertFrom-Json
        } catch {
            Write-Warning "Failed to parse target settings for session migration: $_"
        }

        if ($null -ne $targetSettings) {
            $migrationRemoved = @()
            $migrationAdded = @()

            if (-not $sessionRecoveryEnabled) {
                $migrationRemoved = @(Remove-SessionRecoveryHooks -Settings $targetSettings)
            } else {
                $migrationAdded = @(Add-SessionRecoveryHooks -Settings $targetSettings)
            }

            $migrationChanged = $migrationRemoved.Count -gt 0 -or $migrationAdded.Count -gt 0
            if ($migrationChanged -and -not $DryRun) {
                $json = ConvertTo-Json -InputObject $targetSettings -Depth 10
                $encoding = New-Object System.Text.UTF8Encoding($false)
                [System.IO.File]::WriteAllText($targetSettingsPath, ($json + "`r`n"), $encoding)
            }

            $sessionHookMigrationResult = [pscustomobject]@{
                sessionRecoveryEnabled = $sessionRecoveryEnabled
                removed = @($migrationRemoved)
                added = @($migrationAdded)
                changed = $migrationChanged
            }

            if ($migrationRemoved.Count -gt 0) {
                foreach ($r in $migrationRemoved) {
                    $removedDeadHooks.Add("session-recovery: $r")
                }
            }
        }
    }

    $gitignoreEntryCount = Set-ExactGitignoreEntries `
        -Root $targetRootResolved `
        -RelativePaths @($sourceFiles.Keys) `
        -ExcludePattern $ExcludeGitignorePattern

    $transientGitignoreEntryCount = Set-TransientCopilotArtifactGitignoreEntries -Root $targetRootResolved

    # Untrack any synced files that git is still tracking despite being gitignored.
    # git rm --cached removes from the index only; the file stays on disk.
    # This prevents synced scripts from appearing as modified in git clients (Fork, VS Code).
    if (-not $DryRun) {
        $untrackedCount = 0
        foreach ($relativePath in $sourceFiles.Keys) {
            $isExcluded = (@($ExcludeGitignorePattern | Where-Object { $relativePath -match [regex]::Escape($_) }).Count -gt 0)
            if ($isExcluded) { continue }
            $repoPath = Convert-ToRepoRelativePath -Path $relativePath
            # Plain ls-files (no --error-unmatch): untracked files yield empty
            # output instead of stderr, which Windows PowerShell 5.1 would turn
            # into a terminating NativeCommandError under EAP=Stop.
            $tracked = git -C $targetRootResolved ls-files -- $repoPath
            if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace([string]$tracked)) {
                git -C $targetRootResolved rm --cached --quiet $repoPath 2>$null | Out-Null
                $untrackedCount++
            }
        }
        if ($untrackedCount -gt 0) {
            Write-Host "  Untracked $untrackedCount gitignored file(s) from git index"
        }
    }

    $gitHooksConfiguration = $null
    if ($normalizedScope -icontains '.githooks') {
        $gitHooksConfiguration = Set-RepositoryGitHooksPath -Root $targetRootResolved -DryRun:$DryRun
    }

    # Reconcile legacy CopilotTools Windows Scheduled Tasks after the scripts
    # themselves have been synced. Older registrations can keep launching a
    # visible pwsh/cmd window even though current task creation uses
    # "-WindowStyle Hidden". Repair only tasks that already exist and belong
    # to this target repository; never create a task as a side effect of sync.
    $scheduledTaskWindowRepair = $null
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        $schedulerWrapperPath = Join-Path $targetRootResolved '.github/scripts/scheduler-wrapper.ps1'
        if (Test-Path -LiteralPath $schedulerWrapperPath) {
            try {
                . $schedulerWrapperPath
                $scheduledTaskWindowRepair = Repair-CopilotToolsScheduledTaskWindowStyle -RepositoryRoot $targetRootResolved -WhatIf:$DryRun
            } catch {
                $scheduledTaskWindowRepair = [pscustomobject]@{
                    supported   = $true
                    scanned     = 0
                    matched     = 0
                    repaired    = 0
                    alreadySafe = 0
                    failed      = 1
                    reason      = 'repair-failed'
                    errors      = @($_.Exception.Message)
                }
                if (-not $DryRun) {
                    Write-Warning "Failed to reconcile existing CopilotTools Scheduled Tasks: $($_.Exception.Message)"
                }
            }
        }
    }

    # No session-tracking preflight here. Agent Sync is the one workflow exempt
    # from the session-limit stack: it delivers those scripts, it does not
    # depend on them, and running the preflight made a short, idempotent,
    # freely repeatable file sync launch Chrome, probe claude.ai identity, and
    # block on account questions that have nothing to do with copying files.
    # `check-tracking-preflight.ps1` is still shipped and is run on demand
    # (see .github/skills/session-limit-tracking/SKILL.md) by the agents that
    # actually rely on it.

    $forbiddenArtifacts = Get-ForbiddenCopilotArtifacts -Root $targetRootResolved
    $nestedSdlcStatusFiles = Get-NestedSdlcStatusFiles -Root $targetRootResolved

    $mismatches = New-Object System.Collections.Generic.List[string]
    if (-not $DryRun) {
        $postTargetFiles = Get-RelativeFileMap -Root $targetRootResolved -ScopePaths $normalizedScope
        foreach ($relativePath in ($sourceFiles.Keys | Sort-Object)) {
            $isMergeJson = @($normalizedMergeJsonPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0
            if ($isMergeJson) {
                $verifyResult = Merge-JsonSettings `
                    -SourcePath $sourceFiles[$relativePath] `
                    -TargetPath (Join-Path $targetRootResolved $relativePath) `
                    -RepoRoot $targetRootResolved `
                    -DryRun
                if ($verifyResult.changed) { $mismatches.Add($relativePath) }
                continue
            }

            if (-not $postTargetFiles.ContainsKey($relativePath)) {
                $mismatches.Add($relativePath)
                continue
            }

            $isRootMd = @($protectedRootMdPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0
            if ($isRootMd -and $AllowRootMdSync) {
                $check = Set-FileSectionByMarkers -TargetPath $postTargetFiles[$relativePath] -SourceSectionPath $sourceFiles[$relativePath] -DryRun
                if ($check.changed) {
                    $mismatches.Add($relativePath)
                }
                continue
            }

            $sourceHash = (Get-FileHash -LiteralPath $sourceFiles[$relativePath] -Algorithm SHA256).Hash
            $targetHash = (Get-FileHash -LiteralPath $postTargetFiles[$relativePath] -Algorithm SHA256).Hash
            if ($sourceHash -ne $targetHash) {
                $mismatches.Add($relativePath)
            }
        }
    }

    if (-not $DryRun -and $agentsCoreRulesInjection -ne $null) {
        $check = Set-FileSectionByMarkers `
            -TargetPath $targetAgentsMd `
            -SourceSectionPath $sourceAgentsCoreRules `
            -DryRun
        if ($check.changed) {
            $mismatches.Add('AGENTS.md')
        }
    }

    if (-not $DryRun) {
        $assetLockPath = Join-Path $targetRootResolved '.copilottools/asset-lock.json'
        New-Item -ItemType Directory -Path (Split-Path -Parent $assetLockPath) -Force | Out-Null
        $assetLock = [ordered]@{ schemaVersion = 1; sourceRevision = $sourceCommit; manifestHash = (Get-RuntimeLayerHash -Root $targetRootResolved) }
        $assetLock | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $assetLockPath -Encoding UTF8
    }

    $categoryCounts = [ordered]@{
        githubCopilot = [ordered]@{ added = 0; changed = 0; deleted = 0 }
        claude = [ordered]@{ added = 0; changed = 0; deleted = 0 }
        openCode = [ordered]@{ added = 0; changed = 0; deleted = 0 }
        kilo = [ordered]@{ added = 0; changed = 0; deleted = 0 }
        codex = [ordered]@{ added = 0; changed = 0; deleted = 0 }
        shared = [ordered]@{ added = 0; changed = 0; deleted = 0 }
        branchGuards = [ordered]@{ added = 0; changed = 0; deleted = 0 }
    }
    foreach ($kind in @(@('added', $added), @('changed', $changed), @('deleted', $deleted))) {
        foreach ($path in $kind[1]) {
            if ($path -like '.opencode/*' -or $path -eq 'opencode.jsonc') { $category = 'openCode' }
            elseif ($path -like '.kilo/*' -or $path -eq 'kilo.jsonc') { $category = 'kilo' }
            elseif ($path -like '.agents/*' -or $path -like '.codex/*') { $category = 'codex' }
            elseif ($path -like '.claude/*') { $category = 'claude' }
            elseif ($path -like '.github/hooks/*' -or $path -like '.githooks/*') { $category = 'branchGuards' }
            elseif ($path -like '.github/agents/*') { $category = 'githubCopilot' }
            else { $category = 'shared' }
            $categoryCounts[$category][$kind[0]]++
        }
    }

    $result = [ordered]@{
        sourceRepo = $SourceRepo
        sourceRef = $SourceRef
        sourceCommit = $sourceCommit
        targetRoot = $targetRootResolved
        targetBranch = $targetBranch
        scope = $normalizedScope
        dryRun = [bool]$DryRun
        addedCount = $added.Count
        changedCount = $changed.Count
        deletedCount = $deleted.Count
        added = @($added)
        changed = @($changed)
        deleted = @($deleted)
        categoryCounts = $categoryCounts
        mergedJsonFiles = @($mergedJsonFiles)
        removedDeadHooks = @($removedDeadHooks)
        agentsCoreRulesInjection = $agentsCoreRulesInjection
        gitignoreExactEntryCount = $gitignoreEntryCount
        transientGitignoreEntryCount = $transientGitignoreEntryCount
        gitHooksConfiguration = $gitHooksConfiguration
        scheduledTaskWindowRepair = $scheduledTaskWindowRepair
        forbiddenArtifacts = @($forbiddenArtifacts)
        nestedSdlcStatusFiles = @($nestedSdlcStatusFiles)
        verification = $(if ($mismatches.Count -eq 0) { 'passed' } else { 'failed' })
        mismatches = @($mismatches)
        sessionHookMigration = $sessionHookMigrationResult
    }

    $result | ConvertTo-Json -Depth 5

    if ($mismatches.Count -gt 0) {
        exit 1
    }
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

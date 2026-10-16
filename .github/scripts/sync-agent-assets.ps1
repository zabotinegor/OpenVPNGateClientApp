param(
    [string]$SourceRepo = 'https://github.com/zabotinegor/CopilotTools.git',
    [string]$SourceRef = 'main',
    [string]$TargetRoot = (Get-Location).Path,
    [string[]]$Scope = @(),
    [string[]]$PreservePattern = @('agent-sync', '.github/scripts/sync-agent-assets.ps1', 'asset-lock.json'),
    [string[]]$ExcludeGitignorePattern = @('agent-sync', '.github/scripts/sync-agent-assets.ps1', '.github/hooks/', '.githooks/', 'protect-agent-git-command', '.copilottools/config.json'),
    [string[]]$MergeJsonPaths = @(),
    [switch]$DryRun,
    [switch]$AllowRootMdSync
)

$ErrorActionPreference = 'Stop'
$ExcludeSyncPathPattern = @()
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "sync-agent-assets.ps1 requires PowerShell 7+ (pwsh); it uses APIs missing from Windows PowerShell $($PSVersionTable.PSVersion). Re-run with: pwsh -NoProfile -File .github/scripts/sync-agent-assets.ps1"
}
. (Join-Path $PSScriptRoot 'runtime-layer-hash.ps1')
. (Join-Path $PSScriptRoot 'runtime-distribution-lib.ps1')
# runtime-distribution-lib.ps1 enables strict mode at dot-source time; this entrypoint is
# written for non-strict property access, so restore it for the caller scope.
Set-StrictMode -Off

# Every helper this entrypoint dot-sources is needed to run it at all, so it must stay
# trackable in client repos (a fresh clone cannot obtain it by syncing). Derive the list
# from this script's own dot-source statements and exempt it from the managed .gitignore block.
$bootstrapHelpers = @([regex]::Matches((Get-Content -LiteralPath $PSCommandPath -Raw), '(?m)^\.\s+\(Join-Path \$PSScriptRoot ''([^'']+\.ps1)''\)') | ForEach-Object { $_.Groups[1].Value })
$ExcludeGitignorePattern = @($ExcludeGitignorePattern) + $bootstrapHelpers

function ConvertTo-QuotedProcessArgument {
    # Quotes a single argument per the CommandLineToArgvW escaping rules so it
    # survives being joined into ONE pre-built command-line string. Needed
    # because Start-Process -ArgumentList <string[]> does NOT auto-quote array
    # elements containing spaces - it joins them with plain spaces, silently
    # splitting a spaced path (e.g. 'C:\Users\Jane Doe\...') into separate
    # argv entries on the receiving side (unlike ProcessStartInfo.ArgumentList,
    # which quotes each element correctly, or a normal '&' call).
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Value)

    if ($Value.Length -gt 0 -and $Value.IndexOfAny([char[]]@(' ', "`t", '"')) -lt 0) {
        return $Value
    }

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $index = 0
    while ($index -lt $Value.Length) {
        if ($Value[$index] -eq '\') {
            $backslashCount = 0
            while ($index -lt $Value.Length -and $Value[$index] -eq '\') { $backslashCount++; $index++ }
            if ($index -eq $Value.Length) { [void]$builder.Append([char]'\', $backslashCount * 2) }
            elseif ($Value[$index] -eq '"') {
                [void]$builder.Append([char]'\', $backslashCount * 2 + 1)
                [void]$builder.Append('"')
                $index++
            }
            else { [void]$builder.Append([char]'\', $backslashCount) }
        }
        elseif ($Value[$index] -eq '"') {
            [void]$builder.Append('\"')
            $index++
        }
        else {
            [void]$builder.Append($Value[$index])
            $index++
        }
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-ExternalCommand {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    # Redirect stdout/stderr to temp files instead of merging them into
    # PowerShell's pipeline via '2>&1'. A shallow clone ('git clone --depth')
    # of a LOCAL repository path still goes through the smart pack-negotiation
    # protocol - git spawns a 'git-upload-pack' grandchild that inherits the
    # stdout/stderr pipe handles. Windows only signals EOF on an anonymous
    # pipe once every handle to its write end closes, including ones
    # inherited by that grandchild; PowerShell's native-command '2>&1' capture
    # can then hang indefinitely waiting for EOF that the already-exited
    # direct child will never provide by itself. This was reproduced directly
    # (git.exe clone plus a still-running git-upload-pack.exe blocked the
    # whole call). File redirection has no such handle-coupling: the OS
    # signals process exit independently of who still holds the file open.
    #
    # The array elements are pre-quoted and joined into a single command-line
    # string (rather than passed as -ArgumentList $Arguments directly) because
    # Start-Process -ArgumentList <string[]> joins array elements with plain
    # spaces and does not quote ones containing spaces - a path argument with
    # a space (e.g. under a Windows user profile like 'C:\Users\Jane Doe\...')
    # would otherwise be split into multiple separate argv entries.
    $stdoutPath = [System.IO.Path]::GetTempFileName()
    $stderrPath = [System.IO.Path]::GetTempFileName()
    try {
        $argumentLine = (@($Arguments) | ForEach-Object { ConvertTo-QuotedProcessArgument -Value $_ }) -join ' '
        $process = Start-Process -FilePath $FilePath -ArgumentList $argumentLine -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $exitCode = $process.ExitCode
        $output = @()
        if (Test-Path -LiteralPath $stdoutPath) { $output += @(Get-Content -LiteralPath $stdoutPath -Encoding UTF8 -ErrorAction SilentlyContinue) }
        if (Test-Path -LiteralPath $stderrPath) { $output += @(Get-Content -LiteralPath $stderrPath -Encoding UTF8 -ErrorAction SilentlyContinue) }
    }
    finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
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
        [string[]]$ExcludePattern,
        [string[]]$BlockedPatterns = @()
    )

    $gitignorePath = Join-Path $Root '.gitignore'
    $beginMarker = '# BEGIN synced-agent-assets'
    $endMarker = '# END synced-agent-assets'
    $beginMarkers = @('# BEGIN synced-agent-assets', '# BEGIN synced-copilot-assets')
    $endMarkers = @('# END synced-agent-assets', '# END synced-copilot-assets')
    $existing = @()
    if (Test-Path -LiteralPath $gitignorePath) {
        $existing = @(Get-Content -LiteralPath $gitignorePath -Encoding UTF8)
    }

    foreach ($blockedPattern in $BlockedPatterns) {
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
    param([string]$Root, [string[]]$AdditionalEntries = @())

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
        # The lock every non-dry-run sync writes (revision + runtime-layer hash).
        # It is generated per machine and PreservePattern keeps later syncs from
        # deleting it, so without an ignore rule it is a permanent untracked file.
        # Only the lock is ignored: .copilottools/config.json is a hand-authored
        # hand-authored runtime configuration stays trackable.
        '/.copilottools/asset-lock.json'
    )
    # Built-in and source/runtime-manifest patterns overlap. Emit each once.
    $seenIgnoreEntries = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $entries = @((@($entries) + @($AdditionalEntries | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })) | Where-Object { $seenIgnoreEntries.Add([string]$_) })

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
        [pscustomobject]$Target,
        # Top-level keys the target owns once it defines them: seeded when absent, never overwritten.
        [string[]]$SeedOnlyKeys = @()
    )
    $changed = $false
    foreach ($prop in $Source.PSObject.Properties) {
        $existing = $Target.PSObject.Properties[$prop.Name]
        if ($null -eq $existing) {
            $Target | Add-Member -MemberType NoteProperty -Name $prop.Name -Value $prop.Value
            $changed = $true
        } elseif ($prop.Name -in $SeedOnlyKeys) {
            continue
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

function Merge-JsonSettings {
    param(
        [string]$SourcePath,
        [string]$TargetPath,
        [string]$RepoRoot,
        [System.Collections.Generic.HashSet[string]]$PendingDeletions,
        [string[]]$SeedOnlyKeys = @(),
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

    $changed = Merge-PsObjects -Source $sourceObj -Target $targetObj -SeedOnlyKeys $SeedOnlyKeys

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

function Get-SeedOnlyConfigKeys {
    param([object]$Distribution, [string]$RelativePath)
    $keys = @()
    foreach ($entry in @($Distribution.seedOnlyConfigKeys)) {
        if ($entry -and [string]$entry.path -ieq $RelativePath) { $keys += @($entry.keys) }
    }
    return ,@($keys)
}

function Resolve-SourceAdapterScript {
    # Manifest-declared adapters are executed from the fetched source. -SourceRepo is trusted
    # caller input (the canonical default, or an explicit mirror/fixture the caller chose), so
    # execution is intentional, but a manifest must never point outside the fetched checkout:
    # only a relative, traversal-free path to an existing .github/scripts/** file is accepted.
    param([string]$SourceRoot, [string]$AdapterPath, [string]$Label)
    $normalized = Convert-ToRepoRelativePath -Path ([string]$AdapterPath)
    if ([string]::IsNullOrWhiteSpace($normalized) -or [IO.Path]::IsPathRooted([string]$AdapterPath) -or $normalized -match '^[A-Za-z]:' -or ($normalized -split '/') -contains '..') {
        throw "$Label adapter path '$AdapterPath' must be a relative path without '..' segments."
    }
    if ($normalized -notmatch '^\.github/scripts/') {
        throw "$Label adapter path '$AdapterPath' must live under .github/scripts/."
    }
    $full = [IO.Path]::GetFullPath((Join-Path $SourceRoot $normalized))
    $rootFull = [IO.Path]::GetFullPath($SourceRoot).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label adapter path '$AdapterPath' resolves outside the source checkout."
    }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "$Label adapter is missing: $full" }
    return $full
}

function Test-PathInSyncScope {
    param([string]$Path, [string[]]$Scope)
    $candidate = Convert-ToRepoRelativePath -Path $Path
    foreach ($scopePath in @($Scope)) {
        $normalizedScopePath = (Convert-ToRepoRelativePath -Path $scopePath).TrimEnd('/')
        if ($candidate -ieq $normalizedScopePath -or $candidate.StartsWith("$normalizedScopePath/", [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Test-ScopeIntersectsPaths {
    param([string[]]$Scope, [string[]]$Paths)
    foreach ($owned in @($Paths)) {
        foreach ($scopePath in @($Scope)) {
            if ((Test-PathInSyncScope -Path $owned -Scope @($scopePath)) -or (Test-PathInSyncScope -Path $scopePath -Scope @($owned))) { return $true }
        }
    }
    return $false
}

function Set-RepositoryGitHooksPath {
    param([string]$Root, [string]$SourceRoot, [switch]$DryRun)
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        # --git-path hooks follows core.hooksPath: a legacy relative value such as
        # .githooks resolves inside the checkout. Use the real shared Git
        # metadata directory instead, including for linked worktrees.
        $gitCommonDir = ((git -C $Root rev-parse --path-format=absolute --git-common-dir 2>$null) | Out-String).Trim()
    } finally { $ErrorActionPreference = $previousEap }
    if (-not $gitCommonDir) {
        return [pscustomobject]@{ changed = $false; status = 'not-a-git-worktree'; hooksPath = $null }
    }
    # Never trust a core.hooksPath derived location or versioned checkout file.
    # All worktrees of this repository share the same stable Git common directory.
    $trusted = Join-Path $gitCommonDir 'copilottools-trusted-hooks'
    $files = [ordered]@{
        'post-checkout' = '.githooks/post-checkout'
        'pre-commit' = '.githooks/pre-commit'
        'pre-push' = '.githooks/pre-push'
        'reference-transaction' = '.githooks/reference-transaction'
        'trusted-runtime/sync-agent-assets.ps1' = '.github/scripts/sync-agent-assets.ps1'
        'trusted-runtime/runtime-layer-hash.ps1' = '.github/scripts/runtime-layer-hash.ps1'
        'trusted-runtime/runtime-distribution-lib.ps1' = '.github/scripts/runtime-distribution-lib.ps1'
    }
    $fileChanged = $false
    foreach ($item in $files.GetEnumerator()) {
        $src = Join-Path $SourceRoot $item.Value
        $dst = Join-Path $trusted $item.Key
        if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw "Missing trusted hook source: $($item.Value)" }
        $different = (-not (Test-Path -LiteralPath $dst -PathType Leaf)) -or
            ((Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash)
        $isExecutableHook = $item.Key -in @('post-checkout', 'pre-commit', 'pre-push', 'reference-transaction')
        $needsExecuteRepair = $false
        if (-not $IsWindows -and $isExecutableHook -and (Test-Path -LiteralPath $dst -PathType Leaf)) {
            # Trusted hook contents and Unix execution permissions are separate
            # invariants. A restored but non-executable hook is silently ignored
            # by Git even when the hashes match, so repair metadata independently.
            # POSIX test -x is available on supported Unix hosts even with
            # PowerShell 7.0/7.2 (.NET Core 3.1/.NET 6). The .NET 7-only
            # File.GetUnixFileMode/UnixFileMode APIs would crash routine sync.
            & test -x $dst
            $testExit = $LASTEXITCODE
            if ($testExit -notin @(0, 1)) {
                throw "Cannot verify trusted hook execution permission: $dst (test exit $testExit)"
            }
            $needsExecuteRepair = $testExit -eq 1
        }
        if ($different -or $needsExecuteRepair) {
            $fileChanged = $true
            if (-not $DryRun) {
                if ($different) {
                    New-Item -ItemType Directory -Path (Split-Path -Parent $dst) -Force | Out-Null
                    Copy-Item -LiteralPath $src -Destination $dst -Force
                }
                if (-not $IsWindows -and $isExecutableHook) {
                    & chmod +x $dst
                    if ($LASTEXITCODE -ne 0) { throw 'Cannot chmod trusted hook' }
                }
            }
        }
    }
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $current = ((git -C $Root config --local --get core.hooksPath 2>$null) | Out-String).Trim()
    } finally { $ErrorActionPreference = $previousEap }
    $needsConfig = $current -ne $trusted
    if ($needsConfig -and -not $DryRun) {
        Invoke-ExternalCommand -FilePath 'git' -Arguments @('-C', $Root, 'config', '--local', 'core.hooksPath', $trusted) -FailureMessage 'Cannot configure trusted hooks path.' | Out-Null
    }
    $changed = $fileChanged -or $needsConfig
    if ($DryRun -and $changed) { $status = 'would-configure' }
    elseif ($changed) { $status = 'configured' }
    else { $status = 'already-configured' }
    return [pscustomobject]@{ changed = $changed; status = $status; hooksPath = $trusted }
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

    $distribution = Get-CopilotToolsDistribution -Root $tempRoot
    # A scoped sync verifies only the requested files. It must not advance the
    # global runtime-layer attestation: unsynchronized assets may still be stale
    # even if the scoped verification passes.
    $isFullManifestSync = ($normalizedScope.Count -eq 0)
    if ($isFullManifestSync) { $normalizedScope = @($distribution.allAssets) }
    # Manifest config adapters own their declared formats (including TOML).
    # They must not fall into the generic JSON merge/verification pipeline,
    # even when a runtime also advertises that path as mergeable JSON.
    $adapterOwnedPaths = @($distribution.configMerges | ForEach-Object { Convert-ToRepoRelativePath -Path ([string]$_.path) })
    $normalizedMergeJsonPaths = @(
        @($normalizedMergeJsonPaths) + @($distribution.mergeJsonPaths) |
            Where-Object { $_ -notin $adapterOwnedPaths } |
            Select-Object -Unique
    )
    $ExcludeSyncPathPattern = @($distribution.excludeSyncPathPatterns)

    $requestedProtectedRootMd = @(
        $normalizedScope |
            Where-Object {
                $scopePath = $_
                @($protectedRootMdPaths | Where-Object { $_ -ieq $scopePath }).Count -gt 0
            }
    )
    if ($requestedProtectedRootMd.Count -gt 0 -and -not $AllowRootMdSync) {
        $blocked = ($requestedProtectedRootMd | Sort-Object -Unique) -join ', '
        throw "Resolved sync scope contains protected root markdown path(s): $blocked. Re-run with -AllowRootMdSync only when explicitly approved."
    }

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
        $configMergeEntry = $distribution.configMerges | Where-Object { $_.path -ieq $relativePath } | Select-Object -First 1

        if (-not $targetFiles.ContainsKey($relativePath)) {
            if ($isMergeJson -and $null -eq $configMergeEntry) {
                $newMergeJsonPaths.Add($relativePath) | Out-Null
            } else {
                $added.Add($relativePath)
            }
            if (-not $DryRun) {
                New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
                if ($isRootMd) {
                    # Create root markdown with managed sync markers
                    Set-FileSectionByMarkers -TargetPath $targetPath -SourceSectionPath $sourcePath -DryRun:$DryRun
                } elseif (-not $isMergeJson -or $null -ne $configMergeEntry) {
                    # Initialize adapter-managed files with their source-format
                    # bytes; the adapter below then computes canonical content.
                    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
                }
            }
            if ($null -eq $configMergeEntry -or $DryRun) { continue }
        }

        if ($null -ne $configMergeEntry) {
            $adapterPath = Resolve-SourceAdapterScript -SourceRoot $tempRoot -AdapterPath ([string]$configMergeEntry.adapterScript) -Label 'Config merge'
            $adapterOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments @('-NoProfile','-NonInteractive','-File',$adapterPath,'-SourcePath',$sourcePath,'-TargetPath',$targetPath) -FailureMessage "Config merge adapter failed for '$relativePath'."
            $adapterResult = ([string]::Join([Environment]::NewLine, @($adapterOutput))) | ConvertFrom-Json
            if ($null -eq $adapterResult.PSObject.Properties['content']) { throw "Config merge adapter for '$relativePath' returned no content." }
            $before = Get-Content -LiteralPath $targetPath -Raw -Encoding UTF8
            $after = [string]$adapterResult.content
            if ($before -ne $after) {
                $changed.Add($relativePath)
                if (-not $DryRun) { [IO.File]::WriteAllText($targetPath, $after, [Text.UTF8Encoding]::new($false)) }
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
            # An updated client .gitignore may already have pruned its legacy
            # test entry. Recover ownership only when the target still matches
            # an actual source-only CopilotTools test byte-for-byte. Preserve
            # locally edited or unrelated client files rather than deleting
            # them under an inferred ownership claim.
            if (-not $managedIgnoreOwned -and
                $relativePath -match '^\.github/scripts/(?:test-[^/]+|run-all-tests\.ps1)
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

    # Retired legacy directories are data-driven, not hard-coded per runtime.
    # A hidden file or even an empty subdirectory counts as user-owned content.
    foreach ($legacy in @($distribution.config.retiredLegacyDirectories)) {
        if ($null -eq $legacy) { continue }
        $legacyPath = Convert-ToRepoRelativePath -Path ([string]$legacy.path)
        $replacementPath = Convert-ToRepoRelativePath -Path ([string]$legacy.replacedBy)
        if ([string]::IsNullOrWhiteSpace($legacyPath) -or
            $legacyPath -match '(^|/)\.\.(/|$)' -or
            [IO.Path]::IsPathRooted($legacyPath)) {
            throw "Unsafe retired legacy directory path in distribution manifest: '$legacyPath'."
        }
        if ($Scope.Count -gt 0 -and
            -not (Test-PathInSyncScope -Path $legacyPath -Scope $normalizedScope) -and
            -not (Test-PathInSyncScope -Path $replacementPath -Scope $normalizedScope)) {
            continue
        }

        $staleDirectory = Join-Path $targetRootResolved $legacyPath
        if (-not (Test-Path -LiteralPath $staleDirectory -PathType Container)) {
            if (Test-Path -LiteralPath $staleDirectory) {
                Write-Warning "Stale '$legacyPath' is not a directory. Manually migrate to '$replacementPath'."
            }
            continue
        }

        $children = @(Get-ChildItem -LiteralPath $staleDirectory -Force -ErrorAction Stop)
        if ($children.Count -gt 0) {
            Write-Warning "Stale '$legacyPath' contains $($children.Count) item(s). Manually migrate to '$replacementPath'; nothing was removed."
            continue
        }

        if (-not $DryRun) {
            # Do not recurse: content created after enumeration must not be deleted.
            Remove-Item -LiteralPath $staleDirectory -Force -ErrorAction Stop
            if (Test-Path -LiteralPath $staleDirectory) {
                throw "Failed to remove stale directory '$legacyPath': path still exists."
            }
        }
        $changed.Add($legacyPath)
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
            -SeedOnlyKeys (Get-SeedOnlyConfigKeys -Distribution $distribution -RelativePath $mergeRelPath) `
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

    # Runtime-owned post-sync migrations are declared by manifests.
    $runtimePostSyncResults = [ordered]@{}
    foreach ($postSync in @($distribution.postSyncAdapters)) {
        # A narrow -Scope must not trigger runtime migrations for assets it does not cover.
        if (-not (Test-ScopeIntersectsPaths -Scope $normalizedScope -Paths @($postSync.ownedPaths))) {
            $runtimePostSyncResults[[string]$postSync.runtime] = [ordered]@{ skipped = $true; reason = 'runtime assets are outside the requested scope' }
            continue
        }
        $adapterPath = Resolve-SourceAdapterScript -SourceRoot $tempRoot -AdapterPath ([string]$postSync.adapterScript) -Label "Runtime post-sync ($($postSync.runtime))"
        $adapterArguments = @('-NoProfile','-NonInteractive','-File',$adapterPath,'-SourceRoot',$tempRoot,'-TargetRoot',$targetRootResolved)
        # Preview first: an adapter whose changes would escape the requested scope is skipped, not applied.
        $previewOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments ($adapterArguments + '-DryRun') -FailureMessage "Runtime post-sync adapter preview failed for '$($postSync.runtime)'."
        $previewResult = ([string]::Join([Environment]::NewLine, @($previewOutput))) | ConvertFrom-Json
        $effectiveScope = @($normalizedScope)
        foreach ($legacy in @($postSync.legacyPaths)) {
            if ($legacy -and (Test-PathInSyncScope -Path ([string]$legacy.replacedBy) -Scope $normalizedScope)) { $effectiveScope += [string]$legacy.path }
        }
        $escaping = @(@($previewResult.changedPaths) | Where-Object { $_ -and -not (Test-PathInSyncScope -Path ([string]$_) -Scope $effectiveScope) })
        if ($escaping.Count -gt 0) {
            Write-Warning "Runtime post-sync adapter '$($postSync.runtime)' skipped: it would change path(s) outside the requested scope: $($escaping -join ', ')"
            $runtimePostSyncResults[[string]$postSync.runtime] = [ordered]@{ skipped = $true; reason = 'adapter changes would escape the requested scope'; outOfScopePaths = @($escaping) }
            continue
        }
        $adapterResult = $previewResult
        if (-not $DryRun) {
            $adapterOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments $adapterArguments -FailureMessage "Runtime post-sync adapter failed for '$($postSync.runtime)'."
            $adapterResult = ([string]::Join([Environment]::NewLine, @($adapterOutput))) | ConvertFrom-Json
        }
        $runtimePostSyncResults[[string]$postSync.runtime] = $adapterResult
        foreach ($changedPath in @($adapterResult.changedPaths)) {
            if ($changedPath -and $changedPath -notin $added -and $changedPath -notin $changed) { $changed.Add([string]$changedPath) }
        }
        foreach ($warning in @($adapterResult.warnings)) { if ($warning) { Write-Warning ([string]$warning) } }
        foreach ($removedHook in @($adapterResult.removed)) { if ($removedHook) { $removedDeadHooks.Add("$($postSync.runtime): $removedHook") } }
    }

    $gitignoreEntryCount = Set-ExactGitignoreEntries `
        -Root $targetRootResolved `
        -RelativePaths @($sourceFiles.Keys) `
        -ExcludePattern $ExcludeGitignorePattern `
        -BlockedPatterns @($distribution.config.blockedBroadGitignorePatterns)

    $transientGitignoreEntryCount = Set-TransientCopilotArtifactGitignoreEntries -Root $targetRootResolved -AdditionalEntries @($distribution.transientIgnorePaths)

    # Untrack any synced files that git is still tracking despite being gitignored.
    # git rm --cached removes from the index only; the file stays on disk.
    # This prevents synced scripts from appearing as modified in git clients (Fork, VS Code).
    if (-not $DryRun) {
        $untrackedCount = 0
        # One ls-files call for the whole index instead of one git process per
        # synced file (hundreds of spawns dominated sync time on Windows). Plain
        # ls-files: untracked files yield empty output instead of stderr, which
        # Windows PowerShell 5.1 would turn into a terminating NativeCommandError
        # under EAP=Stop. Path matching follows core.ignorecase like a pathspec.
        $ignoreCase = ([string](git -C $targetRootResolved config --get core.ignorecase 2>$null)).Trim() -eq 'true'
        $trackedComparer = if ($ignoreCase) { [StringComparer]::OrdinalIgnoreCase } else { [StringComparer]::Ordinal }
        $trackedPaths = [System.Collections.Generic.HashSet[string]]::new($trackedComparer)
        $indexListing = git -C $targetRootResolved ls-files -z
        if ($LASTEXITCODE -eq 0) {
            foreach ($entry in ([string]::Join('', @($indexListing)) -split "`0")) {
                if ($entry) { [void]$trackedPaths.Add($entry) }
            }
        }
        $pathsToUntrack = [System.Collections.Generic.List[string]]::new()
        foreach ($relativePath in $sourceFiles.Keys) {
            $isExcluded = (@($ExcludeGitignorePattern | Where-Object { $relativePath -match [regex]::Escape($_) }).Count -gt 0)
            if ($isExcluded) { continue }
            $repoPath = Convert-ToRepoRelativePath -Path $relativePath
            if ($trackedPaths.Contains($repoPath)) { $pathsToUntrack.Add($repoPath) }
        }
        for ($offset = 0; $offset -lt $pathsToUntrack.Count; $offset += 50) {
            $batch = @($pathsToUntrack.GetRange($offset, [Math]::Min(50, $pathsToUntrack.Count - $offset)))
            # Native commands do not automatically honor ErrorActionPreference.
            # A failed batched rm was previously ignored and every path was
            # reported as untracked even though it remained in the git index.
            git -C $targetRootResolved rm --cached --quiet -- @batch 2>$null | Out-Null
            $removeExit = $LASTEXITCODE
            if ($removeExit -ne 0) {
                throw "Failed to remove $($batch.Count) synchronized file(s) from the git index (git rm --cached exit $removeExit); sync is incomplete."
            }
            $stillTracked = @(git -C $targetRootResolved ls-files --cached -- @batch)
            $verifyExit = $LASTEXITCODE
            if ($verifyExit -ne 0 -or $stillTracked.Count -gt 0) {
                throw "Git index verification failed after synchronized-file cleanup (exit $verifyExit, $($stillTracked.Count) tracked path(s) remain); sync is incomplete."
            }
            $untrackedCount += $batch.Count
        }
        if ($untrackedCount -gt 0) {
            Write-Host "  Untracked $untrackedCount gitignored file(s) from git index"
        }
    }

    $gitHooksConfiguration = $null
    # A file-scoped sync can update a hook or its trusted runtime dependencies.
    # Keep the installed immutable copy synchronized whenever ANY source it
    # contains intersects the requested scope (not only the .githooks folder).
    $trustedSourcePaths = @(
        '.githooks',
        '.github/scripts/sync-agent-assets.ps1',
        '.github/scripts/runtime-layer-hash.ps1',
        '.github/scripts/runtime-distribution-lib.ps1'
    )
    if (Test-ScopeIntersectsPaths -Scope $normalizedScope -Paths $trustedSourcePaths) {
        $gitHooksConfiguration = Set-RepositoryGitHooksPath -Root $targetRootResolved -SourceRoot $tempRoot -DryRun:$DryRun
    }

    # No session-tracking preflight here. Agent Sync is the one workflow exempt
    # from the session-limit stack: it delivers those scripts, it does not
    # depend on them, and running the preflight made a short, idempotent,
    # freely repeatable file sync trigger runtime-specific side effects or
    # block on runtime setup questions that have nothing to do with copying files.
    # Runtime-specific preflight tools are still shipped and run on demand
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
                    -SeedOnlyKeys (Get-SeedOnlyConfigKeys -Distribution $distribution -RelativePath $relativePath) `
                    -DryRun
                if ($verifyResult.changed) { $mismatches.Add($relativePath) }
                continue
            }

            if (-not $postTargetFiles.ContainsKey($relativePath)) {
                $mismatches.Add($relativePath)
                continue
            }

            # Runtime config merges intentionally preserve target-only settings, so the target is
            # verified against the adapter's own expected merge result, not the raw source bytes.
            $verifyConfigMerge = $distribution.configMerges | Where-Object { $_.path -ieq $relativePath } | Select-Object -First 1
            if ($null -ne $verifyConfigMerge) {
                $verifyAdapter = Resolve-SourceAdapterScript -SourceRoot $tempRoot -AdapterPath ([string]$verifyConfigMerge.adapterScript) -Label 'Config merge'
                $verifyOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments @('-NoProfile','-NonInteractive','-File',$verifyAdapter,'-SourcePath',$sourceFiles[$relativePath],'-TargetPath',$postTargetFiles[$relativePath]) -FailureMessage "Config merge adapter failed while verifying '$relativePath'."
                $verifyResultJson = ([string]::Join([Environment]::NewLine, @($verifyOutput))) | ConvertFrom-Json
                $actualConfig = Get-Content -LiteralPath $postTargetFiles[$relativePath] -Raw -Encoding UTF8
                if ($null -eq $verifyResultJson.PSObject.Properties['content'] -or [string]$verifyResultJson.content -ne $actualConfig) { $mismatches.Add($relativePath) }
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

    if (-not $DryRun -and $isFullManifestSync -and $mismatches.Count -eq 0) {
        $assetLockPath = Join-Path $targetRootResolved '.copilottools/asset-lock.json'
        New-Item -ItemType Directory -Path (Split-Path -Parent $assetLockPath) -Force | Out-Null
        $assetLock = [ordered]@{
            schemaVersion = 2
            sourceRepo = $SourceRepo
            sourceRef = $SourceRef
            sourceRevision = $sourceCommit
            manifestHash = (Get-RuntimeLayerHash -Root $targetRootResolved)
        }
        $assetLock | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $assetLockPath -Encoding UTF8
    }

    $categoryCounts = [ordered]@{}
    $categoryNames = @('shared') + @($distribution.categories | ForEach-Object { [string]$_.name })
    foreach ($categoryName in @($categoryNames | Select-Object -Unique)) {
        $categoryCounts[$categoryName] = [ordered]@{ added = 0; changed = 0; deleted = 0 }
    }
    foreach ($kind in @(@('added', $added), @('changed', $changed), @('deleted', $deleted))) {
        foreach ($path in $kind[1]) {
            $category = Resolve-CopilotToolsAssetCategory -Distribution $distribution -Path $path
            if (-not $categoryCounts.Contains($category)) { $categoryCounts[$category] = [ordered]@{ added = 0; changed = 0; deleted = 0 } }
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
        forbiddenArtifacts = @($forbiddenArtifacts)
        nestedSdlcStatusFiles = @($nestedSdlcStatusFiles)
        verification = $(if ($mismatches.Count -eq 0) { 'passed' } else { 'failed' })
        mismatches = @($mismatches)
        runtimePostSync = $runtimePostSyncResults
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
) {
                $sourceOnlyFile = Join-Path $tempRoot $relativePath
                if ((Test-Path -LiteralPath $sourceOnlyFile -PathType Leaf) -and
                    (Get-FileHash -LiteralPath $sourceOnlyFile -Algorithm SHA256).Hash -eq
                    (Get-FileHash -LiteralPath $targetFiles[$relativePath] -Algorithm SHA256).Hash) {
                    $managedIgnoreOwned = $true
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

    # Retired legacy directories are data-driven, not hard-coded per runtime.
    # A hidden file or even an empty subdirectory counts as user-owned content.
    foreach ($legacy in @($distribution.config.retiredLegacyDirectories)) {
        if ($null -eq $legacy) { continue }
        $legacyPath = Convert-ToRepoRelativePath -Path ([string]$legacy.path)
        $replacementPath = Convert-ToRepoRelativePath -Path ([string]$legacy.replacedBy)
        if ([string]::IsNullOrWhiteSpace($legacyPath) -or
            $legacyPath -match '(^|/)\.\.(/|$)' -or
            [IO.Path]::IsPathRooted($legacyPath)) {
            throw "Unsafe retired legacy directory path in distribution manifest: '$legacyPath'."
        }
        if ($Scope.Count -gt 0 -and
            -not (Test-PathInSyncScope -Path $legacyPath -Scope $normalizedScope) -and
            -not (Test-PathInSyncScope -Path $replacementPath -Scope $normalizedScope)) {
            continue
        }

        $staleDirectory = Join-Path $targetRootResolved $legacyPath
        if (-not (Test-Path -LiteralPath $staleDirectory -PathType Container)) {
            if (Test-Path -LiteralPath $staleDirectory) {
                Write-Warning "Stale '$legacyPath' is not a directory. Manually migrate to '$replacementPath'."
            }
            continue
        }

        $children = @(Get-ChildItem -LiteralPath $staleDirectory -Force -ErrorAction Stop)
        if ($children.Count -gt 0) {
            Write-Warning "Stale '$legacyPath' contains $($children.Count) item(s). Manually migrate to '$replacementPath'; nothing was removed."
            continue
        }

        if (-not $DryRun) {
            # Do not recurse: content created after enumeration must not be deleted.
            Remove-Item -LiteralPath $staleDirectory -Force -ErrorAction Stop
            if (Test-Path -LiteralPath $staleDirectory) {
                throw "Failed to remove stale directory '$legacyPath': path still exists."
            }
        }
        $changed.Add($legacyPath)
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
            -SeedOnlyKeys (Get-SeedOnlyConfigKeys -Distribution $distribution -RelativePath $mergeRelPath) `
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

    # Runtime-owned post-sync migrations are declared by manifests.
    $runtimePostSyncResults = [ordered]@{}
    foreach ($postSync in @($distribution.postSyncAdapters)) {
        # A narrow -Scope must not trigger runtime migrations for assets it does not cover.
        if (-not (Test-ScopeIntersectsPaths -Scope $normalizedScope -Paths @($postSync.ownedPaths))) {
            $runtimePostSyncResults[[string]$postSync.runtime] = [ordered]@{ skipped = $true; reason = 'runtime assets are outside the requested scope' }
            continue
        }
        $adapterPath = Resolve-SourceAdapterScript -SourceRoot $tempRoot -AdapterPath ([string]$postSync.adapterScript) -Label "Runtime post-sync ($($postSync.runtime))"
        $adapterArguments = @('-NoProfile','-NonInteractive','-File',$adapterPath,'-SourceRoot',$tempRoot,'-TargetRoot',$targetRootResolved)
        # Preview first: an adapter whose changes would escape the requested scope is skipped, not applied.
        $previewOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments ($adapterArguments + '-DryRun') -FailureMessage "Runtime post-sync adapter preview failed for '$($postSync.runtime)'."
        $previewResult = ([string]::Join([Environment]::NewLine, @($previewOutput))) | ConvertFrom-Json
        $effectiveScope = @($normalizedScope)
        foreach ($legacy in @($postSync.legacyPaths)) {
            if ($legacy -and (Test-PathInSyncScope -Path ([string]$legacy.replacedBy) -Scope $normalizedScope)) { $effectiveScope += [string]$legacy.path }
        }
        $escaping = @(@($previewResult.changedPaths) | Where-Object { $_ -and -not (Test-PathInSyncScope -Path ([string]$_) -Scope $effectiveScope) })
        if ($escaping.Count -gt 0) {
            Write-Warning "Runtime post-sync adapter '$($postSync.runtime)' skipped: it would change path(s) outside the requested scope: $($escaping -join ', ')"
            $runtimePostSyncResults[[string]$postSync.runtime] = [ordered]@{ skipped = $true; reason = 'adapter changes would escape the requested scope'; outOfScopePaths = @($escaping) }
            continue
        }
        $adapterResult = $previewResult
        if (-not $DryRun) {
            $adapterOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments $adapterArguments -FailureMessage "Runtime post-sync adapter failed for '$($postSync.runtime)'."
            $adapterResult = ([string]::Join([Environment]::NewLine, @($adapterOutput))) | ConvertFrom-Json
        }
        $runtimePostSyncResults[[string]$postSync.runtime] = $adapterResult
        foreach ($changedPath in @($adapterResult.changedPaths)) {
            if ($changedPath -and $changedPath -notin $added -and $changedPath -notin $changed) { $changed.Add([string]$changedPath) }
        }
        foreach ($warning in @($adapterResult.warnings)) { if ($warning) { Write-Warning ([string]$warning) } }
        foreach ($removedHook in @($adapterResult.removed)) { if ($removedHook) { $removedDeadHooks.Add("$($postSync.runtime): $removedHook") } }
    }

    $gitignoreEntryCount = Set-ExactGitignoreEntries `
        -Root $targetRootResolved `
        -RelativePaths @($sourceFiles.Keys) `
        -ExcludePattern $ExcludeGitignorePattern `
        -BlockedPatterns @($distribution.config.blockedBroadGitignorePatterns)

    $transientGitignoreEntryCount = Set-TransientCopilotArtifactGitignoreEntries -Root $targetRootResolved -AdditionalEntries @($distribution.transientIgnorePaths)

    # Untrack any synced files that git is still tracking despite being gitignored.
    # git rm --cached removes from the index only; the file stays on disk.
    # This prevents synced scripts from appearing as modified in git clients (Fork, VS Code).
    if (-not $DryRun) {
        $untrackedCount = 0
        # One ls-files call for the whole index instead of one git process per
        # synced file (hundreds of spawns dominated sync time on Windows). Plain
        # ls-files: untracked files yield empty output instead of stderr, which
        # Windows PowerShell 5.1 would turn into a terminating NativeCommandError
        # under EAP=Stop. Path matching follows core.ignorecase like a pathspec.
        $ignoreCase = ([string](git -C $targetRootResolved config --get core.ignorecase 2>$null)).Trim() -eq 'true'
        $trackedComparer = if ($ignoreCase) { [StringComparer]::OrdinalIgnoreCase } else { [StringComparer]::Ordinal }
        $trackedPaths = [System.Collections.Generic.HashSet[string]]::new($trackedComparer)
        $indexListing = git -C $targetRootResolved ls-files -z
        if ($LASTEXITCODE -eq 0) {
            foreach ($entry in ([string]::Join('', @($indexListing)) -split "`0")) {
                if ($entry) { [void]$trackedPaths.Add($entry) }
            }
        }
        $pathsToUntrack = [System.Collections.Generic.List[string]]::new()
        foreach ($relativePath in $sourceFiles.Keys) {
            $isExcluded = (@($ExcludeGitignorePattern | Where-Object { $relativePath -match [regex]::Escape($_) }).Count -gt 0)
            if ($isExcluded) { continue }
            $repoPath = Convert-ToRepoRelativePath -Path $relativePath
            if ($trackedPaths.Contains($repoPath)) { $pathsToUntrack.Add($repoPath) }
        }
        for ($offset = 0; $offset -lt $pathsToUntrack.Count; $offset += 50) {
            $batch = @($pathsToUntrack.GetRange($offset, [Math]::Min(50, $pathsToUntrack.Count - $offset)))
            # Native commands do not automatically honor ErrorActionPreference.
            # A failed batched rm was previously ignored and every path was
            # reported as untracked even though it remained in the git index.
            git -C $targetRootResolved rm --cached --quiet -- @batch 2>$null | Out-Null
            $removeExit = $LASTEXITCODE
            if ($removeExit -ne 0) {
                throw "Failed to remove $($batch.Count) synchronized file(s) from the git index (git rm --cached exit $removeExit); sync is incomplete."
            }
            $stillTracked = @(git -C $targetRootResolved ls-files --cached -- @batch)
            $verifyExit = $LASTEXITCODE
            if ($verifyExit -ne 0 -or $stillTracked.Count -gt 0) {
                throw "Git index verification failed after synchronized-file cleanup (exit $verifyExit, $($stillTracked.Count) tracked path(s) remain); sync is incomplete."
            }
            $untrackedCount += $batch.Count
        }
        if ($untrackedCount -gt 0) {
            Write-Host "  Untracked $untrackedCount gitignored file(s) from git index"
        }
    }

    $gitHooksConfiguration = $null
    # A file-scoped sync can update a hook or its trusted runtime dependencies.
    # Keep the installed immutable copy synchronized whenever ANY source it
    # contains intersects the requested scope (not only the .githooks folder).
    $trustedSourcePaths = @(
        '.githooks',
        '.github/scripts/sync-agent-assets.ps1',
        '.github/scripts/runtime-layer-hash.ps1',
        '.github/scripts/runtime-distribution-lib.ps1'
    )
    if (Test-ScopeIntersectsPaths -Scope $normalizedScope -Paths $trustedSourcePaths) {
        $gitHooksConfiguration = Set-RepositoryGitHooksPath -Root $targetRootResolved -SourceRoot $tempRoot -DryRun:$DryRun
    }

    # No session-tracking preflight here. Agent Sync is the one workflow exempt
    # from the session-limit stack: it delivers those scripts, it does not
    # depend on them, and running the preflight made a short, idempotent,
    # freely repeatable file sync trigger runtime-specific side effects or
    # block on runtime setup questions that have nothing to do with copying files.
    # Runtime-specific preflight tools are still shipped and run on demand
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
                    -SeedOnlyKeys (Get-SeedOnlyConfigKeys -Distribution $distribution -RelativePath $relativePath) `
                    -DryRun
                if ($verifyResult.changed) { $mismatches.Add($relativePath) }
                continue
            }

            if (-not $postTargetFiles.ContainsKey($relativePath)) {
                $mismatches.Add($relativePath)
                continue
            }

            # Runtime config merges intentionally preserve target-only settings, so the target is
            # verified against the adapter's own expected merge result, not the raw source bytes.
            $verifyConfigMerge = $distribution.configMerges | Where-Object { $_.path -ieq $relativePath } | Select-Object -First 1
            if ($null -ne $verifyConfigMerge) {
                $verifyAdapter = Resolve-SourceAdapterScript -SourceRoot $tempRoot -AdapterPath ([string]$verifyConfigMerge.adapterScript) -Label 'Config merge'
                $verifyOutput = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments @('-NoProfile','-NonInteractive','-File',$verifyAdapter,'-SourcePath',$sourceFiles[$relativePath],'-TargetPath',$postTargetFiles[$relativePath]) -FailureMessage "Config merge adapter failed while verifying '$relativePath'."
                $verifyResultJson = ([string]::Join([Environment]::NewLine, @($verifyOutput))) | ConvertFrom-Json
                $actualConfig = Get-Content -LiteralPath $postTargetFiles[$relativePath] -Raw -Encoding UTF8
                if ($null -eq $verifyResultJson.PSObject.Properties['content'] -or [string]$verifyResultJson.content -ne $actualConfig) { $mismatches.Add($relativePath) }
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

    if (-not $DryRun -and $isFullManifestSync -and $mismatches.Count -eq 0) {
        $assetLockPath = Join-Path $targetRootResolved '.copilottools/asset-lock.json'
        New-Item -ItemType Directory -Path (Split-Path -Parent $assetLockPath) -Force | Out-Null
        $assetLock = [ordered]@{
            schemaVersion = 2
            sourceRepo = $SourceRepo
            sourceRef = $SourceRef
            sourceRevision = $sourceCommit
            manifestHash = (Get-RuntimeLayerHash -Root $targetRootResolved)
        }
        $assetLock | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $assetLockPath -Encoding UTF8
    }

    $categoryCounts = [ordered]@{}
    $categoryNames = @('shared') + @($distribution.categories | ForEach-Object { [string]$_.name })
    foreach ($categoryName in @($categoryNames | Select-Object -Unique)) {
        $categoryCounts[$categoryName] = [ordered]@{ added = 0; changed = 0; deleted = 0 }
    }
    foreach ($kind in @(@('added', $added), @('changed', $changed), @('deleted', $deleted))) {
        foreach ($path in $kind[1]) {
            $category = Resolve-CopilotToolsAssetCategory -Distribution $distribution -Path $path
            if (-not $categoryCounts.Contains($category)) { $categoryCounts[$category] = [ordered]@{ added = 0; changed = 0; deleted = 0 } }
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
        forbiddenArtifacts = @($forbiddenArtifacts)
        nestedSdlcStatusFiles = @($nestedSdlcStatusFiles)
        verification = $(if ($mismatches.Count -eq 0) { 'passed' } else { 'failed' })
        mismatches = @($mismatches)
        runtimePostSync = $runtimePostSyncResults
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

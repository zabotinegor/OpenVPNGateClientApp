param(
    [string]$SourceRepo = 'https://github.com/zabotinegor/CopilotTools.git',
    [string]$SourceRef = 'main',
    [string]$TargetRoot = (Get-Location).Path,
    [string[]]$Scope = @('.github/agents', '.github/skills', '.github/tools', '.github/scripts', '.github/hooks', '.githooks', '.claude/commands', '.claude/settings.json', '.mcp.json'),
    [string[]]$PreservePattern = @('agent-sync', 'sync-copilot-assets'),
    [string[]]$ExcludeGitignorePattern = @('agent-sync', 'sync-copilot-assets', '.github/hooks/', '.githooks/', 'protect-agent-git-command'),
    [string[]]$MergeJsonPaths = @('.claude/settings.json', '.mcp.json'),
    [switch]$DryRun,
    [switch]$AllowRootMdSync
)

$ErrorActionPreference = 'Stop'

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

function Get-SectionFromSourceFile {
    param(
        [string]$SourcePath,
        [string]$BeginMarker = '<!-- BEGIN COPILOT SYNC -->',
        [string]$EndMarker = '<!-- END COPILOT SYNC -->'
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        throw "Source section file '$SourcePath' does not exist."
    }

    $sourceLines = @(Get-Content -LiteralPath $SourcePath)
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
        [string]$BeginMarker = '<!-- BEGIN COPILOT SYNC -->',
        [string]$EndMarker = '<!-- END COPILOT SYNC -->',
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

    $targetLines = @(Get-Content -LiteralPath $TargetPath)
    $beginIdx = $targetLines.IndexOf($BeginMarker)
    $endIdx = $targetLines.IndexOf($EndMarker)

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
        [string[]]$ScopePaths
    )

    $map = @{}
    foreach ($scopePath in $ScopePaths) {
        $absoluteScope = Join-Path $Root $scopePath
        if (-not (Test-Path -LiteralPath $absoluteScope)) {
            continue
        }

        if (Test-Path -LiteralPath $absoluteScope -PathType Leaf) {
            $relative = Get-RelativePath -Root $Root -Path $absoluteScope
            $map[(Convert-ToRepoRelativePath -Path $relative)] = $absoluteScope
        } else {
            Get-ChildItem -LiteralPath $absoluteScope -File -Recurse | ForEach-Object {
                $relative = Get-RelativePath -Root $Root -Path $_.FullName
                $map[(Convert-ToRepoRelativePath -Path $relative)] = $_.FullName
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
    $beginMarker = '# BEGIN synced-copilot-assets'
    $endMarker = '# END synced-copilot-assets'
    $blockedPatterns = @('/.github/agents/**', '/.github/skills/**', '/.github/tools/**', '/.github/scripts/**')
    $existing = @()
    if (Test-Path -LiteralPath $gitignorePath) {
        $existing = @(Get-Content -LiteralPath $gitignorePath)
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
        if ($line -eq $beginMarker) {
            $insideManagedBlock = $true
            continue
        }

        if ($insideManagedBlock) {
            if ($line -eq $endMarker) {
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
    $beginMarker = '# BEGIN transient-copilot-artifacts'
    $endMarker = '# END transient-copilot-artifacts'
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
        '/.claude/settings.local.json',
        '**/.claude/settings.local.json'
    )

    $existing = @()
    if (Test-Path -LiteralPath $gitignorePath) {
        $existing = @(Get-Content -LiteralPath $gitignorePath)
    }

    $next = New-Object System.Collections.Generic.List[string]
    $insideManagedBlock = $false
    foreach ($line in $existing) {
        if ($line -eq $beginMarker) {
            $insideManagedBlock = $true
            continue
        }

        if ($insideManagedBlock) {
            if ($line -eq $endMarker) {
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

function Merge-JsonSettings {
    param(
        [string]$SourcePath,
        [string]$TargetPath,
        [switch]$DryRun
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        return [pscustomobject]@{ changed = $false; action = 'source-missing' }
    }

    $sourceRaw = Get-Content -LiteralPath $SourcePath -Raw
    try {
        $sourceObj = $sourceRaw | ConvertFrom-Json
    } catch {
        Write-Warning "Failed to parse source JSON at '$SourcePath': $_"
        $sourceObj = [pscustomobject]@{}
    }
    if ($null -eq $sourceObj) { $sourceObj = [pscustomobject]@{} }

    $targetObj = [pscustomobject]@{}
    if (Test-Path -LiteralPath $TargetPath) {
        $targetContent = Get-Content -LiteralPath $TargetPath -Raw
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
if (Test-Path -LiteralPath (Join-Path $targetRootResolved '.copilottools-source')) {
    throw "Target contains source-only marker '.copilottools-source'. Remove it before syncing client-repository guards."
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
        throw 'Target must have a checked-out non-protected branch before applying synchronized agent assets.'
    }
    if ($targetBranch.ToLowerInvariant() -in @('main', 'dev', 'master', 'develop')) {
        throw "Target branch '$targetBranch' is protected. Create or switch to a non-protected branch before applying synchronized agent assets."
    }
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

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("copilottools-sync-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    Invoke-ExternalCommand -FilePath 'git' -Arguments @('clone', '--quiet', '--no-checkout', '--depth', '1', '--branch', $SourceRef, $SourceRepo, $tempRoot) -FailureMessage 'git clone failed.' | Out-Null
    Invoke-ExternalCommand -FilePath 'git' -Arguments @('-C', $tempRoot, 'checkout', '--quiet', $sourceCommit) -FailureMessage 'git checkout failed.' | Out-Null

    $sourceFiles = Get-RelativeFileMap -Root $tempRoot -ScopePaths $normalizedScope
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

        if ($isRootMd) {
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

        $isMergeJson = @($normalizedMergeJsonPaths | Where-Object { $_ -ieq $relativePath }).Count -gt 0
        if ($isMergeJson) {
            continue
        }

        $deleted.Add($relativePath)
        if (-not $DryRun) {
            Remove-Item -LiteralPath $targetFiles[$relativePath] -Force
        }
    }

    # Inject universal governance section into target AGENTS.md (marker-based; never overwrites)
    $targetAgentsMd = Join-Path $targetRootResolved 'AGENTS.md'
    $sourceAgentsCoreRules = Join-Path $tempRoot '.github/skills/shared/agents-core-rules.md'
    $agentsCoreRulesInjection = $null

    if ((Test-Path -LiteralPath $sourceAgentsCoreRules) -and (Test-Path -LiteralPath $targetAgentsMd)) {
        $agentsCoreRulesInjection = Set-FileSectionByMarkers `
            -TargetPath $targetAgentsMd `
            -SourceSectionPath $sourceAgentsCoreRules `
            -DryRun:$DryRun
        if ($agentsCoreRulesInjection.changed) {
            $changed.Add('AGENTS.md')
        }
    }

    $mergedJsonFiles = New-Object System.Collections.Generic.List[string]
    foreach ($mergeRelPath in $normalizedMergeJsonPaths) {
        $inScope = @($normalizedScope | Where-Object {
            $mergeRelPath -ieq $_ -or
            $mergeRelPath.StartsWith("$_/", [System.StringComparison]::OrdinalIgnoreCase)
        }).Count -gt 0
        if (-not $inScope) { continue }

        $sourceMergePath = Join-Path $tempRoot $mergeRelPath
        $targetMergePath = Join-Path $targetRootResolved $mergeRelPath
        $mergeResult = Merge-JsonSettings -SourcePath $sourceMergePath -TargetPath $targetMergePath -DryRun:$DryRun
        if ($mergeResult.changed) {
            $mergedJsonFiles.Add($mergeRelPath)
            if ($newMergeJsonPaths.Contains($mergeRelPath)) {
                $added.Add($mergeRelPath)
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
        mergedJsonFiles = @($mergedJsonFiles)
        agentsCoreRulesInjection = $agentsCoreRulesInjection
        gitignoreExactEntryCount = $gitignoreEntryCount
        transientGitignoreEntryCount = $transientGitignoreEntryCount
        gitHooksConfiguration = $gitHooksConfiguration
        forbiddenArtifacts = @($forbiddenArtifacts)
        nestedSdlcStatusFiles = @($nestedSdlcStatusFiles)
        verification = $(if ($mismatches.Count -eq 0) { 'passed' } else { 'failed' })
        mismatches = @($mismatches)
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

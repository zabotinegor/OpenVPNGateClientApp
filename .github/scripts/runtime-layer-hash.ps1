function Get-RuntimeLayerHash {
    param([Parameter(Mandatory = $true)][string]$Root)

    $paths = [System.Collections.Generic.List[string]]::new()
    foreach ($path in @(
        '.github/runtime-registry.json',
        '.github/runtime-parity.json',
        '.github/copilottools/distribution.json',
        '.github/scripts/runtime-distribution-lib.ps1',
        '.github/scripts/get-agent-runtime.ps1',
        '.github/scripts/get-specialist-dispatch.ps1',
        '.github/scripts/invoke-specialist.ps1',
        '.github/scripts/execute-specialist.ps1',
        '.github/scripts/resolve-specialist-model.ps1',
        '.github/scripts/get-user-interaction.ps1',
        '.github/scripts/schedule-agent-wakeup-abstraction.ps1'
    )) { $paths.Add($path) }

    foreach ($directory in @(
        '.github/copilottools/runtimes',
        '.github/schemas',
        '.github/scripts/runtime'
    )) {
        $fullDirectory = Join-Path $Root $directory
        if (-not (Test-Path -LiteralPath $fullDirectory)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $fullDirectory -File -Recurse | Sort-Object FullName)) {
            $paths.Add($file.FullName)
        }
    }

    # Host-independent material: repo-relative '/' paths, ordinal ordering and a fixed LF record
    # separator, so identical logical input hashes identically on Windows, Linux and macOS.
    $relativePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($path in $paths) {
        $full = if ([IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $Root $path }
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
        [void]$relativePaths.Add([IO.Path]::GetRelativePath($Root, $full).Replace('\', '/'))
    }
    $orderedPaths = [string[]]@($relativePaths)
    [Array]::Sort($orderedPaths, [System.StringComparer]::Ordinal)

    $material = [System.Text.StringBuilder]::new()
    foreach ($relative in $orderedPaths) {
        $full = Join-Path $Root $relative
        [void]$material.Append($relative)
        [void]$material.Append(':')
        [void]$material.Append((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash)
        [void]$material.Append("`n")
    }

    return (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($material.ToString()))) | ForEach-Object { $_.ToString('x2') }) -join ''
}

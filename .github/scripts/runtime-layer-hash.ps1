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

    $material = [System.Text.StringBuilder]::new()
    foreach ($path in @($paths | Sort-Object -Unique)) {
        $full = if ([IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $Root $path }
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
        $relative = [IO.Path]::GetRelativePath($Root, $full).Replace('\','/')
        [void]$material.Append($relative)
        [void]$material.Append(':')
        [void]$material.Append((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash)
        [void]$material.Append([Environment]::NewLine)
    }

    return (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($material.ToString()))) | ForEach-Object { $_.ToString('x2') }) -join ''
}

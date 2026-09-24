function Get-RuntimeLayerHash {
    param([Parameter(Mandatory = $true)][string]$Root)
    $paths = @('.github/runtime-registry.json')
    $runtimeDir = Join-Path $Root '.github/copilottools/runtimes'
    $schemaDir = Join-Path $Root '.github/schemas'
    if (Test-Path -LiteralPath $runtimeDir) { $paths += @(Get-ChildItem -LiteralPath $runtimeDir -Filter '*.json' -File | ForEach-Object { $_.FullName }) }
    if (Test-Path -LiteralPath $schemaDir) { $paths += @(Get-ChildItem -LiteralPath $schemaDir -Filter '*.json' -File | ForEach-Object { $_.FullName }) }
    $material = [System.Text.StringBuilder]::new()
    foreach ($path in ($paths | Sort-Object)) {
        $full = if ([IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $Root $path }
        if (Test-Path -LiteralPath $full) {
            $relative = [IO.Path]::GetRelativePath($Root, $full).Replace('\','/')
            [void]$material.Append($relative); [void]$material.Append(':'); [void]$material.Append((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash); [void]$material.Append("`n")
        }
    }
    return (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($material.ToString()))) | ForEach-Object { $_.ToString('x2') }) -join ''
}

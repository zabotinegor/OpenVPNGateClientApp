Set-StrictMode -Version Latest

function ConvertTo-CopilotToolsRepoPath {
    param([Parameter(Mandatory)][string]$Path)
    $value = $Path.Replace('\\', '/').Trim()
    while ($value.StartsWith('./')) { $value = $value.Substring(2) }
    return $value.TrimEnd('/')
}

function Test-CopilotToolsPathWithinAsset {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Asset)
    $normalizedPath = ConvertTo-CopilotToolsRepoPath $Path
    $normalizedAsset = ConvertTo-CopilotToolsRepoPath $Asset
    return $normalizedPath -ieq $normalizedAsset -or $normalizedPath.StartsWith("$normalizedAsset/", [StringComparison]::OrdinalIgnoreCase)
}

function Get-CopilotToolsRuntimeManifests {
    param([Parameter(Mandatory)][string]$Root)
    $runtimeRoot = Join-Path $Root '.github/copilottools/runtimes'
    if (-not (Test-Path -LiteralPath $runtimeRoot)) { return @() }
    return @(Get-ChildItem -LiteralPath $runtimeRoot -Filter '*.json' -File | Sort-Object Name | ForEach-Object {
        Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    })
}

function Get-CopilotToolsDistribution {
    param([Parameter(Mandatory)][string]$Root)

    $configPath = Join-Path $Root '.github/copilottools/distribution.json'
    if (-not (Test-Path -LiteralPath $configPath)) { throw "Distribution manifest is missing: $configPath" }
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int]$config.schemaVersion -ne 1) { throw "Unsupported distribution schema version '$($config.schemaVersion)'." }

    $allAssets = [System.Collections.Generic.List[string]]::new()
    $mergeJsonPaths = [System.Collections.Generic.List[string]]::new()
    $transientIgnorePaths = [System.Collections.Generic.List[string]]::new()
    $categories = [System.Collections.Generic.List[object]]::new()
    $configMerges = [System.Collections.Generic.List[object]]::new()
    $postSyncAdapters = [System.Collections.Generic.List[object]]::new()
    $runtimeEntries = [System.Collections.Generic.List[object]]::new()

    foreach ($asset in @($config.sharedAssets)) { if ($asset) { $allAssets.Add((ConvertTo-CopilotToolsRepoPath ([string]$asset))) } }
    foreach ($path in @($config.sharedMergeJsonPaths)) { if ($path) { $mergeJsonPaths.Add((ConvertTo-CopilotToolsRepoPath ([string]$path))) } }
    foreach ($path in @($config.sharedTransientIgnorePaths)) { if ($path) { $transientIgnorePaths.Add([string]$path) } }

    if ($config.PSObject.Properties['platformAssets']) {
        foreach ($property in $config.platformAssets.PSObject.Properties) {
            $assets = @($property.Value | ForEach-Object { ConvertTo-CopilotToolsRepoPath ([string]$_) })
            foreach ($asset in $assets) { $allAssets.Add($asset) }
            $categories.Add([pscustomobject]@{ name=[string]$property.Name; assets=$assets })
        }
    }
    if ($config.PSObject.Properties['assetCategories']) {
        foreach ($property in $config.assetCategories.PSObject.Properties) {
            $categories.Add([pscustomobject]@{ name=[string]$property.Name; assets=@($property.Value | ForEach-Object { ConvertTo-CopilotToolsRepoPath ([string]$_) }) })
        }
    }

    foreach ($manifest in @(Get-CopilotToolsRuntimeManifests -Root $Root)) {
        $runtimeId = [string]$manifest.id
        $dist = if ($manifest.PSObject.Properties['distribution']) { $manifest.distribution } else { [pscustomobject]@{} }
        $assets = @()
        if ($dist.PSObject.Properties['assets']) { $assets = @($dist.assets | ForEach-Object { ConvertTo-CopilotToolsRepoPath ([string]$_) }) }
        foreach ($asset in $assets) { $allAssets.Add($asset) }
        if ($dist.PSObject.Properties['mergeJsonPaths']) {
            foreach ($path in @($dist.mergeJsonPaths)) { $mergeJsonPaths.Add((ConvertTo-CopilotToolsRepoPath ([string]$path))) }
        }
        if ($dist.PSObject.Properties['transientIgnorePaths']) {
            foreach ($path in @($dist.transientIgnorePaths)) { $transientIgnorePaths.Add([string]$path) }
        }
        if ($dist.PSObject.Properties['configMerges']) {
            foreach ($entry in @($dist.configMerges)) {
                $configMerges.Add([pscustomobject]@{
                    runtime=$runtimeId
                    path=ConvertTo-CopilotToolsRepoPath ([string]$entry.path)
                    adapterScript=ConvertTo-CopilotToolsRepoPath ([string]$entry.adapterScript)
                })
            }
        }
        if ($dist.PSObject.Properties['postSyncAdapter'] -and -not [string]::IsNullOrWhiteSpace([string]$dist.postSyncAdapter)) {
            $postSyncAdapters.Add([pscustomobject]@{
                runtime=$runtimeId
                adapterScript=ConvertTo-CopilotToolsRepoPath ([string]$dist.postSyncAdapter)
            })
        }
        $category = if ($dist.PSObject.Properties['reportCategory'] -and $dist.reportCategory) { [string]$dist.reportCategory } else { $runtimeId }
        if ($assets.Count -gt 0) { $categories.Add([pscustomobject]@{ name=$category; assets=$assets }) }
        $includeInParity = -not ($dist.PSObject.Properties['includeInParity'] -and $dist.includeInParity -eq $false)
        $runtimeEntries.Add([pscustomobject]@{
            id=$runtimeId
            manifest=$manifest
            assets=$assets
            category=$category
            includeInParity=$includeInParity
            commandSurface=if($dist.PSObject.Properties['commandSurface']){$dist.commandSurface}else{$null}
            requiredConfigProperties=if($dist.PSObject.Properties['requiredConfigProperties']){@($dist.requiredConfigProperties)}else{@()}
        })
    }

    return [pscustomobject]@{
        config=$config
        runtimes=@($runtimeEntries)
        allAssets=@($allAssets | Where-Object { $_ } | Select-Object -Unique)
        mergeJsonPaths=@($mergeJsonPaths | Where-Object { $_ } | Select-Object -Unique)
        transientIgnorePaths=@($transientIgnorePaths | Where-Object { $_ } | Select-Object -Unique)
        excludeSyncPathPatterns=@($config.excludeSyncPathPatterns)
        categories=@($categories)
        configMerges=@($configMerges)
        postSyncAdapters=@($postSyncAdapters)
    }
}

function Resolve-CopilotToolsAssetCategory {
    param([Parameter(Mandatory)][object]$Distribution, [Parameter(Mandatory)][string]$Path)
    $bestName = 'shared'
    $bestLength = -1
    foreach ($category in @($Distribution.categories)) {
        foreach ($asset in @($category.assets)) {
            if ((Test-CopilotToolsPathWithinAsset -Path $Path -Asset ([string]$asset)) -and ([string]$asset).Length -gt $bestLength) {
                $bestName = [string]$category.name
                $bestLength = ([string]$asset).Length
            }
        }
    }
    return $bestName
}

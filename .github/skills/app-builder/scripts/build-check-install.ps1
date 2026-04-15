param(
    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'debug',

    [ValidateSet('mobile', 'tv', 'both')]
    [string]$Target = 'mobile',

    [string]$DeviceSerial,

    [switch]$AllowReleaseInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
$srcRoot = Join-Path $repoRoot 'src'
$packageName = 'com.yahorzabotsin.openvpnclientgate'

function Invoke-GradleBuild {
    param(
        [string]$SrcRoot,
        [string]$BuildType,
        [string]$Target
    )

    Push-Location $SrcRoot
    try {
        if ($BuildType -eq 'release') {
            if ($Target -eq 'both') {
                & .\gradlew assembleReleaseApp
            } elseif ($Target -eq 'mobile') {
                & .\gradlew :mobile:assembleRelease
            } else {
                & .\gradlew :tv:assembleRelease
            }
        } else {
            if ($Target -eq 'both') {
                & .\gradlew assembleDebugApp
            } elseif ($Target -eq 'mobile') {
                & .\gradlew :mobile:assembleDebug
            } else {
                & .\gradlew :tv:assembleDebug
            }
        }
    }
    finally {
        Pop-Location
    }
}

function Get-LatestApk {
    param(
        [string]$ModuleName,
        [string]$BuildType
    )

    $apkDir = Join-Path $srcRoot "$ModuleName\build\outputs\apk\$BuildType"
    if (-not (Test-Path $apkDir)) {
        throw "APK directory not found: $apkDir"
    }

    $apk = Get-ChildItem -Path $apkDir -Filter '*.apk' -File -Recurse |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if (-not $apk) {
        throw "No APK produced for module '$ModuleName' in '$apkDir'."
    }

    return $apk.FullName
}

function Get-OnlineAdbDevices {
    $adbOutput = adb devices
    $deviceLines = $adbOutput | Select-Object -Skip 1
    $online = @()

    foreach ($line in $deviceLines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $trimmed = $line.Trim()
        if ($trimmed -match '^(.+)\s+device$') {
            $online += $Matches[1]
        }
    }

    return $online
}

if ($BuildType -eq 'release' -and -not $AllowReleaseInstall.IsPresent) {
    throw 'Release install is blocked by default. Re-run with -AllowReleaseInstall after explicit user confirmation.'
}

$gradleTasks = @()
if ($BuildType -eq 'debug') {
    if ($Target -eq 'both') { $gradleTasks += 'assembleDebugApp' }
    elseif ($Target -eq 'mobile') { $gradleTasks += ':mobile:assembleDebug' }
    elseif ($Target -eq 'tv') { $gradleTasks += ':tv:assembleDebug' }
} else {
    if ($Target -eq 'both') { $gradleTasks += 'assembleReleaseApp' }
    elseif ($Target -eq 'mobile') { $gradleTasks += ':mobile:assembleRelease' }
    elseif ($Target -eq 'tv') { $gradleTasks += ':tv:assembleRelease' }
}

Invoke-GradleBuild -SrcRoot $srcRoot -BuildType $BuildType -Target $Target

$apkPaths = @()
if ($Target -eq 'mobile' -or $Target -eq 'both') {
    $apkPaths += Get-LatestApk -ModuleName 'mobile' -BuildType $BuildType
}
if ($Target -eq 'tv' -or $Target -eq 'both') {
    $apkPaths += Get-LatestApk -ModuleName 'tv' -BuildType $BuildType
}

$devices = Get-OnlineAdbDevices
if ($devices.Count -eq 0) {
    throw 'No online ADB devices found.'
}

$selectedDevices = @()
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($devices.Count -gt 1) {
        $list = $devices -join ', '
        throw "Multiple ADB devices found: $list. Specify -DeviceSerial."
    }
    $selectedDevices += $devices[0]
} else {
    if ($devices -notcontains $DeviceSerial) {
        $list = $devices -join ', '
        throw "Requested device '$DeviceSerial' not found. Online: $list"
    }
    $selectedDevices += $DeviceSerial
}

$installResults = @()
$verifyResults = @()

foreach ($serial in $selectedDevices) {
    foreach ($apkPath in $apkPaths) {
        $installOut = adb -s $serial install -r "$apkPath" | Out-String
        $installResults += [pscustomobject]@{
            serial = $serial
            apk = $apkPath
            output = $installOut.Trim()
        }
    }

    $versionOut = adb -s $serial shell dumpsys package $packageName | Select-String 'versionName=|versionCode=' | ForEach-Object { $_.Line }
    $verifyResults += [pscustomobject]@{
        serial = $serial
        package = $packageName
        info = ($versionOut -join '; ')
    }
}

$result = [pscustomobject]@{
    buildType = $BuildType
    target = $Target
    gradleTasks = $gradleTasks
    apks = $apkPaths
    detectedDevices = $devices
    selectedDevices = $selectedDevices
    installs = $installResults
    verification = $verifyResults
}

$result | ConvertTo-Json -Depth 8

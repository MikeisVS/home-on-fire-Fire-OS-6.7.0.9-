#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious.
<#
.SYNOPSIS
Explicitly install a reviewed AFTA APK, grant Usage Access/loading cover, and open setup.
.DESCRIPTION
Use -WhatIf for read-only preflight. Does not alter Amazon packages, the upstream
installation, other accessibility services, or default-home preferences.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)][string] $Serial,
    [string] $ApkPath,
    [string] $ExpectedSha256,
    [switch] $EnableHomeIntentLog,
    [string] $AdbPath,
    [string] $SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $JdkRoot = 'C:\Program Files\Java\jdk-25'
)
$ErrorActionPreference = 'Stop'
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '..\artifacts\home-on-fire-afta.apk' }
. (Join-Path $PSScriptRoot 'Adb.Common.ps1')
Initialize-AftaAdb $Serial $AdbPath
if ((Get-AftaProperty 'ro.product.model') -ne 'AFTA' -or (Get-AftaProperty 'ro.build.version.sdk') -ne '25') {
    throw 'This install script targets AFTA/API 25. See the report for separate Fire OS 7/8 testing.'
}
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
# Direct read avoids Windows PowerShell 5.1 Get-FileHash inheriting -WhatIf.
$sha = [Security.Cryptography.SHA256]::Create()
$apkStream = [IO.File]::OpenRead($ApkPath)
try {
    $hash = [BitConverter]::ToString($sha.ComputeHash($apkStream)).Replace('-', '').ToLowerInvariant()
} finally {
    $apkStream.Dispose()
    $sha.Dispose()
}
if (-not $ExpectedSha256) {
    $sidecar = $ApkPath + '.sha256'
    if (-not (Test-Path -LiteralPath $sidecar)) { throw 'Supply -ExpectedSha256 from the reviewed build, or retain its .sha256 sidecar.' }
    $ExpectedSha256 = ((Get-Content -LiteralPath $sidecar -Raw).Trim() -split '\s+')[0]
}
if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$' -or $hash -ne $ExpectedSha256.ToLowerInvariant()) {
    throw 'APK hash differs from the reviewed build.'
}
$tools = Join-Path $SdkRoot 'build-tools\36.1.0'
$badging = (Invoke-AftaNative -File (Join-Path $tools 'aapt2.exe') -Arguments @('dump', 'badging', $ApkPath)).Text
if ($badging -notmatch "package: name='io.github.toolicious.homeonfire.afta'" -or
    $badging -notmatch "sdkVersion:'21'" -or $badging -notmatch "targetSdkVersion:'34'") {
    throw 'APK package or SDK contract does not match this experiment.'
}
if ($badging -notmatch "uses-permission: name='android.permission.SYSTEM_ALERT_WINDOW'") {
    throw 'This installer requires an APK declaring the Home loading cover permission. See docs\INSTALL-AFTA.md for installation and recovery.'
}
if ($EnableHomeIntentLog -and $badging -notmatch "uses-permission: name='android.permission.READ_LOGS'") {
    throw 'This APK does not declare the optional system Home intent log permission.'
}
$signature = Invoke-AftaNative -File (Join-Path $JdkRoot 'bin\java.exe') -Arguments @(
    '-jar', (Join-Path $tools 'lib\apksigner.jar'), 'verify', '--verbose', '--print-certs', $ApkPath)
$newIdentity = Get-AftaSignerIdentity $signature.Text
$existingPath = (Invoke-AftaAdb -Arguments @('shell', 'pm', 'path', $AftaPackage)).Text.Trim()
if ($existingPath) {
    $basePath = @($existingPath -split '\r?\n' | Where-Object { $_ -match '^package:.+/base\.apk$' })
    if ($basePath.Count -ne 1) { throw 'Cannot identify the installed base APK for signing preflight.' }
    # Only a temporary host read: compare before ShouldProcess/force-stop, including
    # under -WhatIf. A missing original key must not interrupt the installed monitor.
    $signerTemp = Join-Path ([IO.Path]::GetTempPath()) ('afta-signer-' + [guid]::NewGuid().ToString('N') + '.apk')
    try {
        $null = Invoke-AftaAdb -Arguments @('pull', $basePath[0].Substring(8), $signerTemp)
        $installedSignature = Invoke-AftaNative -File (Join-Path $JdkRoot 'bin\java.exe') -Arguments @(
            '-jar', (Join-Path $tools 'lib\apksigner.jar'), 'verify', '--verbose', '--print-certs', $signerTemp)
        if ((Get-AftaSignerIdentity $installedSignature.Text) -cne $newIdentity) {
            throw 'APK signer differs from the installed app. Nothing was stopped or replaced. Rebuild with the existing development key.'
        }
    } finally {
        if (Test-Path -LiteralPath $signerTemp) { Remove-Item -LiteralPath $signerTemp -Force -WhatIf:$false }
    }
}
Write-Host "Reviewed APK SHA-256: $hash"
Write-Host "Verified signing identity: $newIdentity"
Write-Host "Target: $Serial / AFTA / API 25"
Write-Host 'Fresh setup selects installed Couchy and enables automatic Home, boot and wake behavior.'
Write-Host 'Recovery: retain the previous APK and signing key; see docs\INSTALL-AFTA.md.'
$installAction = 'Install APK, grant Usage Access/loading cover and open automatic setup'
if ($EnableHomeIntentLog) {
    Write-Host 'Explicit option: grant READ_LOGS for the filtered system Home intent listener.'
    $installAction += '; grant READ_LOGS for Settings Home'
}
if ($PSCmdlet.ShouldProcess("$Serial ($AftaPackage)", $installAction)) {
    $directory = Join-Path $PSScriptRoot ('..\artifacts\install-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $baseline = [ordered]@{
        serial = $Serial; apkSha256 = $hash; enableHomeIntentLog = [bool]$EnableHomeIntentLog
        enabledAccessibilityServices = (Invoke-AftaAdb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).Text.Trim()
        accessibilityEnabled = (Invoke-AftaAdb -Arguments @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')).Text.Trim()
        upstreamPackage = (Invoke-AftaAdb -Arguments @('shell', 'pm', 'path', 'io.github.toolicious.homeonfire') -AllowFailure).Text.Trim()
        experimentalPackage = (Invoke-AftaAdb -Arguments @('shell', 'pm', 'path', $AftaPackage) -AllowFailure).Text.Trim()
        usageAppOp = (Invoke-AftaAdb -Arguments @('shell', 'appops', 'get', $AftaPackage, 'GET_USAGE_STATS') -AllowFailure).Text.Trim()
        overlayAppOp = (Invoke-AftaAdb -Arguments @('shell', 'appops', 'get', $AftaPackage, 'SYSTEM_ALERT_WINDOW')).Text.Trim()
    }
    $baseline['overlayMode'] = Get-AftaAppOpMode 'SYSTEM_ALERT_WINDOW' $baseline.overlayAppOp
    [IO.File]::WriteAllText((Join-Path $directory 'before.json'), ($baseline | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    if ($baseline.experimentalPackage) {
        $null = Invoke-AftaAdb -Arguments @('shell', 'am', 'force-stop', $AftaPackage)
    }
    try {
        $installed = Invoke-AftaAdb -Arguments @('install', '-r', $ApkPath)
        if ($installed.Text -notmatch 'Success') { throw "Install did not report Success: $($installed.Text)" }
    } catch {
        $installFailure = $_
        if ($baseline.experimentalPackage) {
            $recovery = Invoke-AftaAdb -Arguments @('shell', 'am', 'start', '-n',
                "$AftaPackage/io.github.toolicious.homeonfire.MainActivity") -AllowFailure
            [IO.File]::WriteAllText((Join-Path $directory 'failure-recovery.txt'),
                $recovery.Text + $recovery.ErrorText, [Text.UTF8Encoding]::new($false))
        }
        throw $installFailure
    }
    $null = Invoke-AftaAdb -Arguments @('shell', 'appops', 'set', $AftaPackage, 'GET_USAGE_STATS', 'allow')
    $usageMode = (Invoke-AftaAdb -Arguments @('shell', 'appops', 'get', $AftaPackage, 'GET_USAGE_STATS')).Text
    if ($usageMode -notmatch 'GET_USAGE_STATS:\s*allow') {
        throw "Usage Access was not confirmed; installation completed but setup was not opened. $usageMode"
    }
    $overlayGrant = Invoke-AftaAdb -Arguments @('shell', 'appops', 'set', $AftaPackage, 'SYSTEM_ALERT_WINDOW', 'allow') -AllowFailure
    $overlayCheck = Invoke-AftaAdb -Arguments @('shell', 'appops', 'get', $AftaPackage, 'SYSTEM_ALERT_WINDOW') -AllowFailure
    $overlayAllowed = $overlayGrant.ExitCode -eq 0 -and $overlayCheck.ExitCode -eq 0 -and
        $overlayCheck.Text -match '(?m)^\s*SYSTEM_ALERT_WINDOW:\s*allow\b'
    $homeLogResult = ''
    $homeLogAllowed = -not $EnableHomeIntentLog
    if ($EnableHomeIntentLog) {
        $homeLogGrant = Invoke-AftaAdb -Arguments @('shell', 'pm', 'grant', $AftaPackage, 'android.permission.READ_LOGS') -AllowFailure
        $homeLogCheck = Invoke-AftaAdb -Arguments @('shell', 'dumpsys', 'package', $AftaPackage) -AllowFailure
        $homeLogAllowed = $homeLogGrant.ExitCode -eq 0 -and $homeLogCheck.ExitCode -eq 0 -and
            $homeLogCheck.Text -match '(?m)^\s*android\.permission\.READ_LOGS:\s*granted=true\b'
        $homeLogResult = "READ_LOGS granted: $homeLogAllowed`r`n" + $homeLogGrant.ErrorText
    }
    $opened = Invoke-AftaAdb -Arguments @('shell', 'am', 'start', '-n',
        "$AftaPackage/io.github.toolicious.homeonfire.MainActivity")
    if (($opened.Text + $opened.ErrorText) -match 'Error:|Exception') {
        throw "Setup activity did not open: $($opened.Text) $($opened.ErrorText)"
    }
    [IO.File]::WriteAllText((Join-Path $directory 'result.txt'),
        $installed.Text + $usageMode + $overlayGrant.Text + $overlayGrant.ErrorText +
        $overlayCheck.Text + $overlayCheck.ErrorText + $homeLogResult + $opened.Text, [Text.UTF8Encoding]::new($false))
    if (-not $homeLogAllowed) {
        throw "READ_LOGS was not confirmed. Setup was opened; ordinary UsageEvents redirection remains available. Inspect $directory."
    }
    if (-not $overlayAllowed) {
        throw "Loading cover permission was not confirmed. Automatic setup was opened so redirection remains available. Inspect $directory; the cover will report unavailable."
    }
    Write-Host 'Installed and opened setup. This does not establish runtime compatibility.'
    Write-Host "Installation record: $directory"
}

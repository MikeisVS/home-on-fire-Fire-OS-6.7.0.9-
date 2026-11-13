#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious.
<#
.SYNOPSIS
Collect read-only device status and bounded experimental logs. Does not launch or stop apps.
.DESCRIPTION
Private JSONL files use run-as in the development APK. For a consistent UI snapshot,
use Export diagnostic snapshot and adb pull the displayed directory instead.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Serial,
    [string] $AdbPath,
    [string] $OutputDirectory,
    [switch] $IncludeLogcat
)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $PSScriptRoot ('..\diagnostics\' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
. (Join-Path $PSScriptRoot 'Adb.Common.ps1')
Initialize-AftaAdb $Serial $AdbPath
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$properties = [ordered]@{ serial = $Serial; collectedAtUtc = [DateTime]::UtcNow.ToString('o') }
foreach ($name in @('ro.product.model', 'ro.product.device', 'ro.product.manufacturer',
    'ro.build.version.name', 'ro.build.version.release', 'ro.build.version.sdk',
    'ro.build.version.incremental', 'ro.build.fingerprint', 'ro.product.cpu.abilist')) {
    $properties[$name] = Get-AftaProperty $name
}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'device.json'), ($properties | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$captures = @(
    @{ Name = 'accessibility.txt'; Args = @('shell', 'dumpsys', 'accessibility') },
    @{ Name = 'configured-services.txt'; Args = @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') },
    @{ Name = 'accessibility-master.txt'; Args = @('shell', 'settings', 'get', 'secure', 'accessibility_enabled') },
    @{ Name = 'usage-permission.txt'; Args = @('shell', 'appops', 'get', $AftaPackage, 'GET_USAGE_STATS') },
    @{ Name = 'experimental-package.txt'; Args = @('shell', 'dumpsys', 'package', $AftaPackage) },
    @{ Name = 'upstream-package.txt'; Args = @('shell', 'dumpsys', 'package', 'io.github.toolicious.homeonfire') },
    @{ Name = 'couchy-package.txt'; Args = @('shell', 'dumpsys', 'package', 'com.conreo.couchytv') },
    @{ Name = 'monitor-service.txt'; Args = @('shell', 'dumpsys', 'activity', 'services', $AftaPackage) },
    @{ Name = 'home-resolution.txt'; Args = @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') },
    @{ Name = 'power.txt'; Args = @('shell', 'dumpsys', 'power') },
    @{ Name = 'window-policy.txt'; Args = @('shell', 'dumpsys', 'window', 'policy') }
)
foreach ($capture in $captures) {
    $result = Invoke-AftaAdb -Arguments $capture.Args -AllowFailure
    [IO.File]::WriteAllText((Join-Path $OutputDirectory $capture.Name),
        $result.Text + $result.ErrorText, [Text.UTF8Encoding]::new($false))
}
foreach ($name in @('events.jsonl', 'events.1.jsonl', 'events.2.jsonl', 'events.3.jsonl')) {
    $output = Join-Path $OutputDirectory $name
    $result = Invoke-AftaAdb -Arguments @('exec-out', 'run-as', $AftaPackage, 'cat', "files/diagnostics/$name") -AllowFailure -OutputFile $output
    if ($result.ExitCode -ne 0) {
        $failureText = [IO.File]::ReadAllText($output) + $result.ErrorText
        [IO.File]::WriteAllText(($output + '.error.txt'), $failureText, [Text.UTF8Encoding]::new($false))
        # Do not leave shell error text masquerading as a JSONL log.
        [IO.File]::WriteAllText($output, '', [Text.UTF8Encoding]::new($false))
    }
}
if ($IncludeLogcat) {
    $result = Invoke-AftaAdb -Arguments @('logcat', '-d', '-t', '500', '-v', 'threadtime', 'HomeOnFire:V', '*:S') -AllowFailure
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'optional-logcat.txt'),
        $result.Text + $result.ErrorText, [Text.UTF8Encoding]::new($false))
}
Write-Host "Read-only diagnostics saved: $OutputDirectory"
Write-Host 'Missing run-as logs are recorded as .error.txt files; they do not establish a firmware limitation.'

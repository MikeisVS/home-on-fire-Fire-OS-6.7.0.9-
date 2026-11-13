#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious.
<#
.SYNOPSIS
Stop and remove only the experimental package. Use -WhatIf for read-only preflight.
.DESCRIPTION
Leaves upstream Home on Fire, Couchy, Amazon packages and other accessibility entries intact.
Uninstall removes experimental preferences and logs; export diagnostics first if needed.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param([Parameter(Mandatory = $true)][string] $Serial, [string] $AdbPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Adb.Common.ps1')
Initialize-AftaAdb $Serial $AdbPath
$installed = (Invoke-AftaAdb -Arguments @('shell', 'pm', 'path', $AftaPackage) -AllowFailure).Text.Trim()
$current = (Invoke-AftaAdb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).Text.Trim()
if ($PSCmdlet.ShouldProcess("$Serial ($AftaPackage)", 'Stop and uninstall experimental app; remove only its accessibility entries')) {
    if ($installed) { $null = Invoke-AftaAdb -Arguments @('shell', 'am', 'force-stop', $AftaPackage) }
    # Re-read after any confirmation delay; never restore a stale full settings snapshot.
    $current = (Invoke-AftaAdb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).Text.Trim()
    $kept = Remove-AftaAccessibilityEntry $current
    if ($current -ne 'null' -and $current -ne $kept) {
        $command = 'settings put secure enabled_accessibility_services ' + (ConvertTo-RemoteLiteral $kept)
        $null = Invoke-AftaAdb -Arguments @('shell', $command)
        $after = (Invoke-AftaAdb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).Text.Trim()
        if ($after -ne $kept) { throw 'Accessibility cleanup could not be verified. Other entries were not intentionally changed.' }
    }
    if ($installed) {
        $removed = Invoke-AftaAdb -Arguments @('uninstall', $AftaPackage)
        if ($removed.Text -notmatch 'Success') { throw "Uninstall did not report Success: $($removed.Text)" }
    }
    $remaining = (Invoke-AftaAdb -Arguments @('shell', 'pm', 'path', $AftaPackage) -AllowFailure).Text.Trim()
    if ($remaining) { throw 'Experimental package is still installed.' }
    Write-Host 'Experimental package removed. Upstream Home on Fire, Couchy and Amazon packages were not changed.'
}

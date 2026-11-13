#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\scripts\Adb.Common.ps1')
$Assertions = 0
function Assert-Equal([string] $Actual, [string] $Expected, [string] $Message) {
    if ($Actual -cne $Expected) { throw "$Message : [$Actual] != [$Expected]" }
    $script:Assertions++
}
$upstream = 'io.github.toolicious.homeonfire/.HijackService'
$other = 'com.example.screenreader/.Reader'
$mine = 'io.github.toolicious.homeonfire.afta/io.github.toolicious.homeonfire.HijackService'
Assert-Equal (Remove-AftaAccessibilityEntry ($upstream + ':' + $mine + ':' + $other)) ($upstream + ':' + $other) 'Only experimental entry removed'
Assert-Equal (Remove-AftaAccessibilityEntry ($upstream + ':' + $other)) ($upstream + ':' + $other) 'Unrelated list preserved'
Assert-Equal (Remove-AftaAccessibilityEntry $mine) '' 'Single experimental entry removed'
Assert-Equal (Remove-AftaAccessibilityEntry 'io.github.toolicious.homeonfire.afta/.HijackService') '' 'Short form removed'
Assert-Equal (Remove-AftaAccessibilityEntry 'io.github.toolicious.homeonfire.afta.other/.Service') 'io.github.toolicious.homeonfire.afta.other/.Service' 'Similar package preserved'
Assert-Equal (Remove-AftaAccessibilityEntry 'null') '' 'Absent setting handled'
Assert-Equal (Remove-AftaAccessibilityEntry ($other + ':' + $other + ':' + $mine)) ($other + ':' + $other) 'Other duplicates and order preserved'
Assert-Equal (ConvertTo-RemoteLiteral 'pkg/.Class$Inner') ("'" + 'pkg/.Class$Inner' + "'") 'Shell literal protects dollar characters'
Assert-Equal (Get-AftaAppOpMode 'SYSTEM_ALERT_WINDOW' 'No operations.') 'default' 'Unset overlay mode is retained as default'
Assert-Equal (Get-AftaAppOpMode 'SYSTEM_ALERT_WINDOW' 'SYSTEM_ALERT_WINDOW: allow; time=+1m') 'allow' 'Allowed overlay mode is parsed without timestamps'
Assert-Equal (Get-AftaAppOpMode 'SYSTEM_ALERT_WINDOW' "GET_USAGE_STATS: allow`nSYSTEM_ALERT_WINDOW: deny") 'deny' 'Other permission allow does not imply overlay allow'
Assert-Equal (Get-AftaAppOpMode 'SYSTEM_ALERT_WINDOW' 'SYSTEM_ALERT_WINDOW: ignore') 'ignore' 'Ignored overlay mode is preserved for rollback'
$parseRejected = $false
try { $null = Get-AftaAppOpMode 'SYSTEM_ALERT_WINDOW' 'Error: unknown operation' } catch { $parseRejected = $true }
Assert-Equal ([string]$parseRejected) 'True' 'Unexpected permission output is not silently treated as a grant'
$prefs = ConvertFrom-AftaPreferences '<map><boolean name="usage_amazon_cleanup_enabled" value="false" /><boolean name="usage_suspended" value="true" /><string name="target_package">com.conreo.couchytv</string></map>'
Assert-Equal ([string]$prefs['usage_amazon_cleanup_enabled']) 'False' 'False saved boolean is not mistaken for an empty string'
Assert-Equal ($prefs['usage_amazon_cleanup_enabled'].GetType().FullName) 'System.Boolean' 'Saved booleans retain their type'
Assert-Equal ([string]$prefs['usage_suspended']) 'True' 'True saved boolean is recognized through LocalName'
Assert-Equal $prefs['target_package'] 'com.conreo.couchytv' 'Saved target string is preserved'
$invalidBooleanRejected = $false
try { $null = ConvertFrom-AftaPreferences '<map><boolean name="cleanup" value="unknown" /></map>' } catch { $invalidBooleanRejected = $true }
Assert-Equal ([string]$invalidBooleanRejected) 'True' 'Malformed boolean cannot silently imply cleanup is off'
$trialParseErrors = $null
$trialTokens = $null
$null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '..\scripts\Test-AmazonCleanup.ps1'), [ref]$trialTokens, [ref]$trialParseErrors)
Assert-Equal ([string]$trialParseErrors.Count) '0' 'Measurement harness parses before device use'
$beforeRebootLog = @([pscustomobject]@{event='old-boot';elapsedTimeMs=9000}, [pscustomobject]@{event='new-boot-checkpoint';elapsedTimeMs=100})
$afterRebootLog = $beforeRebootLog + @([pscustomobject]@{event='cleanup_requested';elapsedTimeMs=200})
$appended = @(Get-AftaAppendedEvents $beforeRebootLog $afterRebootLog)
Assert-Equal ([string]$appended.Count) '1' 'Historical larger uptime cannot hide appended events after a reboot'
Assert-Equal $appended[0].event 'cleanup_requested' 'Only the actual appended event is counted'
Assert-Equal ([string]@(Get-AftaAppendedEvents @() $afterRebootLog).Count) '3' 'Initially empty diagnostics retain all captured events'
$rotatedRejected = $false
try { $null = Get-AftaAppendedEvents $beforeRebootLog @([pscustomobject]@{event='other';elapsedTimeMs=300}) } catch { $rotatedRejected = $true }
Assert-Equal ([string]$rotatedRejected) 'True' 'A missing checkpoint is invalid evidence rather than zero dispatches'
Write-Output "Script helpers: $Assertions behavioral assertions passed"

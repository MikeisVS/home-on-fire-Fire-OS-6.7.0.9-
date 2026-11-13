#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious.
<#
.SYNOPSIS
Run paired, temporary on-Cube cleanup trials and save local PID/PSS/focus evidence.
.DESCRIPTION
Needs the reviewed afta.5 APK and an explicit AFTA/API25 serial. Never force-stops
Amazon, clears package data, or changes its enabled/default-home state. Cleanup
is always switched off on exit; acceptance and human checks are assessed separately.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Serial,
    [ValidateRange(1,5)][int] $PairCount = 5,
    [ValidateRange(1,5)][int] $StartPair = 1,
    [string] $PriorMeasurementsPath,
    [string] $OutputDirectory,
    [string] $AdbPath
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Adb.Common.ps1')
Initialize-AftaAdb $Serial $AdbPath
if ((Get-AftaProperty 'ro.product.model') -ne 'AFTA' -or (Get-AftaProperty 'ro.build.version.sdk') -ne '25') {
    throw 'The cleanup trial is restricted to AFTA/API25.'
}
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot '..\diagnostics\afta.5-ram-trial' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Trial output already exists; preserve it and choose a fresh directory.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$TrialEncoding = [Text.UTF8Encoding]::new($false)
$CouchyPackage = 'com.conreo.couchytv'
$AmazonPackage = 'com.amazon.tv.launcher'
$ServiceComponent = "$AftaPackage/io.github.toolicious.homeonfire.UsageMonitorService"
$Runs = New-Object Collections.Generic.List[object]
$Failure = ''
$FinalCleanupEnabled = $null
$script:TrialRecordStatus = 'starting'

function Save-TrialText([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text, $TrialEncoding)
}
function Get-TrialPreferences {
    $text = (Invoke-AftaAdb -Arguments @('exec-out','run-as',$AftaPackage,'cat','shared_prefs/home_on_fire.xml')).Text
    $values = ConvertFrom-AftaPreferences $text
    return [pscustomobject]@{ Text=$text; Values=$values }
}
function Get-FocusedPackage {
    $text = (Invoke-AftaAdb -Arguments @('shell','dumpsys','window','windows')).Text
    if ($text -match '(?m)^\s*mCurrentFocus=Window\{[^\r\n}]*\bu0\s+([^/\s}]+)/') { return $Matches[1] }
    return ''
}
function Wait-FocusedPackage([string] $Package, [int] $TimeoutMs = 18000) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-FocusedPackage) -eq $Package) { return }
        Start-Sleep -Milliseconds 200
    } while ($timer.ElapsedMilliseconds -lt $TimeoutMs)
    throw "Focus did not reach $Package within $TimeoutMs ms."
}
function Invoke-TrialService([string] $Action, [Nullable[bool]] $Cleanup) {
    $arguments = @('shell','run-as',$AftaPackage,'am','startservice','--user','0','-n',$ServiceComponent,'-a',$Action)
    if ($null -ne $Cleanup) { $arguments += @('--ez','amazon_cleanup_enabled',([bool]$Cleanup).ToString().ToLowerInvariant()) }
    $result = Invoke-AftaAdb -Arguments $arguments
    if (($result.Text + $result.ErrorText) -match 'Error:|Exception|Permission Denial') {
        throw "Same-UID service action failed: $($result.Text) $($result.ErrorText)"
    }
}
function Set-TrialCleanup([bool] $Enabled) {
    Invoke-TrialService 'configure' $Enabled
    Start-Sleep -Milliseconds 1200
    $prefs = Get-TrialPreferences
    if ($prefs.Values['usage_amazon_cleanup_enabled'] -ne $Enabled) { throw 'Cleanup setting was not confirmed in saved preferences.' }
    if ($Enabled -and $prefs.Values['usage_suspended'] -eq $true) { throw 'Automation is suspended; preserve evidence before recovery.' }
}
function Save-TrialEvents([string] $Path) {
    $null = Invoke-AftaAdb -Arguments @('exec-out','run-as',$AftaPackage,'cat','files/diagnostics/events.jsonl') -OutputFile $Path
    $rows = @()
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line.Trim()) { $rows += ($line | ConvertFrom-Json) }
    }
    return $rows
}
function Get-AmazonSample([string] $Path, [int] $PlannedSeconds, [Diagnostics.Stopwatch] $SinceFocus) {
    $began = $SinceFocus.ElapsedMilliseconds
    $result = Invoke-AftaAdb -Arguments @('shell','dumpsys','meminfo',$AmazonPackage)
    Save-TrialText $Path ($result.Text + $result.ErrorText)
    if ($result.Text -match 'No process found') {
        $pidValue = 0; $pssValue = 0
    } elseif ($result.Text -match '\*\* MEMINFO in pid (\d+) \[com\.amazon\.tv\.launcher\] \*\*') {
        $pidValue = [int]$Matches[1]
        if ($result.Text -notmatch '(?m)^\s*TOTAL\s+(\d+)\s+') { throw 'Amazon meminfo has no parseable PSS TOTAL.' }
        $pssValue = [int]$Matches[1]
    } else { throw 'Amazon meminfo did not establish a process or verified absence.' }
    $sample = [pscustomobject]@{
        plannedSeconds=$PlannedSeconds; beganMsAfterObservedFocus=$began
        endedMsAfterObservedFocus=$SinceFocus.ElapsedMilliseconds
        capturedAtUtc=[DateTime]::UtcNow.ToString('o'); pid=$pidValue; pssKiB=$pssValue; rawFile=$Path
    }
    Write-Host ("Sample {0}s: Amazon PID={1}, PSS={2:N2} MiB" -f $PlannedSeconds, $pidValue, ($pssValue / 1024.0))
    return $sample
}
function Start-TrialOtherApp {
    $result = Invoke-AftaAdb -Arguments @('shell','am','start','-n','com.esaba.downloader/.ui.main.MainActivity')
    if (($result.Text + $result.ErrorText) -match 'Error:|Exception') { throw 'Downloader could not be launched for this trial.' }
    Wait-FocusedPackage 'com.esaba.downloader'
    Start-Sleep -Milliseconds 700
}
function Get-TrialMedian([object[]] $Values) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { throw 'Cannot take a median of missing trial evidence.' }
    $middle = [int][Math]::Floor($sorted.Count / 2.0)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle-1] + [double]$sorted[$middle]) / 2.0
}
function Save-TrialRecord([string] $Status) {
    $script:TrialRecordStatus = $Status
    $record = [ordered]@{
        serial=$Serial; version='1.5.2-afta.5'; status=$Status; failure=$Failure
        recordedAtUtc=[DateTime]::UtcNow.ToString('o'); intendedPairCount=$PairCount; runs=@($Runs.ToArray())
        method='ADB input to first observed system focus; memory sampling after that observation'
        physicalRemoteValidated=$false; alexaValidated=$false; finalCleanupEnabled=$FinalCleanupEnabled
    }
    Save-TrialText (Join-Path $OutputDirectory 'trial.json') ($record | ConvertTo-Json -Depth 9)
}

try {
    $package = (Invoke-AftaAdb -Arguments @('shell','dumpsys','package',$AftaPackage)).Text
    Save-TrialText (Join-Path $OutputDirectory 'package-before.txt') $package
    if ($package -notmatch 'versionCode=14005\b' -or $package -notmatch 'KILL_BACKGROUND_PROCESSES: granted=true') {
        throw 'Expected the reviewed afta.5 APK with its normal cleanup permission granted.'
    }
    $initial = Get-TrialPreferences
    Save-TrialText (Join-Path $OutputDirectory 'preferences-before.xml') $initial.Text
    if ($initial.Values['target_package'] -ne $CouchyPackage -or $initial.Values['usage_suspended'] -eq $true -or
            $initial.Values['usage_diagnostic_only'] -eq $true -or $initial.Values['usage_monitor_enabled'] -eq $false) {
        throw 'Trial needs selected Couchy, an active automatic monitor and no retained suspension.'
    }
    if ($StartPair -gt 1) {
        if (-not $PriorMeasurementsPath) { throw 'Resuming requires explicitly reconciled prior measurements.' }
        $priorText = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $PriorMeasurementsPath).Path)
        $prior = $priorText | ConvertFrom-Json
        if ($prior.serial -ne $Serial -or $prior.version -ne '1.5.2-afta.5' -or $prior.runs.Count -ne (($StartPair-1)*2)) {
            throw 'Prior measurement identity/count does not match the resumed trial.'
        }
        foreach ($item in $prior.runs) {
            if (-not $item.verified -or $item.samples.Count -ne 3 -or $item.pair -ge $StartPair) { throw 'Prior measurements are not verified complete earlier pairs.' }
            $Runs.Add($item)
        }
        Save-TrialText (Join-Path $OutputDirectory 'prior-measurements.json') $priorText
    }
    $null = Invoke-AftaAdb -Arguments @('shell','input','keyevent','224')
    for ($pair = $StartPair; $pair -le $PairCount; $pair++) {
        # Alternate order to reduce a fixed warm-cache/time trend.
        $modes = if (($pair % 2) -eq 1) { @($false,$true) } else { @($true,$false) }
        foreach ($enabled in $modes) {
            $mode = if ($enabled) { 'on' } else { 'off' }
            $directory = Join-Path $OutputDirectory ("pair-{0}-{1}" -f $pair,$mode)
            New-Item -ItemType Directory -Path $directory | Out-Null
            Write-Host "Starting pair $pair / cleanup $mode"
            Set-TrialCleanup $enabled
            $before = @(Save-TrialEvents (Join-Path $directory 'events-before.jsonl'))
            # Warmup establishes the selected cleanup state before timing the NEXT Home.
            # Timing the first Home would miss its later cleanup's cold-start cost.
            Start-TrialOtherApp
            $warmupStart = [Diagnostics.Stopwatch]::StartNew()
            $null = Invoke-AftaAdb -Arguments @('shell','input','keyevent','3')
            Wait-FocusedPackage $CouchyPackage
            $warmupLatency = $warmupStart.ElapsedMilliseconds
            Start-Sleep -Seconds 10
            Start-TrialOtherApp
            $remaining = 16000 - $warmupStart.ElapsedMilliseconds
            if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int]$remaining) }
            $preTimer = [Diagnostics.Stopwatch]::StartNew()
            $preAmazon = Get-AmazonSample (Join-Path $directory 'amazon-before-timed-home.txt') 0 $preTimer
            $homeTimer = [Diagnostics.Stopwatch]::StartNew()
            $homeSentAt = [DateTime]::UtcNow.ToString('o')
            $null = Invoke-AftaAdb -Arguments @('shell','input','keyevent','3')
            Wait-FocusedPackage $CouchyPackage
            $homeLatency = $homeTimer.ElapsedMilliseconds
            $focusObservedAt = [DateTime]::UtcNow.ToString('o')
            $sinceFocus = [Diagnostics.Stopwatch]::StartNew()
            Write-Host "Timed Home reached Couchy after $homeLatency ms"
            $run = [pscustomobject]@{
                pair=$pair; cleanupEnabled=$enabled; homeSentAtUtc=$homeSentAt; couchyFocusObservedAtUtc=$focusObservedAt
                homeLatencyMs=$homeLatency; warmupHomeLatencyMs=$warmupLatency; amazonBeforeTimedHome=$preAmazon
                samples=@(); cleanupAttempts=0; cleanupRequests=0; cleanupFailures=0; verified=$false
            }
            $Runs.Add($run)
            Save-TrialRecord 'running'
            foreach ($seconds in @(2,30,60)) {
                $wait = $seconds * 1000 - $sinceFocus.ElapsedMilliseconds
                if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) }
                $run.samples += Get-AmazonSample (Join-Path $directory ("amazon-{0}s.txt" -f $seconds)) $seconds $sinceFocus
                if ((Get-FocusedPackage) -ne $CouchyPackage) { throw 'Couchy lost focus during the memory dwell.' }
                Save-TrialRecord 'running'
            }
            $after = @(Save-TrialEvents (Join-Path $directory 'events-after.jsonl'))
            $events = @(Get-AftaAppendedEvents $before $after)
            $run.cleanupAttempts = @($events | Where-Object event -eq 'cleanup_attempt').Count
            $run.cleanupRequests = @($events | Where-Object event -eq 'cleanup_requested').Count
            $run.cleanupFailures = @($events | Where-Object event -eq 'cleanup_failed').Count
            Save-TrialText (Join-Path $directory 'events-scoped.json') ($events | ConvertTo-Json -Depth 6)
            $prefs = Get-TrialPreferences
            Save-TrialText (Join-Path $directory 'preferences-after.xml') $prefs.Text
            if ($prefs.Values['usage_suspended'] -eq $true) { throw 'Circuit opened during this trial; capture retained suspension.' }
            if ($prefs.Values['usage_amazon_cleanup_enabled'] -ne $enabled) { throw 'Cleanup disabled itself or its configuration changed during the trial.' }
            $expected = if ($enabled) { 2 } else { 0 }
            if ($run.cleanupAttempts -ne $expected -or $run.cleanupRequests -ne $expected -or $run.cleanupFailures -ne 0) {
                throw "Unexpected cleanup dispatch count: attempts=$($run.cleanupAttempts), requested=$($run.cleanupRequests), failures=$($run.cleanupFailures), expected=$expected"
            }
            $run.verified = $true
            Save-TrialRecord 'running'
            Write-Host "Completed pair $pair / cleanup $mode"
        }
    }
    $off = @($Runs | Where-Object { -not $_.cleanupEnabled })
    $on = @($Runs | Where-Object cleanupEnabled)
    $summary = [ordered]@{ completePairs=$PairCount; baselineHomeMedianMs=(Get-TrialMedian @($off.homeLatencyMs)); cleanupHomeMedianMs=(Get-TrialMedian @($on.homeLatencyMs)) }
    $summary['addedHomeMedianMs'] = $summary.cleanupHomeMedianMs - $summary.baselineHomeMedianMs
    foreach ($seconds in @(2,30,60)) {
        $offValues = @($off | ForEach-Object { ($_.samples | Where-Object plannedSeconds -eq $seconds).pssKiB })
        $onValues = @($on | ForEach-Object { ($_.samples | Where-Object plannedSeconds -eq $seconds).pssKiB })
        $summary["offMedianPss${seconds}sKiB"] = Get-TrialMedian $offValues
        $summary["onMedianPss${seconds}sKiB"] = Get-TrialMedian $onValues
        $summary["savedMedianPss${seconds}sKiB"] = $summary["offMedianPss${seconds}sKiB"] - $summary["onMedianPss${seconds}sKiB"]
    }
    $summary['ramTimingGatePassed'] = $summary.savedMedianPss30sKiB -ge 10240 -and $summary.savedMedianPss60sKiB -ge 10240 -and $summary.addedHomeMedianMs -le 1000
    $summary['navigationAcceptancePending'] = $true
    Save-TrialText (Join-Path $OutputDirectory 'summary.json') ($summary | ConvertTo-Json -Depth 5)
    Write-Host ($summary | ConvertTo-Json)
    Save-TrialRecord 'paired measurements complete; navigation/human acceptance separate'
} catch {
    $Failure = $_.Exception.Message
    Save-TrialRecord 'failed; preserve partial evidence'
    throw
} finally {
    try {
        Set-TrialCleanup $false
        $final = Get-TrialPreferences
        $FinalCleanupEnabled = $false
        Save-TrialText (Join-Path $OutputDirectory 'preferences-final.xml') $final.Text
        Save-TrialRecord $script:TrialRecordStatus
        Write-Host 'Temporary trial finished; cleanup verified off.'
    } catch {
        Save-TrialText (Join-Path $OutputDirectory 'cleanup-restoration-error.txt') $_.Exception.Message
        Write-Warning 'Could not confirm cleanup off; inspect the saved restoration error immediately.'
    }
}

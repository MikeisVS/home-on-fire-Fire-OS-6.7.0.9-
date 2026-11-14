#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious.
[CmdletBinding()]
param([string] $JdkRoot = 'C:\Program Files\Java\jdk-25')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$TaskRoot = Split-Path -Parent $PSScriptRoot
$Output = Join-Path $TaskRoot 'artifacts\test-classes'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$Sources = @(
    (Join-Path $TaskRoot 'src\io\github\toolicious\homeonfire\RedirectEngine.java'),
    (Join-Path $TaskRoot 'src\io\github\toolicious\homeonfire\UsageCursor.java'),
    (Join-Path $TaskRoot 'src\io\github\toolicious\homeonfire\HomeIntentLog.java'),
    (Join-Path $TaskRoot 'src\io\github\toolicious\homeonfire\HomeCoverState.java'),
    (Join-Path $TaskRoot 'src\io\github\toolicious\homeonfire\AmazonCleanupPolicy.java'),
    (Join-Path $PSScriptRoot 'RedirectEngineTest.java'),
    (Join-Path $PSScriptRoot 'StartupHomeTest.java'),
    (Join-Path $PSScriptRoot 'HomeIntentLogTest.java'),
    (Join-Path $PSScriptRoot 'UsageCursorTest.java'),
    (Join-Path $PSScriptRoot 'HomeCoverTest.java'),
    (Join-Path $PSScriptRoot 'AmazonCleanupTest.java')
)
& (Join-Path $JdkRoot 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d $Output @Sources
if ($LASTEXITCODE -ne 0) { throw 'Policy test compilation failed.' }
$Results = @()
foreach ($test in @('RedirectEngineTest', 'UsageCursorTest', 'StartupHomeTest', 'HomeIntentLogTest', 'HomeCoverTest', 'AmazonCleanupTest')) {
    $result = & (Join-Path $JdkRoot 'bin\java.exe') -cp $Output "io.github.toolicious.homeonfire.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
    $Results += $result
    Write-Host $result
}
$scriptResults = & (Join-Path $PSScriptRoot 'ScriptHelpersTest.ps1')
$Results += $scriptResults
Write-Host $scriptResults
[IO.File]::WriteAllLines((Join-Path $TaskRoot 'artifacts\policy-tests.txt'), [string[]]$Results,
    [Text.UTF8Encoding]::new($false))

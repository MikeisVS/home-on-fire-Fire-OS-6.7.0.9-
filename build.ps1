#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious.
<#
.SYNOPSIS
Build and inspect the separately signed Home on Fire AFTA development APK.
.DESCRIPTION
Build only. Never invokes ADB or changes a tracked version. Defaults to JDK
25.0.1, platform 35 and build tools 36.1.0; min SDK 21, target SDK 34.
Creates a persistent DEVELOPMENT key outside the repository if needed.
The default development password is android; optionally set AFTA_KEYSTORE_PASSWORD.
#>
[CmdletBinding()]
param(
    [string] $SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $JdkRoot,
    [string] $BuildToolsVersion = '36.1.0',
    [int] $CompileSdk = 35,
    [string] $KeystorePath = "$env:LOCALAPPDATA\HomeOnFireAfta\signing\afta-development.p12"
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$BuildRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'build'))
$Artifacts = Join-Path $ProjectRoot 'artifacts'
$Package = 'io.github.toolicious.homeonfire.afta'
$ExpectedJdkVersion = '25.0.1'
$NewLine = [Environment]::NewLine

# Never clean an unchecked or caller-provided path.
if ($BuildRoot -ne [IO.Path]::Combine($ProjectRoot, 'build') -or
    -not $BuildRoot.StartsWith($ProjectRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to clean a build directory outside this checkout.'
}
if (Test-Path -LiteralPath $BuildRoot) {
    $buildItem = Get-Item -LiteralPath $BuildRoot
    if ($buildItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Build directory cannot be a junction or symlink.' }
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BuildRoot, $Artifacts | Out-Null
$BuildLog = Join-Path $Artifacts 'build.log'
[IO.File]::WriteAllText($BuildLog, '', [Text.UTF8Encoding]::new($false))

function Quote-NativeArgument([string] $Value) {
    if ($Value.Length -eq 0) { return '""' }
    # Windows CommandLineToArgvW quoting, without invoking a command shell.
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Invoke-Native([string] $File, [string[]] $Arguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = (($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.WorkingDirectory = $ProjectRoot
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not start $File" }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $output = $stdout.Result + $stderr.Result
    $exitCode = $process.ExitCode
    $process.Dispose()
    [IO.File]::AppendAllText($BuildLog, ([IO.Path]::GetFileName($File) + ':' + $NewLine + $output + $NewLine))
    if ($exitCode -ne 0) { throw "$([IO.Path]::GetFileName($File)) failed ($exitCode): $output" }
    return $output
}

if (-not $JdkRoot) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $JdkRoot = $env:JAVA_HOME
    } elseif (Test-Path -LiteralPath 'C:\Program Files\Java\jdk-25\bin\javac.exe') {
        $JdkRoot = 'C:\Program Files\Java\jdk-25'
        Write-Host 'Using installed JDK 25; missing/invalid JAVA_HOME was not changed.'
    } else {
        throw 'Supply -JdkRoot pointing to JDK 25.0.1.'
    }
}
$Java = Join-Path $JdkRoot 'bin\java.exe'
$Javac = Join-Path $JdkRoot 'bin\javac.exe'
$Keytool = Join-Path $JdkRoot 'bin\keytool.exe'
$ToolsRoot = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$AndroidJar = Join-Path $SdkRoot "platforms\android-$CompileSdk\android.jar"
$Aapt = Join-Path $ToolsRoot 'aapt2.exe'
$Zipalign = Join-Path $ToolsRoot 'zipalign.exe'
$D8Jar = Join-Path $ToolsRoot 'lib\d8.jar'
$SignerJar = Join-Path $ToolsRoot 'lib\apksigner.jar'
foreach ($tool in @($Java, $Javac, $Keytool, $AndroidJar, $Aapt, $Zipalign, $D8Jar, $SignerJar)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing prerequisite: $tool" }
}
$JavacVersion = (Invoke-Native $Javac @('-version')).Trim()
if ($JavacVersion -ne "javac $ExpectedJdkVersion") {
    throw "Expected javac $ExpectedJdkVersion; found $JavacVersion. Supply -JdkRoot."
}
$AaptVersion = (Invoke-Native $Aapt @('version')).Trim()
$D8Version = (Invoke-Native $Java @('-cp', $D8Jar, 'com.android.tools.r8.D8', '--version')).Trim()

$Gen = Join-Path $BuildRoot 'gen'
$Classes = Join-Path $BuildRoot 'classes'
$Dex = Join-Path $BuildRoot 'dex'
New-Item -ItemType Directory -Path $Gen, $Classes, $Dex | Out-Null
$Compiled = Join-Path $BuildRoot 'resources.zip'
$Unsigned = Join-Path $BuildRoot 'resources.apk'
Write-Host 'Compiling resources and Java...'
$null = Invoke-Native $Aapt @('compile', '--dir', (Join-Path $ProjectRoot 'res'), '-o', $Compiled)
$null = Invoke-Native $Aapt @('link', '-o', $Unsigned, '-I', $AndroidJar,
    '--manifest', (Join-Path $ProjectRoot 'AndroidManifest.xml'), '-R', $Compiled,
    '--java', $Gen, '--auto-add-overlay', '--min-sdk-version', '21',
    '--target-sdk-version', '34', '--rename-manifest-package', $Package)
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src'), $Gen -Recurse -Filter '*.java' -File |
    Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
$SourceList = Join-Path $BuildRoot 'sources.txt'
[IO.File]::WriteAllLines($SourceList, $Sources, [Text.UTF8Encoding]::new($false))
$null = Invoke-Native $Javac @('-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-Xlint:-options', '-bootclasspath', $AndroidJar, '-classpath', $AndroidJar,
    '-d', $Classes, "@$SourceList")

# Deterministic ZIPs: sorted names, fixed timestamps, stored entries.
# This also keeps resources.arsc uncompressed, as required on newer Android.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Add-ZipBytes($Archive, [string] $Name, [byte[]] $Bytes) {
    $entry = $Archive.CreateEntry($Name, [IO.Compression.CompressionLevel]::NoCompression)
    $entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
    $stream = $entry.Open()
    try { $stream.Write($Bytes, 0, $Bytes.Length) } finally { $stream.Dispose() }
}
$ClassesJar = Join-Path $BuildRoot 'classes.jar'
$zip = [IO.Compression.ZipFile]::Open($ClassesJar, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in (Get-ChildItem -LiteralPath $Classes -Recurse -Filter '*.class' -File | Sort-Object FullName)) {
        $name = $file.FullName.Substring($Classes.Length + 1).Replace('\', '/')
        Add-ZipBytes $zip $name ([IO.File]::ReadAllBytes($file.FullName))
    }
} finally { $zip.Dispose() }
$null = Invoke-Native $Java @('-cp', $D8Jar, 'com.android.tools.r8.D8', '--min-api', '21',
    '--lib', $AndroidJar, '--output', $Dex, $ClassesJar)
$Packed = Join-Path $BuildRoot 'packed.apk'
$inputZip = [IO.Compression.ZipFile]::OpenRead($Unsigned)
$outputZip = [IO.Compression.ZipFile]::Open($Packed, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in ($inputZip.Entries | Sort-Object FullName)) {
        $source = $entry.Open()
        $memory = New-Object IO.MemoryStream
        try {
            $source.CopyTo($memory)
            Add-ZipBytes $outputZip $entry.FullName ($memory.ToArray())
        } finally { $source.Dispose(); $memory.Dispose() }
    }
    foreach ($file in (Get-ChildItem -LiteralPath $Dex -Filter 'classes*.dex' -File | Sort-Object Name)) {
        Add-ZipBytes $outputZip $file.Name ([IO.File]::ReadAllBytes($file.FullName))
    }
} finally { $inputZip.Dispose(); $outputZip.Dispose() }
$Aligned = Join-Path $BuildRoot 'aligned.apk'
$null = Invoke-Native $Zipalign @('-f', '-p', '4', $Packed, $Aligned)

$OriginalPassword = [Environment]::GetEnvironmentVariable('AFTA_KEYSTORE_PASSWORD', 'Process')
if (-not $OriginalPassword) { $env:AFTA_KEYSTORE_PASSWORD = 'android' }
$Apk = Join-Path $Artifacts 'home-on-fire-afta.apk'
try {
    $keyParent = Split-Path -Parent ([IO.Path]::GetFullPath($KeystorePath))
    if ($keyParent.StartsWith($ProjectRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Signing key must be outside the repository.'
    }
    if (-not (Test-Path -LiteralPath $KeystorePath)) {
        New-Item -ItemType Directory -Force -Path $keyParent | Out-Null
        $null = Invoke-Native $Keytool @('-genkeypair', '-keystore', $KeystorePath,
            '-storetype', 'PKCS12', '-alias', 'home-on-fire-afta', '-keyalg', 'RSA',
            '-keysize', '2048', '-sigalg', 'SHA256withRSA', '-validity', '10000',
            '-dname', 'CN=Home on Fire AFTA Development', '-storepass:env', 'AFTA_KEYSTORE_PASSWORD',
            '-keypass:env', 'AFTA_KEYSTORE_PASSWORD')
    }
    Write-Host 'Signing development APK and inspecting it...'
    $null = Invoke-Native $Java @('-jar', $SignerJar, 'sign', '--ks', $KeystorePath,
        '--ks-key-alias', 'home-on-fire-afta', '--ks-pass', 'env:AFTA_KEYSTORE_PASSWORD',
        '--key-pass', 'env:AFTA_KEYSTORE_PASSWORD', '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false',
        '--v4-signing-enabled', 'false', '--out', $Apk, $Aligned)
} finally {
    [Environment]::SetEnvironmentVariable('AFTA_KEYSTORE_PASSWORD', $OriginalPassword, 'Process')
}
$Signature = Invoke-Native $Java @('-jar', $SignerJar, 'verify', '--verbose', '--print-certs', $Apk)
$null = Invoke-Native $Zipalign @('-c', '-p', '4', $Apk)
$Badging = Invoke-Native $Aapt @('dump', 'badging', $Apk)
$ManifestDump = Invoke-Native $Aapt @('dump', 'xmltree', $Apk, '--file', 'AndroidManifest.xml')
if ($Badging -notmatch "package: name='$([regex]::Escape($Package))'" -or
    $Badging -notmatch "sdkVersion:'21'" -or $Badging -notmatch "targetSdkVersion:'34'") {
    throw 'APK identity/SDK inspection did not match the development contract.'
}
if ($ManifestDump -notmatch 'io.github.toolicious.homeonfire.UsageMonitorService' -or
    $ManifestDump -notmatch 'io.github.toolicious.homeonfire.AftaSettingsActivity') {
    throw 'Development components are missing from APK manifest.'
}
$Hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $Artifacts 'apk-inspection.txt'),
    $Badging + $NewLine + $Signature + $NewLine + $ManifestDump, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText(($Apk + '.sha256'), "$Hash  home-on-fire-afta.apk" + $NewLine, [Text.UTF8Encoding]::new($false))
$Git = (Get-Command git -ErrorAction Stop).Source
$Commit = (Invoke-Native $Git @('rev-parse', 'HEAD')).Trim()
$Branch = (Invoke-Native $Git @('branch', '--show-current')).Trim()
$SourceFiles = @('AndroidManifest.xml', 'build.ps1') +
    @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src'), (Join-Path $ProjectRoot 'res') -Recurse -File |
      ForEach-Object { $_.FullName.Substring($ProjectRoot.Length + 1) })
$SourceHashes = [ordered]@{}
foreach ($relative in ($SourceFiles | Sort-Object)) {
    $SourceHashes[$relative.Replace('\', '/')] = (Get-FileHash -LiteralPath (Join-Path $ProjectRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$Record = [ordered]@{
    packageName = $Package; apk = $Apk; sha256 = $Hash
    sourceBaseCommit = $Commit; branch = $Branch; sourceHashes = $SourceHashes
    jdk = $JavacVersion; compileSdk = $CompileSdk; minSdk = 21; targetSdk = 34
    buildTools = $BuildToolsVersion; aapt2 = $AaptVersion; d8 = $D8Version
    sdkRoot = $SdkRoot; jdkRoot = $JdkRoot; powershell = $PSVersionTable.PSVersion.ToString()
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    deviceInstalled = $false; compatibilityValidated = $false
}
[IO.File]::WriteAllText((Join-Path $Artifacts 'build-record.json'),
    ($Record | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Host "Built and inspected: $Apk"
Write-Host "SHA-256: $Hash"
Write-Host 'No device contacted. Runtime compatibility is not established by this build.'

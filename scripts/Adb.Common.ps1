#requires -Version 5.1
# AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious.
Set-StrictMode -Version Latest
$AftaPackage = 'io.github.toolicious.homeonfire.afta'

function ConvertTo-NativeArgument([string] $Value) {
    if ($Value.Length -eq 0) { return '""' }
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

function Invoke-AftaNative {
    param([string] $File, [string[]] $Arguments, [switch] $AllowFailure, [string] $OutputFile)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = (($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Cannot start $File" }
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $stream = $null
    try {
        if ($OutputFile) {
            $stream = [IO.File]::Create($OutputFile)
            $copy = $process.StandardOutput.BaseStream.CopyToAsync($stream)
            $process.WaitForExit()
            $null = $copy.GetAwaiter().GetResult()
            $stdout = ''
        } else {
            $stdoutTask = $process.StandardOutput.ReadToEndAsync()
            $process.WaitForExit()
            $stdout = $stdoutTask.Result
        }
        $result = [pscustomobject]@{
            ExitCode = $process.ExitCode; Text = $stdout; ErrorText = $stderrTask.Result
        }
        if ($result.ExitCode -ne 0 -and -not $AllowFailure) {
            throw "$([IO.Path]::GetFileName($File)) failed ($($result.ExitCode)): $($result.Text) $($result.ErrorText)"
        }
        return $result
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        $process.Dispose()
    }
}

function Initialize-AftaAdb([string] $Serial, [string] $AdbPath) {
    if ($Serial -notmatch '^[A-Za-z0-9._:\[\]-]+$') { throw 'An explicit, valid device serial is required.' }
    if (-not $AdbPath) {
        foreach ($candidate in @('C:\platform-tools\adb.exe', "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AdbPath = $candidate; break }
        }
    }
    if (-not $AdbPath) {
        $command = Get-Command adb -ErrorAction SilentlyContinue
        if ($command) { $AdbPath = $command.Source }
    }
    if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
        throw 'Supply -AdbPath pointing to adb.exe.'
    }
    $script:TaskAdb = $AdbPath
    $script:TaskSerial = $Serial
    $state = (Invoke-AftaAdb -Arguments @('get-state')).Text.Trim()
    if ($state -ne 'device') { throw "Device $Serial is not ready: $state" }
}

function Invoke-AftaAdb {
    param([string[]] $Arguments, [switch] $AllowFailure, [string] $OutputFile)
    Invoke-AftaNative -File $script:TaskAdb -Arguments (@('-s', $script:TaskSerial) + $Arguments) -AllowFailure:$AllowFailure -OutputFile $OutputFile
}

function Get-AftaProperty([string] $Name) {
    return (Invoke-AftaAdb -Arguments @('shell', 'getprop', $Name)).Text.Trim()
}

function Get-AftaSignerIdentity([string] $Verification) {
    $signers = @([regex]::Matches($Verification,
        '(?m)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$') |
        ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object -Unique)
    if ($signers.Count -eq 0) { throw 'APK verification did not provide a valid signer SHA-256 identity.' }
    return ($signers -join ',')
}

function ConvertTo-RemoteLiteral([string] $Value) {
    $quote = [string][char]39
    return $quote + $Value.Replace($quote, $quote + '\' + $quote + $quote) + $quote
}

function Remove-AftaAccessibilityEntry([string] $Value) {
    if (-not $Value -or $Value -eq 'null') { return '' }
    return ((@($Value -split ':') | Where-Object {
        ($_ -split '/', 2)[0] -ne $AftaPackage
    }) -join ':')
}

function Get-AftaAppOpMode([string] $Operation, [string] $Output) {
    $pattern = '(?m)^\s*' + [regex]::Escape($Operation) + ':\s*(allow|ignore|deny|default|foreground)\b'
    if ($Output -match $pattern) { return $Matches[1].ToLowerInvariant() }
    if ($Output -match '^\s*No operations\.?\s*$') { return 'default' }
    throw "Cannot establish $Operation mode from appops output: $Output"
}

function ConvertFrom-AftaPreferences([string] $Text) {
    [xml]$xml = $Text
    $values = @{}
    foreach ($node in $xml.DocumentElement.ChildNodes) {
        if ($node.NodeType -ne [Xml.XmlNodeType]::Element) { continue }
        $name = $node.GetAttribute('name')
        # PowerShell's XML adapter makes .Name return the name ATTRIBUTE here.
        if ($node.LocalName -eq 'boolean') {
            $value = $node.GetAttribute('value')
            if ($value -ne 'true' -and $value -ne 'false') { throw "Invalid saved boolean: $name" }
            $values[$name] = $value -eq 'true'
        } elseif ($node.HasAttribute('value')) {
            $values[$name] = $node.GetAttribute('value')
        } else {
            $values[$name] = $node.InnerText
        }
    }
    return $values
}

function Get-AftaAppendedEvents([object[]] $Before, [object[]] $After) {
    if ($Before.Count -eq 0) { return $After }
    # Persistent logs cross device reboots. An all-history uptime maximum is invalid.
    $last = $Before[$Before.Count - 1] | ConvertTo-Json -Compress -Depth 8
    $lastIndex = -1
    for ($index = 0; $index -lt $After.Count; $index++) {
        if (($After[$index] | ConvertTo-Json -Compress -Depth 8) -ceq $last) { $lastIndex = $index }
    }
    if ($lastIndex -lt 0) { throw 'The event checkpoint rotated away; capture all log files before claiming dispatch counts.' }
    return @($After | Select-Object -Skip ($lastIndex + 1))
}

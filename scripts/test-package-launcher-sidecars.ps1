#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'package-launcher-sidecars.ps1'
$buildLocal = Join-Path $PSScriptRoot '../build-local.ps1'
$pwsh = (Get-Process -Id $PID).Path
$comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$root = Join-Path $tempParent ("sidecars-test-" + [Guid]::NewGuid().ToString('N'))
$oldMode = $env:SIDECAR_FIXTURE_MODE
$oldVersion = $env:SIDECAR_FIXTURE_VERSION
$oldPwsh = $env:SIDECAR_FIXTURE_PWSH
$oldPath = $env:PATH

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) { throw "${Message}: expected '$Expected', got '$Actual'" }
}

function Get-Snapshot {
    param([string]$Directory)
    return (@(Get-ChildItem -LiteralPath $Directory -Recurse -Force -File | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($Directory.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }) -join "`n")
}

function Assert-NoTransactions {
    if (@(Get-ChildItem -LiteralPath $root -Force | Where-Object { $_.Name -like '.sidecars-*' }).Count -ne 0) {
        throw 'A sidecar transaction directory was not cleaned up.'
    }
}

function Invoke-FixturePackaging {
    & $helper -BinDir $bin -QqPackager $qq -CloudflarePackager $cloud
}

function Assert-PackagingFailure {
    param([string]$Mode, [string]$ExpectedMessage)
    $before = Get-Snapshot $bin
    $env:SIDECAR_FIXTURE_MODE = $Mode
    $failed = $false
    try { Invoke-FixturePackaging }
    catch {
        $failed = $true
        if ($_.Exception.Message -notlike "*$ExpectedMessage*") { throw }
    }
    if (-not $failed) { throw "Packaging unexpectedly succeeded for $Mode" }
    Assert-Equal (Get-Snapshot $bin) $before "Old outputs changed after $Mode"
    Assert-NoTransactions
}

function Remove-TestTree {
    $resolved = (Get-Item -LiteralPath $root -Force).FullName
    if (-not $resolved.Equals([IO.Path]::GetFullPath($root), $comparison) -or
        -not $resolved.StartsWith($tempParent + [IO.Path]::DirectorySeparatorChar, $comparison) -or
        (Split-Path -Leaf $resolved) -notmatch '^sidecars-test-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside the verified test directory.'
    }
    $ancestor = $resolved
    while ($ancestor) {
        $item = Get-Item -LiteralPath $ancestor -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing test cleanup through a reparse point.' }
        $ancestor = Split-Path -Parent $ancestor
    }
    foreach ($item in Get-ChildItem -LiteralPath $resolved -Recurse -Force) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            -not $item.FullName.StartsWith($resolved + [IO.Path]::DirectorySeparatorChar, $comparison)) {
            throw 'Refusing test cleanup containing an unverified child.'
        }
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

$null = New-Item -ItemType Directory -Path $root
try {
    $bin = Join-Path $root 'bin'
    $null = New-Item -ItemType Directory -Path $bin
    Set-Content -LiteralPath (Join-Path $bin 'server.jar') -Value 'unrelated-server'
    Set-Content -LiteralPath (Join-Path $bin 'netease-api.exe') -Value 'unrelated-api'
    $qq = Join-Path $root 'qq-fixture.ps1'
    $cloud = Join-Path $root 'cloud-fixture.ps1'
    $env:SIDECAR_FIXTURE_PWSH = $pwsh
    Set-Content -LiteralPath $qq -Value @'
param([string]$OutputDir)
if (Test-Path -LiteralPath $OutputDir) { throw 'QQ OutputDir must be new.' }
$null = New-Item -ItemType Directory -Path $OutputDir
Set-Content -LiteralPath (Join-Path $OutputDir 'version.txt') -Value $env:SIDECAR_FIXTURE_VERSION
if ($env:SIDECAR_FIXTURE_MODE -eq 'qq-throw') { throw 'QQ fixture failure' }
if ($env:SIDECAR_FIXTURE_MODE -eq 'qq-error') { Write-Error 'QQ nonterminating fixture failure' }
if ($env:SIDECAR_FIXTURE_MODE -eq 'qq-native') {
    & $env:SIDECAR_FIXTURE_PWSH -NoProfile -NonInteractive -Command 'exit 23'
}
'@
    Set-Content -LiteralPath $cloud -Value @'
param([string]$OutputDir)
$exe = Join-Path $OutputDir 'cloudflared.exe'
$notices = Join-Path $OutputDir 'cloudflared-notices'
if ((Test-Path -LiteralPath $exe) -or (Test-Path -LiteralPath $notices)) { throw 'Cloudflare outputs must be new.' }
Set-Content -LiteralPath $exe -Value $env:SIDECAR_FIXTURE_VERSION
$null = New-Item -ItemType Directory -Path $notices
Set-Content -LiteralPath (Join-Path $notices 'NOTICE') -Value $env:SIDECAR_FIXTURE_VERSION
if ($env:SIDECAR_FIXTURE_MODE -eq 'cloud-throw') { throw 'Cloudflare fixture failure' }
if ($env:SIDECAR_FIXTURE_MODE -eq 'cloud-native') {
    & $env:SIDECAR_FIXTURE_PWSH -NoProfile -NonInteractive -Command 'exit 29'
}
'@
    $env:SIDECAR_FIXTURE_MODE = 'success'
    foreach ($version in @('first', 'second')) {
        $env:SIDECAR_FIXTURE_VERSION = $version
        Invoke-FixturePackaging
        Assert-Equal (Get-Content -LiteralPath (Join-Path $bin 'qqmusic/version.txt') -Raw).Trim() $version 'QQ output was not replaced'
        Assert-Equal (Get-Content -LiteralPath (Join-Path $bin 'cloudflared.exe') -Raw).Trim() $version 'Cloudflared output was not replaced'
        Assert-Equal (Get-Content -LiteralPath (Join-Path $bin 'cloudflared-notices/NOTICE') -Raw).Trim() $version 'Notices were not replaced'
        Assert-NoTransactions
        if ($version -eq 'first') {
            Set-Content -LiteralPath (Join-Path $bin 'qqmusic/obsolete.txt') -Value 'stale'
            Set-Content -LiteralPath (Join-Path $bin 'cloudflared-notices/obsolete.txt') -Value 'stale'
        }
    }
    Assert-Equal (Test-Path -LiteralPath (Join-Path $bin 'qqmusic/obsolete.txt')) $false 'Stale QQ output survived replacement'
    Assert-Equal (Test-Path -LiteralPath (Join-Path $bin 'cloudflared-notices/obsolete.txt')) $false 'Stale notices survived replacement'
    Assert-Equal (Get-Content -LiteralPath (Join-Path $bin 'server.jar') -Raw).Trim() 'unrelated-server' 'Unowned server changed'
    Assert-Equal (Get-Content -LiteralPath (Join-Path $bin 'netease-api.exe') -Raw).Trim() 'unrelated-api' 'Unowned API changed'
    $env:SIDECAR_FIXTURE_VERSION = 'must-not-publish'
    Assert-PackagingFailure 'qq-throw' 'QQ fixture failure'
    Assert-PackagingFailure 'qq-error' 'QQ nonterminating fixture failure'
    Assert-PackagingFailure 'cloud-throw' 'Cloudflare fixture failure'
    Assert-PackagingFailure 'qq-native' 'native exit code 23'
    Assert-PackagingFailure 'cloud-native' 'native exit code 29'

    if ($IsWindows) {
        $env:SIDECAR_FIXTURE_MODE = 'success'
        $before = Get-Snapshot $bin
        $lock = [IO.File]::Open((Join-Path $bin 'cloudflared.exe'), [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
        try {
            $failed = $false
            try { Invoke-FixturePackaging } catch { $failed = $true }
            if (-not $failed) { throw 'Replacing a locked executable unexpectedly succeeded.' }
        } finally { $lock.Dispose() }
        Assert-Equal (Get-Snapshot $bin) $before 'Rollback did not restore QQ after a later backup failed'
        Assert-NoTransactions
    }

    $mockRepo = Join-Path $root 'mock-repo'
    $null = New-Item -ItemType Directory -Path (Join-Path $mockRepo 'music-party-web'), (Join-Path $mockRepo 'tools')
    Copy-Item -LiteralPath $buildLocal -Destination (Join-Path $mockRepo 'build-local.ps1')
    $tools = Join-Path $mockRepo 'tools'
    if ($IsWindows) {
        Set-Content -LiteralPath (Join-Path $tools 'npm.cmd') -Value "@exit /b 37`r`n" -Encoding ascii
    } else {
        Set-Content -LiteralPath (Join-Path $tools 'npm') -Value "#!/bin/sh`nexit 37`n" -Encoding ascii
        & chmod +x (Join-Path $tools 'npm')
        if ($LASTEXITCODE -ne 0) { throw 'Could not make the offline npm fixture executable.' }
    }
    $env:PATH = $tools + [IO.Path]::PathSeparator + $oldPath
    $buildOutput = @(& $pwsh -NoProfile -NonInteractive -File (Join-Path $mockRepo 'build-local.ps1') 2>&1)
    if ($LASTEXITCODE -eq 0) { throw 'build-local ignored a native npm failure.' }
    if (($buildOutput -join "`n") -notlike '*Frontend dependency installation failed*') {
        throw "build-local did not fail at the first native command: $($buildOutput -join "`n")"
    }
    if (Test-Path -LiteralPath (Join-Path $mockRepo 'src')) { throw 'build-local continued after npm failed.' }
    Write-Host 'Offline sidecar packaging tests passed.'
} finally {
    $env:SIDECAR_FIXTURE_MODE = $oldMode
    $env:SIDECAR_FIXTURE_VERSION = $oldVersion
    $env:SIDECAR_FIXTURE_PWSH = $oldPwsh
    $env:PATH = $oldPath
    Remove-TestTree
}

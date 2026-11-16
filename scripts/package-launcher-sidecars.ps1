#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$BinDir = (Join-Path $PSScriptRoot '../launcher/bin'),
    # Script overrides let CI use offline fixtures without invoking either downloader.
    [ValidateNotNullOrEmpty()]
    [string]$QqPackager = (Join-Path $PSScriptRoot 'package-qqmusic.ps1'),
    [ValidateNotNullOrEmpty()]
    [string]$CloudflarePackager = (Join-Path $PSScriptRoot 'package-cloudflared.ps1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }

function Assert-SafePath {
    param([string]$Path, [string]$Root, [switch]$AllowRoot, [switch]$Tree)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $boundary = $fullRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($boundary, $comparison) -and -not ($AllowRoot -and $fullPath.Equals($fullRoot, $comparison))) {
        throw "Path is outside the verified directory: $fullPath"
    }
    $ancestor = $fullPath
    while ($ancestor) {
        $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing a path through a reparse point: $ancestor"
            }
            if (-not $item.FullName.Equals([IO.Path]::GetFullPath($ancestor), $comparison)) {
                throw "Resolved path differs from the expected path: $ancestor"
            }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
    if ($Tree -and (Test-Path -LiteralPath $fullPath -PathType Container)) {
        foreach ($item in Get-ChildItem -LiteralPath $fullPath -Recurse -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing a tree containing a reparse point: $($item.FullName)"
            }
            if (-not $item.FullName.StartsWith($fullPath + [IO.Path]::DirectorySeparatorChar, $comparison)) {
                throw "Resolved child escaped the verified directory: $($item.FullName)"
            }
        }
    }
    return $fullPath
}

function Remove-SafeTree {
    param([string]$Path, [string]$Root, [switch]$AllowRoot)
    $verified = Assert-SafePath -Path $Path -Root $Root -AllowRoot:$AllowRoot -Tree
    if (Test-Path -LiteralPath $verified) {
        Remove-Item -LiteralPath $verified -Recurse -Force
    }
}

function Move-SafeItem {
    param([string]$Source, [string]$SourceRoot, [string]$Destination, [string]$DestinationRoot)
    $verifiedSource = Assert-SafePath -Path $Source -Root $SourceRoot -Tree
    $verifiedDestination = Assert-SafePath -Path $Destination -Root $DestinationRoot -Tree
    if (Test-Path -LiteralPath $verifiedDestination) {
        throw "Move destination must not exist: $verifiedDestination"
    }
    Move-Item -LiteralPath $verifiedSource -Destination $verifiedDestination
}

function Invoke-Packager {
    param([string]$Script, [string]$OutputDir)
    $global:LASTEXITCODE = 0
    & $Script -OutputDir $OutputDir
    if ($LASTEXITCODE -ne 0) {
        throw "Sidecar packager failed with native exit code ${LASTEXITCODE}: $Script"
    }
}

$bin = [IO.Path]::GetFullPath($BinDir, (Get-Location).ProviderPath).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$parent = Split-Path -Parent $bin
if (-not $parent -or -not (Test-Path -LiteralPath $parent -PathType Container)) {
    throw 'BinDir must have an existing parent directory and cannot be a filesystem root.'
}
$bin = Assert-SafePath -Path $bin -Root $parent -Tree
if ((Test-Path -LiteralPath $bin) -and -not (Test-Path -LiteralPath $bin -PathType Container)) {
    throw "BinDir must be a directory: $bin"
}
foreach ($script in @($QqPackager, $CloudflarePackager)) {
    if (-not (Test-Path -LiteralPath $script -PathType Leaf)) {
        throw "Sidecar packager script does not exist: $script"
    }
}
$QqPackager = (Resolve-Path -LiteralPath $QqPackager).ProviderPath
$CloudflarePackager = (Resolve-Path -LiteralPath $CloudflarePackager).ProviderPath
$token = [Guid]::NewGuid().ToString('N')
$stage = Join-Path $parent ".sidecars-stage-$token"
$backup = Join-Path $parent ".sidecars-backup-$token"
foreach ($path in @($stage, $backup)) {
    $null = Assert-SafePath -Path $path -Root $parent
    if (Test-Path -LiteralPath $path) { throw "Transaction directory already exists: $path" }
}
$owned = @('qqmusic', 'cloudflared.exe', 'cloudflared-notices')
$backedUp = [Collections.Generic.List[string]]::new()
$installed = [Collections.Generic.List[string]]::new()
$keepBackup = $false
$stageCreated = $false
$backupCreated = $false
try {
    $null = New-Item -ItemType Directory -Path $stage
    $stageCreated = $true
    $null = Assert-SafePath -Path $stage -Root $parent -Tree
    Invoke-Packager -Script $QqPackager -OutputDir (Join-Path $stage 'qqmusic')
    Invoke-Packager -Script $CloudflarePackager -OutputDir $stage
    foreach ($name in $owned) {
        $path = Assert-SafePath -Path (Join-Path $stage $name) -Root $stage -Tree
        $kind = if ($name -eq 'cloudflared.exe') { 'Leaf' } else { 'Container' }
        if (-not (Test-Path -LiteralPath $path -PathType $kind)) {
            throw "Sidecar packager did not produce the required output: $name"
        }
        $null = Assert-SafePath -Path (Join-Path $bin $name) -Root $bin -Tree
    }
    $null = Assert-SafePath -Path $backup -Root $parent
    $null = New-Item -ItemType Directory -Path $backup
    $backupCreated = $true
    $null = Assert-SafePath -Path $bin -Root $parent
    if (-not (Test-Path -LiteralPath $bin)) { $null = New-Item -ItemType Directory -Path $bin }
    try {
        foreach ($name in $owned) {
            $destination = Join-Path $bin $name
            if (Test-Path -LiteralPath $destination) {
                Move-SafeItem -Source $destination -SourceRoot $bin -Destination (Join-Path $backup $name) -DestinationRoot $backup
                $backedUp.Add($name)
            }
        }
        foreach ($name in $owned) {
            Move-SafeItem -Source (Join-Path $stage $name) -SourceRoot $stage -Destination (Join-Path $bin $name) -DestinationRoot $bin
            $installed.Add($name)
        }
    } catch {
        $promotionError = $_
        $rollbackErrors = [Collections.Generic.List[string]]::new()
        foreach ($name in $installed) {
            try { Remove-SafeTree -Path (Join-Path $bin $name) -Root $bin }
            catch { $rollbackErrors.Add($_.Exception.Message) }
        }
        foreach ($name in $backedUp) {
            try { Move-SafeItem -Source (Join-Path $backup $name) -SourceRoot $backup -Destination (Join-Path $bin $name) -DestinationRoot $bin }
            catch { $rollbackErrors.Add($_.Exception.Message) }
        }
        if ($rollbackErrors.Count -gt 0) {
            $keepBackup = $true
            throw "Sidecar promotion failed: $promotionError. Rollback could not finish; preserved backups at ${backup}: $($rollbackErrors -join '; ')"
        }
        throw $promotionError
    }
    Write-Host "Launcher sidecars are ready: $bin"
} finally {
    if ($stageCreated) { Remove-SafeTree -Path $stage -Root $parent }
    if ($backupCreated -and -not $keepBackup) { Remove-SafeTree -Path $backup -Root $parent }
}

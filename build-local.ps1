#requires -Version 7.0
# Music Party Local Build Script
# Requirements: Node.js, Java 21, Go, Wails CLI, Python 3.13, pkg (npm install -g pkg)
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$NeteaseSource = 'E:\Develop\Code\api-enhanced'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Item -LiteralPath $PSScriptRoot -Force).FullName
$comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$static = [IO.Path]::GetFullPath((Join-Path $repoRoot 'src/main/resources/static'))
$expectedStatic = Join-Path $repoRoot 'src/main/resources/static'

function Assert-StaticPath {
    if (-not $static.Equals([IO.Path]::GetFullPath($expectedStatic), $comparison) -or
        -not $static.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, $comparison)) {
        throw 'Refusing static cleanup outside the verified repository root.'
    }
    $ancestor = $static
    while ($ancestor) {
        $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                -not $item.FullName.Equals([IO.Path]::GetFullPath($ancestor), $comparison)) {
                throw "Refusing static cleanup through an unverified path: $ancestor"
            }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
    if (Test-Path -LiteralPath $static -PathType Container) {
        foreach ($item in Get-ChildItem -LiteralPath $static -Recurse -Force) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                -not $item.FullName.StartsWith($static + [IO.Path]::DirectorySeparatorChar, $comparison)) {
                throw "Refusing static cleanup through an unverified child: $($item.FullName)"
            }
        }
    }
}

Push-Location $repoRoot
try {
    Write-Host '--- 1. Building Frontend ---'
    Push-Location (Join-Path $repoRoot 'music-party-web')
    try {
        & npm install
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }

    Write-Host '--- 2. Building Java Backend ---'
    Assert-StaticPath
    if (Test-Path -LiteralPath $static) { Remove-Item -LiteralPath $static -Recurse -Force }
    $null = New-Item -ItemType Directory -Path $static
    Copy-Item -Path (Join-Path $repoRoot 'music-party-web/dist/*') -Destination $static -Recurse
    & mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Java backend build failed.' }

    $bin = Join-Path $repoRoot 'launcher/bin'
    $null = New-Item -ItemType Directory -Path $bin -Force
    Write-Host '--- 3. Building Netease API ---'
    Push-Location $NeteaseSource
    try {
        & npm install
        if ($LASTEXITCODE -ne 0) { throw 'Netease API dependency installation failed.' }
        & npx pkg . -t node18-win-x64 -o (Join-Path $bin 'netease-api.exe')
        if ($LASTEXITCODE -ne 0) { throw 'Netease API packaging failed.' }
    } finally { Pop-Location }

    Write-Host '--- 4. Preparing Launcher Assets ---'
    Copy-Item -Path (Join-Path $repoRoot 'target/music-party-*.jar') -Destination (Join-Path $bin 'server.jar')

    Write-Host '--- 5. Packaging Launcher Sidecars ---'
    & (Join-Path $repoRoot 'scripts/package-launcher-sidecars.ps1') -BinDir $bin

    Write-Host '--- 6. Building Wails Launcher ---'
    Push-Location (Join-Path $repoRoot 'launcher')
    try {
        & wails build -platform windows/amd64
        if ($LASTEXITCODE -ne 0) { throw 'Wails launcher build failed.' }
    } finally { Pop-Location }

    Write-Host 'DONE! Your EXE is at: launcher/build/bin/MusicParty.exe'
} finally { Pop-Location }

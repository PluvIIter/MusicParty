#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDir,
    [ValidateNotNullOrEmpty()]
    [string]$HostPython = 'python'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) {
    throw 'Build this Windows amd64 bundle on Windows with setup-python 3.13 and Git installed.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$sidecar = Join-Path $repoRoot 'sidecars/qqmusic'
$requirements = Join-Path $sidecar 'requirements.txt'
$requirementText = Get-Content -LiteralPath $requirements -Raw
$pin = [regex]::Matches($requirementText, '(?m)^qqmusic-api-python\s*@\s*git\+https://github\.com/l-1124/QQMusicApi\.git@([0-9a-f]{40})\s*$')
if ($pin.Count -ne 1) {
    throw 'requirements.txt must contain exactly one full, pinned QQMusicApi commit.'
}
$upstreamCommit = $pin[0].Groups[1].Value
$upstreamUrl = 'https://github.com/l-1124/QQMusicApi.git'
$pythonVersion = '3.13.9'
$pythonUrl = "https://www.python.org/ftp/python/$pythonVersion/python-$pythonVersion-embed-amd64.zip"
$pythonHashSource = "$pythonUrl.spdx.json"
# Pin the CPython package checksum from the release's official SPDX document, not a dependency checksum.
$pythonSha256 = '91d828c2da3a029b41699e918674a0cb379c02cf20dab9c501306885f837402a'

$hostCommand = Get-Command $HostPython -CommandType Application -ErrorAction Stop | Select-Object -First 1
$hostExecutable = $hostCommand.Source
& $hostExecutable -I -c 'import struct, sys; assert sys.version_info[:2] == (3, 13) and struct.calcsize("P") == 8 and sys.platform == "win32", "Use setup-python 3.13 x64"'
if ($LASTEXITCODE -ne 0) { throw 'Host Python must be CPython 3.13 x64 on Windows.' }
$null = Get-Command git -CommandType Application -ErrorAction Stop

$bundle = [IO.Path]::GetFullPath($OutputDir, (Get-Location).ProviderPath)
if (Test-Path -LiteralPath $bundle) {
    throw "OutputDir must be a new path; refusing to overwrite: $bundle"
}
$ancestor = [IO.DirectoryInfo](Split-Path -Parent $bundle)
while ($null -ne $ancestor) {
    if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "OutputDir cannot traverse a reparse point: $($ancestor.FullName)"
    }
    $ancestor = $ancestor.Parent
}
$null = New-Item -ItemType Directory -Path $bundle
$bundle = (Get-Item -LiteralPath $bundle).FullName
$buildDir = Join-Path $bundle '.build'
$pythonDir = Join-Path $bundle 'python'
$sitePackages = Join-Path $pythonDir 'Lib/site-packages'
$upstreamDir = Join-Path $bundle 'upstream'
$sourceDir = Join-Path $upstreamDir 'source'
$checkout = Join-Path $buildDir 'checkout'
$null = New-Item -ItemType Directory -Path $buildDir, $upstreamDir

function Invoke-Git {
    param([string[]]$GitArgs)
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git failed: $($GitArgs -join ' ')" }
}

try {
    $runtimeZip = Join-Path $buildDir 'python-embed.zip'
    Invoke-WebRequest -Uri $pythonUrl -OutFile $runtimeZip
    $actualHash = (Get-FileHash -LiteralPath $runtimeZip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $pythonSha256) {
        throw "CPython archive SHA256 mismatch: expected $pythonSha256; got $actualHash"
    }
    $sbomPath = Join-Path $upstreamDir 'python-runtime.spdx.json'
    Invoke-WebRequest -Uri $pythonHashSource -OutFile $sbomPath
    $sbom = Get-Content -LiteralPath $sbomPath -Raw | ConvertFrom-Json
    $runtimePackage = @($sbom.packages | Where-Object { $_.SPDXID -eq 'SPDXRef-PACKAGE-cpython' })
    if ($runtimePackage.Count -ne 1 -or $runtimePackage[0].downloadLocation -ne $pythonUrl) {
        throw 'Official SBOM does not describe the pinned runtime.'
    }
    $sbomHashes = @($runtimePackage[0].checksums | Where-Object { $_.algorithm -eq 'SHA256' })
    if ($sbomHashes.Count -ne 1 -or $sbomHashes[0].checksumValue -ne $pythonSha256) {
        throw 'Official SBOM checksum differs from the pinned runtime checksum.'
    }
    Expand-Archive -LiteralPath $runtimeZip -DestinationPath $pythonDir
    if (-not (Test-Path -LiteralPath (Join-Path $pythonDir 'LICENSE.txt') -PathType Leaf)) {
        throw 'CPython runtime license is missing.'
    }
    $null = New-Item -ItemType Directory -Path $sitePackages -Force
    [IO.File]::WriteAllText((Join-Path $pythonDir 'python313._pth'), "python313.zip`n.`nLib/site-packages`n..`n../upstream/source`nimport site`n", [Text.Encoding]::ASCII)

    Invoke-Git -GitArgs @('init', $checkout)
    Invoke-Git -GitArgs @('-C', $checkout, 'fetch', '--depth=1', $upstreamUrl, $upstreamCommit)
    Invoke-Git -GitArgs @('-C', $checkout, '-c', 'core.symlinks=false', 'checkout', '--detach', 'FETCH_HEAD')
    $actualCommit = (& git -C $checkout rev-parse HEAD)
    if ($LASTEXITCODE -ne 0 -or $actualCommit.Trim() -ne $upstreamCommit) {
        throw 'Upstream checkout does not match requirements.txt.'
    }
    $submodules = @(& git -C $checkout ls-files --stage | Where-Object { $_ -match '^160000 ' })
    if ($LASTEXITCODE -ne 0 -or $submodules.Count -gt 0) {
        throw 'Upstream contains submodules; a complete corresponding-source export needs review.'
    }
    $sourceZip = Join-Path $buildDir 'upstream-source.zip'
    Invoke-Git -GitArgs @('-C', $checkout, 'archive', '--format=zip', "--output=$sourceZip", $upstreamCommit)
    Expand-Archive -LiteralPath $sourceZip -DestinationPath $sourceDir
    foreach ($required in @('LICENSE', 'pyproject.toml', 'qqmusic_api/__init__.py', 'web/run.py', 'web/src/app.py', 'web/accounts.example.toml', 'web/config.example.toml')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceDir $required) -PathType Leaf)) {
            throw "Upstream corresponding source is missing $required"
        }
    }
    foreach ($name in @('.env', 'web/accounts.toml', 'web/config.toml')) {
        if (Test-Path -LiteralPath (Join-Path $sourceDir $name)) {
            throw "Upstream export contains active runtime configuration: $name"
        }
    }
    $sourceInventory = @'
import hashlib
import json
from pathlib import Path
import subprocess
import sys
checkout, source, output, commit = sys.argv[1:]
source = Path(source).resolve()
tracked = subprocess.check_output(["git", "-C", checkout, "ls-tree", "-r", "--name-only", "-z", commit])
expected = {name.decode("utf-8") for name in tracked.split(b"\0") if name}
actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()}
assert expected == actual, "Export is not the complete upstream tree"
files = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in sorted(expected)}
Path(output).write_text(json.dumps({"commit": commit, "files": files}, indent=2) + "\n", encoding="utf-8")
'@
    & $hostExecutable -I -c $sourceInventory $checkout $sourceDir (Join-Path $upstreamDir 'source-manifest.json') $upstreamCommit
    if ($LASTEXITCODE -ne 0) { throw 'Corresponding source is incomplete.' }
    Copy-Item -LiteralPath (Join-Path $sourceDir 'LICENSE') -Destination (Join-Path $upstreamDir 'LICENSE')
    [IO.File]::WriteAllText((Join-Path $upstreamDir 'COMMIT'), "$upstreamCommit`n", [Text.Encoding]::ASCII)

    & $hostExecutable -I -m pip --isolated install --disable-pip-version-check --no-warn-script-location --no-compile --only-binary=:all: --target $sitePackages --report (Join-Path $upstreamDir 'pip-install-report.json') -r $requirements
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the pinned sidecar requirements and Windows wheels.' }
    foreach ($name in @('requirements.txt', 'UPSTREAM-NOTICE.md')) {
        Copy-Item -LiteralPath (Join-Path $sidecar $name) -Destination (Join-Path $bundle $name)
    }
    $manifest = [ordered]@{
        pythonVersion = $pythonVersion
        pythonUrl = $pythonUrl
        pythonSha256 = $pythonSha256
        pythonHashSource = $pythonHashSource
        upstreamUrl = $upstreamUrl
        upstreamCommit = $upstreamCommit
        entryPoint = 'upstream/source/web/run.py'
        workingDirectory = 'upstream/source'
        requirementsSha256 = (Get-FileHash -LiteralPath $requirements -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $bundle 'bundle-manifest.json') -Encoding utf8NoBOM

    & $hostExecutable -I (Join-Path $PSScriptRoot 'test-qqmusic-bundle.py') --bundle $bundle
    if ($LASTEXITCODE -ne 0) { throw 'Bundled Python offline smoke test failed.' }
    Write-Host "QQMusic native Web bundle is ready: $bundle"
} finally {
    # Only remove our newly created staging tree, never a caller-selected output directory.
    $expectedBuildDir = [IO.Path]::GetFullPath((Join-Path $bundle '.build'))
    if (Test-Path -LiteralPath $buildDir) {
        $resolvedBuildDir = (Get-Item -LiteralPath $buildDir -Force).FullName
        if ($resolvedBuildDir -ne $expectedBuildDir -or (Split-Path -Parent $resolvedBuildDir) -ne $bundle) {
            throw 'Refusing cleanup outside the verified bundle staging directory.'
        }
        $reparsePoints = @(Get-Item -LiteralPath $resolvedBuildDir -Force) + @(Get-ChildItem -LiteralPath $resolvedBuildDir -Recurse -Force)
        if (@($reparsePoints | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
            throw 'Refusing staging cleanup through a reparse point.'
        }
        Remove-Item -LiteralPath $resolvedBuildDir -Recurse -Force
    }
}

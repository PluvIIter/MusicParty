#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) {
    throw 'Build this Windows amd64 bundle on Windows with PowerShell 7.'
}

$version = '2026.1.2'
$commit = 'd7c62aed71e2aaccbe9230b9928f0e52a53f11c4'
$releaseUrl = "https://github.com/cloudflare/cloudflared/releases/tag/$version"
$binaryUrl = "https://github.com/cloudflare/cloudflared/releases/download/$version/cloudflared-windows-amd64.exe"
# The executable pin is published in this official release's Checksums section.
$binarySha256 = '6304f5e1c017c038fb74a02c0157b2b63d4bc1f15709c639fcc08cb6dbe4c126'
$sourceUrl = "https://codeload.github.com/cloudflare/cloudflared/zip/$commit"
$sourceSha256 = '6e41862ba111debe8d47ec47aca1db0343d3d74e489c97eef334c27ac1be7095'
$sourcePrefix = "cloudflared-$commit/"

$bundle = [IO.Path]::GetFullPath($OutputDir, (Get-Location).ProviderPath)
$ancestor = [IO.DirectoryInfo]$bundle
while ($null -ne $ancestor) {
    if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "OutputDir cannot traverse a reparse point: $($ancestor.FullName)"
    }
    $ancestor = $ancestor.Parent
}
if ((Test-Path -LiteralPath $bundle) -and -not (Test-Path -LiteralPath $bundle -PathType Container)) {
    throw "OutputDir must be a directory: $bundle"
}
$executable = Join-Path $bundle 'cloudflared.exe'
$notices = Join-Path $bundle 'cloudflared-notices'
foreach ($target in @($executable, $notices)) {
    if (Test-Path -LiteralPath $target) {
        throw "Use a clean release directory; refusing to overwrite: $target"
    }
}
$null = [IO.Directory]::CreateDirectory($bundle)
$null = New-Item -ItemType Directory -Path $notices
$sourceDir = Join-Path $notices 'source'
$sourceZip = Join-Path $notices "cloudflared-$version-source.zip"
$download = Join-Path $notices 'cloudflared.download'

function Assert-Sha256 {
    param([string]$Path, [string]$Expected)
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected) {
        throw "SHA256 mismatch for ${Path}: expected $Expected; got $actual"
    }
}

Invoke-WebRequest -Uri $binaryUrl -OutFile $download
Assert-Sha256 -Path $download -Expected $binarySha256
Invoke-WebRequest -Uri $sourceUrl -OutFile $sourceZip
Assert-Sha256 -Path $sourceZip -Expected $sourceSha256

$null = [IO.Directory]::CreateDirectory($sourceDir)
$sourceBoundary = $sourceDir + [IO.Path]::DirectorySeparatorChar
$inventory = [ordered]@{}
$archive = [IO.Compression.ZipFile]::OpenRead($sourceZip)
try {
    foreach ($entry in $archive.Entries) {
        if (-not $entry.FullName.StartsWith($sourcePrefix, [StringComparison]::Ordinal)) {
            throw "Unexpected source archive root: $($entry.FullName)"
        }
        $relative = $entry.FullName.Substring($sourcePrefix.Length)
        if ($relative.Length -eq 0) { continue }
        if ($relative.Contains('\') -or $relative -match '(^|/)\.\.?(/|$)|[<>:"|?*]' -or (($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
            throw "Unsafe source archive entry: $relative"
        }
        $destination = [IO.Path]::GetFullPath((Join-Path $sourceDir $relative))
        if (-not $destination.StartsWith($sourceBoundary, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Source archive path escaped the bundle: $relative"
        }
        if ($entry.FullName.EndsWith('/')) {
            $null = [IO.Directory]::CreateDirectory($destination)
            continue
        }
        if ($inventory.Contains($relative)) { throw "Duplicate source entry: $relative" }
        $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $destination))
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $false)
        $inventory[$relative] = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    }
} finally {
    $archive.Dispose()
}
foreach ($required in @('LICENSE', 'go.mod', 'go.sum', 'vendor/modules.txt', 'cmd/cloudflared/main.go')) {
    if (-not $inventory.Contains($required)) { throw "Missing corresponding source: $required" }
}
$licensePath = Join-Path $sourceDir 'LICENSE'
if ((Get-Content -LiteralPath $licensePath -Raw) -notmatch 'Apache License\s+Version 2\.0') {
    throw 'Upstream Apache 2.0 license is missing.'
}
Copy-Item -LiteralPath $licensePath -Destination (Join-Path $notices 'LICENSE')
$noticePaths = @($inventory.Keys | Where-Object { ($_ -split '/')[-1] -match '^(LICENSE|NOTICE|COPYING|COPYRIGHT|AUTHORS|PATENTS)([._-].*)?$' })
$vendorNotices = @($noticePaths | Where-Object { $_.StartsWith('vendor/') })
if ($vendorNotices.Count -lt 100) { throw 'Vendored dependency license/notice files are incomplete.' }
$upstreamNotice = Join-Path $sourceDir 'NOTICE'
if (Test-Path -LiteralPath $upstreamNotice -PathType Leaf) {
    Copy-Item -LiteralPath $upstreamNotice -Destination (Join-Path $notices 'NOTICE')
} else {
    $notice = @"
cloudflared $version, distributed unmodified from Cloudflare's official release.
Project: https://github.com/cloudflare/cloudflared
License: Apache License 2.0; see LICENSE and source/LICENSE.
Corresponding source commit: $commit
Source archive: $sourceUrl
This upstream revision has no top-level NOTICE file. This packaging notice is not an upstream NOTICE.
The complete upstream source and vendored Go dependencies are retained in source/.
Their original license, copyright and NOTICE files are preserved at their original paths;
notice-files.json indexes them without replacing source-file copyright notices.
"@
    Set-Content -LiteralPath (Join-Path $notices 'NOTICE') -Value $notice -Encoding utf8NoBOM
}
$noticePaths | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $notices 'notice-files.json') -Encoding utf8NoBOM
[ordered]@{ commit = $commit; files = $inventory } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $notices 'source-manifest.json') -Encoding utf8NoBOM

# A failed build keeps its evidence in notices and must be retried in a clean release directory.
[IO.File]::Move($download, $executable, $false)
Assert-Sha256 -Path $executable -Expected $binarySha256
$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $executable
$startInfo.ArgumentList.Add('--version')
$startInfo.WorkingDirectory = $notices
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.Environment['PATH'] = ''
$process = [Diagnostics.Process]::new()
$process.StartInfo = $startInfo
try {
    if (-not $process.Start()) { throw 'Could not start the bundled cloudflared.exe.' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(30000)) {
        $process.Kill($true)
        $process.WaitForExit()
        throw 'cloudflared.exe --version timed out.'
    }
    $versionOutput = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0 -or $versionOutput -notmatch "(?m)^cloudflared version $([regex]::Escape($version))(\s|$)") {
        throw "Bundled cloudflared version check failed (exit $($process.ExitCode)): $versionOutput"
    }
} finally {
    $process.Dispose()
}
$manifest = [ordered]@{
    version = $version
    platform = 'windows-amd64'
    binaryUrl = $binaryUrl
    binarySha256 = $binarySha256
    binaryHashSource = "$releaseUrl (Checksums)"
    releaseUrl = $releaseUrl
    sourceCommit = $commit
    sourceUrl = $sourceUrl
    sourceSha256 = $sourceSha256
    sourceSha256Provenance = 'SHA256 of the complete official commit archive, independently downloaded when pinning this script.'
    sourceArchive = "cloudflared-$version-source.zip"
    sourceDirectory = 'source'
    vendorNoticeCount = $vendorNotices.Count
    smokeCommand = 'cloudflared.exe --version'
    smokeOutput = $versionOutput.Trim()
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $notices 'bundle-manifest.json') -Encoding utf8NoBOM
Write-Host "cloudflared $version bundle is ready: $executable"

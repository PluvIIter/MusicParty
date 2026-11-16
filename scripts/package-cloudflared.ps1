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

$inventory = [ordered]@{}
$entryNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$archive = [IO.Compression.ZipFile]::OpenRead($sourceZip)
try {
    foreach ($entry in $archive.Entries) {
        if (-not $entry.FullName.StartsWith($sourcePrefix, [StringComparison]::Ordinal)) {
            throw "Unexpected source archive root: $($entry.FullName)"
        }
        $relative = $entry.FullName.Substring($sourcePrefix.Length)
        if ($relative.Length -eq 0) { continue }
        $path = $relative.TrimEnd('/')
        if ($path.Length -eq 0 -or $relative.Contains('\') -or $relative -match '//|(^|/)\.\.?(/|$)|[<>:"|?*\x00-\x1f]|^/|[. ](/|$)' -or (($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
            throw "Unsafe source archive entry: $relative"
        }
        if (-not $entryNames.Add($path)) { throw "Duplicate source entry: $relative" }
        if ($entry.FullName.EndsWith('/')) { continue }
        $stream = $entry.Open()
        $hasher = [Security.Cryptography.SHA256]::Create()
        try {
            $inventory[$relative] = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
        } finally {
            $hasher.Dispose()
            $stream.Dispose()
        }
    }
    foreach ($required in @('LICENSE', 'go.mod', 'go.sum', 'vendor/modules.txt', 'cmd/cloudflared/main.go')) {
        if (-not $inventory.Contains($required)) { throw "Missing corresponding source: $required" }
    }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry($sourcePrefix + 'LICENSE'), (Join-Path $notices 'LICENSE'), $false)
} finally {
    $archive.Dispose()
}
if ((Get-Content -LiteralPath (Join-Path $notices 'LICENSE') -Raw) -notmatch 'Apache License\s+Version 2\.0') {
    throw 'Upstream Apache 2.0 license is missing.'
}
$noticePaths = @($inventory.Keys | Where-Object { ($_ -split '/')[-1] -match '^(LICENSE|NOTICE|COPYING|COPYRIGHT|AUTHORS|PATENTS)([._-].*)?$' })
$vendorNotices = @($noticePaths | Where-Object { $_.StartsWith('vendor/') })
if ($vendorNotices.Count -lt 100) { throw 'Vendored dependency license/notice files are incomplete.' }
if (-not @($vendorNotices | Where-Object { $_.EndsWith('/NOTICE') }).Count) { throw 'Original dependency NOTICE files are missing.' }
$upstreamNotice = if ($inventory.Contains('NOTICE')) {
    "The upstream NOTICE is preserved at ${sourcePrefix}NOTICE inside the source archive."
} else {
    'This upstream revision has no top-level NOTICE file.'
}
$notice = @"
cloudflared $version, distributed unmodified from Cloudflare's official release.
Project: https://github.com/cloudflare/cloudflared
License: Apache License 2.0; see LICENSE and ${sourcePrefix}LICENSE inside the source archive.
Corresponding source commit: $commit
Source archive: $(Split-Path -Leaf $sourceZip)
Source archive URL: $sourceUrl
Source archive SHA256: $sourceSha256
Archive-internal source prefix: $sourcePrefix
$upstreamNotice
This packaging notice is not an upstream NOTICE.
The complete upstream source and vendored Go dependencies are retained only in the source archive.
Their original license, copyright and NOTICE files remain at their archive-internal paths;
notice-files.json indexes complete archive entry names, including the prefix above.
source-manifest.json hashes every source file relative to that archive-internal prefix.
"@
Set-Content -LiteralPath (Join-Path $notices 'NOTICE') -Value $notice -Encoding utf8NoBOM
@($noticePaths | ForEach-Object { $sourcePrefix + $_ }) | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $notices 'notice-files.json') -Encoding utf8NoBOM
[ordered]@{
    commit = $commit
    sourceArchive = Split-Path -Leaf $sourceZip
    sourcePrefix = $sourcePrefix
    storage = 'archive'
    files = $inventory
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $notices 'source-manifest.json') -Encoding utf8NoBOM

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
    sourcePrefix = $sourcePrefix
    storage = 'archive'
    vendorNoticeCount = $vendorNotices.Count
    smokeCommand = 'cloudflared.exe --version'
    smokeOutput = $versionOutput.Trim()
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $notices 'bundle-manifest.json') -Encoding utf8NoBOM
Write-Host "cloudflared $version bundle is ready: $executable"

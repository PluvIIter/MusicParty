import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import urllib.request
import zipfile


VERSION = "2026.1.2"
COMMIT = "d7c62aed71e2aaccbe9230b9928f0e52a53f11c4"
BINARY_SHA256 = "6304f5e1c017c038fb74a02c0157b2b63d4bc1f15709c639fcc08cb6dbe4c126"
SOURCE_SHA256 = "6e41862ba111debe8d47ec47aca1db0343d3d74e489c97eef334c27ac1be7095"
RELEASE_URL = f"https://github.com/cloudflare/cloudflared/releases/tag/{VERSION}"
BINARY_URL = f"https://github.com/cloudflare/cloudflared/releases/download/{VERSION}/cloudflared-windows-amd64.exe"
SOURCE_URL = f"https://codeload.github.com/cloudflare/cloudflared/zip/{COMMIT}"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def static_check():
    script = Path(__file__).with_name("package-cloudflared.ps1").read_text(encoding="utf-8")
    for name, expected in [("version", VERSION), ("commit", COMMIT), ("binarySha256", BINARY_SHA256),
                           ("sourceSha256", SOURCE_SHA256)]:
        match = re.search(rf"(?m)^\${name} = '([^']+)'$", script)
        require(match and match[1] == expected, f"Unexpected PowerShell pin: {name}")
    for expected in ["[string]$OutputDir", "Get-FileHash", "-Algorithm SHA256", "refusing to overwrite",
                     "ArgumentList.Add('--version')", "WaitForExit(30000)", "source-manifest.json",
                     "vendor/modules.txt", "notice-files.json", "if (-not $IsWindows)"]:
        require(expected in script, f"Missing package contract: {expected}")
    require("/latest" not in script, "Package script must not resolve latest")
    require(not re.search(r"(?mi)^\s*(?:&.*\b(?:tunnel|service)\b|Start-Process|Install-Package)", script),
            "Package smoke must not install a service or create a tunnel")
    print("Static cloudflared package contract passed (does not execute PowerShell).")


def check_official_release():
    with urllib.request.urlopen(RELEASE_URL, timeout=60) as response:
        html = response.read().decode("utf-8")
    hashes = set(re.findall(r"cloudflared-windows-amd64\.exe: ([0-9a-f]{64})", html))
    require(hashes == {BINARY_SHA256}, "Official release Checksums differs from the executable pin")
    require(f"/cloudflare/cloudflared/commit/{COMMIT}" in html, "Official release tag points to another commit")
    print(f"Official release checksum and tag verified: {RELEASE_URL}")


def validate_layout(bundle):
    executable = bundle / "cloudflared.exe"
    notices = bundle / "cloudflared-notices"
    required = ["LICENSE", "NOTICE", "bundle-manifest.json", "source-manifest.json", "notice-files.json",
                f"cloudflared-{VERSION}-source.zip", "source/LICENSE", "source/go.mod", "source/go.sum",
                "source/vendor/modules.txt", "source/cmd/cloudflared/main.go"]
    require(executable.is_file(), "Missing cloudflared.exe")
    for name in required:
        require((notices / name).is_file(), f"Missing notice/source file: {name}")
    require(sha256(executable) == BINARY_SHA256, "Executable checksum mismatch")
    with executable.open("rb") as binary:
        require(binary.read(2) == b"MZ", "Executable is not a Windows PE")
        binary.seek(0x3C)
        offset = struct.unpack("<I", binary.read(4))[0]
        binary.seek(offset)
        require(binary.read(6) == b"PE\0\0\x64\x86", "Executable is not Windows AMD64")
    manifest = json.loads((notices / "bundle-manifest.json").read_text(encoding="utf-8"))
    for key, value in {"version": VERSION, "sourceCommit": COMMIT, "binarySha256": BINARY_SHA256,
                       "sourceSha256": SOURCE_SHA256, "binaryUrl": BINARY_URL, "sourceUrl": SOURCE_URL,
                       "releaseUrl": RELEASE_URL, "platform": "windows-amd64",
                       "smokeCommand": "cloudflared.exe --version"}.items():
        require(manifest[key] == value, f"Manifest mismatch: {key}")
    require(re.match(rf"cloudflared version {re.escape(VERSION)}(?:\s|$)", manifest["smokeOutput"]),
            "Manifest has no successful version smoke output")
    source = notices / "source"
    require((notices / "LICENSE").read_bytes() == (source / "LICENSE").read_bytes(), "License mismatch")
    require("Apache License" in (notices / "LICENSE").read_text(encoding="utf-8"), "Missing Apache license")
    inventory = json.loads((notices / "source-manifest.json").read_text(encoding="utf-8"))
    require(inventory["commit"] == COMMIT and inventory["files"], "Missing source inventory")
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()}
    require(actual == set(inventory["files"]), "Source inventory differs from exported files")
    for name, digest in inventory["files"].items():
        target = (source / name).resolve(strict=True)
        require(target.is_relative_to(source), f"Source path escapes bundle: {name}")
        require(sha256(target) == digest, f"Source checksum mismatch: {name}")
    archive_path = notices / f"cloudflared-{VERSION}-source.zip"
    require(sha256(archive_path) == SOURCE_SHA256, "Source archive checksum mismatch")
    prefix = f"cloudflared-{COMMIT}/"
    with zipfile.ZipFile(archive_path) as archive:
        files = {entry.filename[len(prefix):]: entry for entry in archive.infolist() if not entry.is_dir()}
        require(set(files) == actual, "Source export is not the complete official archive")
        for name, entry in files.items():
            require(entry.filename.startswith(prefix), "Archive has unexpected source root")
            require(hashlib.sha256(archive.read(entry)).hexdigest() == inventory["files"][name],
                    f"Source export differs from official archive: {name}")
    notice_files = json.loads((notices / "notice-files.json").read_text(encoding="utf-8"))
    expected = {name for name in actual if re.fullmatch(r"(?:LICENSE|NOTICE|COPYING|COPYRIGHT|AUTHORS|PATENTS)(?:[._-].*)?",
                                                       name.rsplit("/", 1)[-1], re.IGNORECASE)}
    require(set(notice_files) == expected, "Dependency notice index incomplete")
    vendor_count = sum(name.startswith("vendor/") for name in expected)
    require(vendor_count >= 100 and manifest["vendorNoticeCount"] == vendor_count, "Missing vendored notices")
    require(any(name.startswith("vendor/") and name.endswith("/NOTICE") for name in expected),
            "Missing original dependency NOTICE files")
    print("Bundled executable, full official source and vendored notice integrity passed.")


def smoke(bundle):
    require(os.name == "nt", "Run the executable smoke on Windows, or use --static-only for review")
    env = dict(os.environ, PATH="")
    with tempfile.TemporaryDirectory(prefix="cloudflared-smoke-") as working:
        result = subprocess.run([str(bundle / "cloudflared.exe"), "--version"], cwd=working, env=env,
                                stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, f"Version smoke exited {result.returncode}: {result.stderr}")
    require(re.search(rf"(?m)^cloudflared version {re.escape(VERSION)}(?:\s|$)", result.stdout + result.stderr),
            f"Unexpected version: {result.stdout} {result.stderr}")
    with tempfile.TemporaryDirectory(prefix="cloudflared-help-") as working:
        help_result = subprocess.run([str(bundle / "cloudflared.exe"), "tunnel", "--help"], cwd=working, env=env,
                                     stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
    require(help_result.returncode == 0, "Bundled tunnel command help failed")
    help_text = help_result.stdout + help_result.stderr
    for option in ["--config", "--no-autoupdate", "--output", "--protocol", "--url", "--metrics"]:
        require(option in help_text, f"Bundled cloudflared does not declare required option: {option}")
    print("Offline --version and tunnel --help smoke passed with empty PATH; no service or tunnel was started.")


def main():
    parser = argparse.ArgumentParser(description="Verify the pinned cloudflared Windows package without starting a tunnel.")
    parser.add_argument("--output-dir", type=Path, help="Parent containing cloudflared.exe, e.g. launcher/bin")
    parser.add_argument("--static-only", action="store_true", help="Review script/layout without executing Windows code")
    parser.add_argument("--check-official-release", action="store_true", help="Build-time opt-in network check of official release HTML")
    options = parser.parse_args()
    static_check()
    if options.check_official_release:
        check_official_release()
    if options.output_dir:
        bundle = options.output_dir.resolve(strict=True)
        validate_layout(bundle)
        if not options.static_only:
            smoke(bundle)
    else:
        require(options.static_only, "Pass --output-dir for bundle smoke, or --static-only for script review")


if __name__ == "__main__":
    main()

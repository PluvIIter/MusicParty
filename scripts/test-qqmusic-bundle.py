import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


PROBE = r'''
import importlib.metadata as metadata
import json
from pathlib import Path
import site
import struct
import sys
import web.src.app as app
import fastapi
import uvicorn
import qqmusic_api
import griffe
import pydantic_settings
import dotenv
import cryptography.hazmat.bindings._rust
import orjson
import pydantic_core

bundle = Path(sys.argv[1]).resolve()
source = bundle / "upstream/source"
assert sys.version_info[:3] == (3, 13, 9), sys.version
assert struct.calcsize("P") == 8
assert Path(sys.executable).resolve() == bundle / "python/python.exe"
assert sys.flags.isolated and sys.flags.ignore_environment and sys.flags.no_user_site
assert site.ENABLE_USER_SITE is False
for entry in sys.path:
    assert entry and Path(entry).resolve().is_relative_to(bundle), sys.path
for module in list(sys.modules.values()):
    filename = getattr(module, "__file__", None)
    if filename and not filename.startswith("<"):
        assert Path(filename).resolve().is_relative_to(bundle), filename
assert Path(app.__file__).resolve() == source / "web/src/app.py"
for module in (fastapi, uvicorn, griffe, pydantic_settings, dotenv):
    assert Path(module.__file__).resolve().is_relative_to(bundle / "python/Lib/site-packages")
assert Path(qqmusic_api.__file__).resolve().is_relative_to(bundle)
manifest = json.loads((bundle / "bundle-manifest.json").read_text(encoding="utf-8"))
distributions = list(metadata.distributions())
report = json.loads((bundle / "upstream/pip-install-report.json").read_text(encoding="utf-8"))
normalize = lambda name: name.lower().replace("_", "-").replace(".", "-")
expected = {normalize(item["metadata"]["name"]) for item in report["install"]}
assert len(expected) >= 7
assert {normalize(d.metadata["Name"]) for d in distributions} == expected
for distribution in distributions:
    files = distribution.files
    assert files, f"Missing dist-info RECORD: {distribution.metadata['Name']}"
    assert any(str(p).endswith(".dist-info/METADATA") for p in files)
    assert any(str(p).endswith(".dist-info/RECORD") for p in files)
    for filename in files:
        installed = Path(distribution.locate_file(filename)).resolve()
        if installed.is_relative_to(bundle / "python/Lib/site-packages"):
            assert installed.is_file(), f"Missing installed file: {filename}"
    for filename in distribution.metadata.get_all("License-File", []):
        candidates = [p for p in files if str(p).endswith(".dist-info/licenses/" + filename)
                      or str(p).endswith(".dist-info/" + filename)]
        assert candidates, f"Missing license {filename}: {distribution.metadata['Name']}"
upstream = metadata.distribution("qqmusic-api-python")
direct_url = json.loads(upstream.read_text("direct_url.json"))
assert direct_url["vcs_info"]["commit_id"] == manifest["upstreamCommit"], direct_url
assert app.settings.credential.enabled is False
print(json.dumps({"python": sys.version.split()[0], "distributions": len(distributions)}))
'''


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_layout(bundle):
    required = ["requirements.txt", "UPSTREAM-NOTICE.md", "bundle-manifest.json",
                "python/python.exe", "python/python313.zip", "python/python313._pth", "python/LICENSE.txt",
                "upstream/LICENSE", "upstream/COMMIT", "upstream/source/LICENSE",
                "upstream/source/pyproject.toml", "upstream/source/qqmusic_api/__init__.py",
                "upstream/source/web/run.py", "upstream/source/web/src/app.py",
                "upstream/source/web/accounts.example.toml", "upstream/source/web/config.example.toml",
                "upstream/source-manifest.json", "upstream/python-runtime.spdx.json", "upstream/pip-install-report.json"]
    for name in required:
        require((bundle / name).is_file(), f"Missing bundle file: {name}")
    for name in ["app.py", "gateway.py", "upstream/source/.env", "upstream/source/web/accounts.toml",
                 "upstream/source/web/config.toml"]:
        require(not (bundle / name).exists(), f"Unexpected private gateway or runtime configuration: {name}")
    require((bundle / "python/Lib/site-packages").is_dir(), "Missing bundled site-packages")
    paths = (bundle / "python/python313._pth").read_text(encoding="ascii").splitlines()
    require(paths == ["python313.zip", ".", "Lib/site-packages", "..", "../upstream/source", "import site"],
            "Unexpected embedded Python search paths")
    manifest = json.loads((bundle / "bundle-manifest.json").read_text(encoding="utf-8"))
    require(manifest["pythonVersion"] == "3.13.9", "Unexpected Python runtime version")
    require(manifest["entryPoint"] == "upstream/source/web/run.py", "Unexpected native Web entry point")
    require(manifest["workingDirectory"] == "upstream/source", "Unexpected native Web working directory")
    requirements = (bundle / "requirements.txt").read_bytes()
    require(hashlib.sha256(requirements).hexdigest() == manifest["requirementsSha256"], "Requirements checksum mismatch")
    commit = (bundle / "upstream/COMMIT").read_text(encoding="ascii").strip()
    require(commit == manifest["upstreamCommit"] == "c046788dd1f11daacc74da487cbbf1bcd8ac6f33",
            "Upstream source revision mismatch")
    require(f"QQMusicApi.git@{commit}" in requirements.decode(), "Upstream source differs from requirements")
    require((bundle / "upstream/LICENSE").read_bytes() == (bundle / "upstream/source/LICENSE").read_bytes(),
            "Upstream license mismatch")
    source = bundle / "upstream/source"
    inventory = json.loads((bundle / "upstream/source-manifest.json").read_text(encoding="utf-8"))
    require(inventory["commit"] == commit and inventory["files"], "Missing source inventory")
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()}
    require(actual == set(inventory["files"]), "Corresponding source file inventory mismatch")
    for name, digest in inventory["files"].items():
        target = (source / name).resolve()
        require(target.is_relative_to(source), f"Source path escaped the bundle: {name}")
        require(hashlib.sha256(target.read_bytes()).hexdigest() == digest, f"Source checksum mismatch: {name}")
    require(not list(source.rglob(".git")), "Source export must not retain Git metadata")


def main():
    parser = argparse.ArgumentParser(description="Test the Windows native QQMusic Web bundle offline.")
    parser.add_argument("--bundle", type=Path, required=True)
    options = parser.parse_args()
    require(os.name == "nt", "Run this smoke test on Windows; it executes the bundled python.exe")
    bundle = options.bundle.resolve(strict=True)
    validate_layout(bundle)
    spec = importlib.util.spec_from_file_location("native_smoke", Path(__file__).resolve().parents[1] / "sidecars/qqmusic/smoke.py")
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    python = bundle / "python/python.exe"
    source = bundle / "upstream/source"
    with tempfile.TemporaryDirectory(prefix="qqmusic-bundle-smoke-") as directory:
        working = Path(directory)
        poison = working / "poison"
        poison.mkdir()
        for name in ["web", "qqmusic_api", "fastapi", "uvicorn", "sitecustomize", "usercustomize"]:
            (poison / f"{name}.py").write_text('raise RuntimeError("Imported host pollution")\n', encoding="utf-8")
        base_env = {key: value for key, value in os.environ.items() if not key.upper().startswith(("PYTHON", "QQMUSIC_"))}
        base_env |= {"PATH": "", "QQMUSIC_CREDENTIAL_ENABLED": "false", "QQMUSIC_LOGGING_LEVEL": "WARNING"}
        polluted_env = base_env | {"PYTHONHOME": str(poison), "PYTHONPATH": str(poison),
                                   "PYTHONUSERBASE": str(poison), "PYTHONSTARTUP": str(poison / "web.py")}
        for label, env in [("clean", base_env), ("polluted-python-environment", polluted_env)]:
            probe = subprocess.run([str(python), "-I", "-B", "-c", PROBE, str(bundle)], cwd=poison, env=env,
                                   stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            require(probe.returncode == 0, f"Bundled import/isolation check failed ({label}):\n{probe.stdout}\n{probe.stderr}")
            print(f"Bundled import/isolation check passed ({label}): {probe.stdout.strip()}")
            native.smoke(source, python, env, working, label)
    validate_layout(bundle)


if __name__ == "__main__":
    main()

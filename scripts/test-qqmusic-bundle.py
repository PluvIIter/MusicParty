import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time


PROBE = r'''
import importlib.metadata as metadata
import json
from pathlib import Path
import site
import struct
import sys
import app
import gateway
import fastapi
import uvicorn
import qqmusic_api
import cryptography.hazmat.bindings._rust
import orjson
import pydantic_core

bundle = Path(sys.argv[1]).resolve()
assert sys.version_info[:3] == (3, 13, 9), sys.version
assert struct.calcsize("P") == 8
assert Path(sys.executable).resolve() == bundle / "python" / "python.exe"
assert sys.flags.isolated and sys.flags.ignore_environment and sys.flags.no_user_site
assert site.ENABLE_USER_SITE is False
for entry in sys.path:
    assert entry and Path(entry).resolve().is_relative_to(bundle), sys.path
for module in list(sys.modules.values()):
    filename = getattr(module, "__file__", None)
    if filename and not filename.startswith("<"):
        assert Path(filename).resolve().is_relative_to(bundle), filename
for module in (app, gateway):
    assert Path(module.__file__).resolve().parent == bundle
for module in (fastapi, uvicorn, qqmusic_api):
    assert Path(module.__file__).resolve().is_relative_to(bundle / "python" / "Lib" / "site-packages")
manifest = json.loads((bundle / "bundle-manifest.json").read_text(encoding="utf-8"))
distributions = list(metadata.distributions())
report = json.loads((bundle / "upstream" / "pip-install-report.json").read_text(encoding="utf-8"))
normalize = lambda name: name.lower().replace("_", "-").replace(".", "-")
expected = {normalize(item["metadata"]["name"]) for item in report["install"]}
assert len(expected) >= 3
assert {normalize(d.metadata["Name"]) for d in distributions} == expected
for distribution in distributions:
    files = distribution.files
    assert files, f"Missing dist-info RECORD: {distribution.metadata['Name']}"
    assert any(str(p).endswith(".dist-info/METADATA") for p in files)
    assert any(str(p).endswith(".dist-info/RECORD") for p in files)
    for filename in files:
        installed = Path(distribution.locate_file(filename)).resolve()
        if installed.is_relative_to(bundle / "python" / "Lib" / "site-packages"):
            assert installed.is_file(), f"Missing installed file: {filename}"
    for filename in distribution.metadata.get_all("License-File", []):
        candidates = [p for p in files if str(p).endswith(".dist-info/licenses/" + filename)
                      or str(p).endswith(".dist-info/" + filename)]
        assert candidates, f"Missing license {filename}: {distribution.metadata['Name']}"
upstream = metadata.distribution("qqmusic-api-python")
direct_url = json.loads(upstream.read_text("direct_url.json"))
assert direct_url["vcs_info"]["commit_id"] == manifest["upstreamCommit"], direct_url
print(json.dumps({"python": sys.version.split()[0], "distributions": len(distributions)}))
'''

# Reject accidental upstream access even if a future constructor starts doing network I/O.
BOOTSTRAP = r'''
import ipaddress
import runpy
import sys

def loopback(address):
    try:
        return ipaddress.ip_address(address).is_loopback
    except ValueError:
        return False

def offline(event, args):
    if event == "socket.getaddrinfo" and args[0] is not None and not loopback(args[0]):
        raise RuntimeError("Offline smoke test blocked DNS")
    if event in {"socket.connect", "socket.bind", "socket.sendto"}:
        address = args[1]
        if not isinstance(address, tuple) or not loopback(address[0]):
            raise RuntimeError("Offline smoke test blocked non-loopback network I/O")

sys.addaudithook(offline)
runpy.run_path(sys.argv[1], run_name="__main__")
'''


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_layout(bundle):
    required = ["app.py", "gateway.py", "requirements.txt", "UPSTREAM-NOTICE.md", "bundle-manifest.json",
                "python/python.exe", "python/python313.zip", "python/python313._pth", "python/LICENSE.txt",
                "upstream/LICENSE", "upstream/COMMIT", "upstream/source/LICENSE",
                "upstream/source/pyproject.toml", "upstream/source/qqmusic_api/__init__.py",
                "upstream/source-manifest.json", "upstream/python-runtime.spdx.json", "upstream/pip-install-report.json"]
    for name in required:
        require((bundle / name).is_file(), f"Missing bundle file: {name}")
    require((bundle / "python/Lib/site-packages").is_dir(), "Missing bundled site-packages")
    paths = (bundle / "python/python313._pth").read_text(encoding="ascii").splitlines()
    require(paths == ["python313.zip", ".", "Lib/site-packages", "..", "import site"], "Unexpected embedded Python search paths")
    manifest = json.loads((bundle / "bundle-manifest.json").read_text(encoding="utf-8"))
    require(manifest["pythonVersion"] == "3.13.9", "Unexpected Python runtime version")
    requirements = (bundle / "requirements.txt").read_bytes()
    require(hashlib.sha256(requirements).hexdigest() == manifest["requirementsSha256"], "Requirements checksum mismatch")
    commit = (bundle / "upstream/COMMIT").read_text(encoding="ascii").strip()
    require(commit == manifest["upstreamCommit"] and len(commit) == 40, "Upstream source revision mismatch")
    require(f"QQMusicApi.git@{commit}" in requirements.decode(), "Upstream source differs from requirements")
    require((bundle / "upstream/LICENSE").read_bytes() == (bundle / "upstream/source/LICENSE").read_bytes(), "Upstream license mismatch")
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


def request(port, method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        return response.status, json.loads(response.read())
    finally:
        connection.close()


def smoke(bundle, python, env, working, label):
    token = secrets.token_urlsafe(32)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    child_env = env | {"QQMUSIC_GATEWAY_TOKEN": token, "QQMUSIC_BIND": "127.0.0.1",
                       "QQMUSIC_PORT": str(port), "QQMUSIC_DEVICE_PATH": str(working / f"device-{label}.json")}
    with tempfile.TemporaryFile(mode="w+b") as log:
        process = subprocess.Popen([str(python), "-I", "-c", BOOTSTRAP, str(bundle / "app.py")], cwd=working,
                                   env=child_env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                require(process.poll() is None, f"Gateway exited during {label} startup")
                try:
                    status, payload = request(port, "GET", "/health")
                    require(status == 200 and payload == {"protocolVersion": 1}, "Invalid health response")
                    break
                except (OSError, http.client.HTTPException):
                    require(time.monotonic() < deadline, f"Gateway startup timed out: {label}")
                    time.sleep(0.1)
            for authorization, expected, code in [(None, 401, "UNAUTHORIZED"),
                                                    ("wrong-token", 401, "UNAUTHORIZED"),
                                                    (token, 404, "CAPABILITY_UNAVAILABLE")]:
                status, payload = request(port, "POST", "/v1/not-supported", b'{"params":{}}', authorization)
                require(status == expected and payload.get("error", {}).get("code") == code,
                        f"Unexpected {label} boundary response: {status} {payload}")
                require(token not in json.dumps(payload), "Gateway leaked its token")
            require(process.poll() is None, "Gateway exited after smoke requests")
        except Exception as failure:
            log.seek(0)
            detail = log.read().decode("utf-8", errors="replace").replace(token, "[redacted]")
            raise RuntimeError(f"{failure}\nGateway output:\n{detail}") from failure
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    print(f"Offline gateway smoke passed: {label}")


def main():
    parser = argparse.ArgumentParser(description="Test the Windows QQMusic bundle without contacting QQ upstream.")
    parser.add_argument("--bundle", type=Path, required=True)
    options = parser.parse_args()
    require(os.name == "nt", "Run this smoke test on Windows; it executes the bundled python.exe")
    bundle = options.bundle.resolve(strict=True)
    validate_layout(bundle)
    python = bundle / "python/python.exe"
    with tempfile.TemporaryDirectory(prefix="qqmusic-bundle-smoke-") as directory:
        working = Path(directory)
        poison = working / "poison"
        poison.mkdir()
        for name in ["app", "gateway", "qqmusic_api", "fastapi", "uvicorn", "sitecustomize", "usercustomize"]:
            (poison / f"{name}.py").write_text('raise RuntimeError("Imported host pollution")\n', encoding="utf-8")
        base_env = {key: value for key, value in os.environ.items() if not key.upper().startswith(("PYTHON", "QQMUSIC_"))}
        # An absolute executable plus an empty PATH proves runtime startup needs no host Python or tools.
        base_env["PATH"] = ""
        polluted_env = base_env | {"PYTHONHOME": str(poison), "PYTHONPATH": str(poison),
                                   "PYTHONUSERBASE": str(poison), "PYTHONSTARTUP": str(poison / "app.py")}
        for label, env in [("clean", base_env), ("polluted-python-environment", polluted_env)]:
            probe = subprocess.run([str(python), "-I", "-c", PROBE, str(bundle)], cwd=poison, env=env,
                                   stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            require(probe.returncode == 0, f"Bundled import/isolation check failed ({label}):\n{probe.stdout}\n{probe.stderr}")
            print(f"Bundled import/isolation check passed ({label}): {probe.stdout.strip()}")
            smoke(bundle, python, env, poison, label)


if __name__ == "__main__":
    main()

import argparse
from contextlib import closing
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time


BOOTSTRAP = r'''
import ipaddress
import runpy
import sys

def loopback(address):
    try:
        return ipaddress.ip_address(address).is_loopback
    except ValueError:
        return False

def blocked(message):
    print("Offline smoke test blocked: " + message, file=sys.stderr, flush=True)
    raise RuntimeError(message)

def offline(event, args):
    if event == "socket.getaddrinfo" and args[0] is not None and not loopback(args[0]):
        blocked("DNS")
    if event in {"socket.connect", "socket.bind", "socket.sendto"}:
        address = args[1]
        if not isinstance(address, tuple) or not loopback(address[0]):
            blocked("non-loopback network I/O")

sys.addaudithook(offline)
runpy.run_path(sys.argv[1], run_name="__main__")
'''


EXPECTED_ROUTES = {
    "/search/search_by_type", "/song/{value}/detail", "/song/get_song_urls",
    "/song/{value}/lyric", "/user/{uin}/created_songlists", "/songlist/{songlist_id}/detail",
    "/album/{value}/detail", "/recommend/get_guess_recommend",
}


def request(port, path):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        return response.status, json.loads(response.read())
    finally:
        connection.close()


def verify(port=8000):
    status, health = request(port, "/")
    assert status == 200 and health["code"] == 0 and health["msg"] == "ok", health
    assert isinstance(health["time"], str) and health["time"], health
    status, schema = request(port, "/openapi.json")
    assert status == 200 and schema["info"]["title"] == "QQMusic API", schema
    assert EXPECTED_ROUTES <= schema["paths"].keys(), EXPECTED_ROUTES - schema["paths"].keys()
    assert not any(path.startswith("/v1/") for path in schema["paths"])
    assert request(port, "/health")[0] == 404
    assert request(port, "/v1/search")[0] == 404


def smoke(source, python, env, working, label):
    for name in [".env", "web/accounts.toml", "web/config.toml"]:
        assert not (source / name).exists(), f"Unexpected active upstream configuration: {name}"
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    child_env = env | {
        "QQMUSIC_SERVER_HOST": "127.0.0.1", "QQMUSIC_SERVER_PORT": str(port),
        "QQMUSIC_SERVER_WORKERS": "1", "QQMUSIC_CREDENTIAL_ENABLED": "false",
        "QQMUSIC_CLIENT_DEVICE_PATH": str(working / f"device-{label}.json"),
        "QQMUSIC_CREDENTIAL_STORE_PATH": str(working / f"credentials-{label}.sqlite3"),
        "QQMUSIC_LOGGING_LEVEL": "WARNING", "PYTHONDONTWRITEBYTECODE": "1",
    }
    with tempfile.TemporaryFile(mode="w+b") as log:
        process = subprocess.Popen(
            [str(python), "-I", "-B", "-c", BOOTSTRAP, str(source / "web/run.py")],
            cwd=source, env=child_env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 45
            while True:
                assert process.poll() is None, f"Native Web exited during {label} startup"
                try:
                    verify(port)
                    break
                except (OSError, http.client.HTTPException):
                    assert time.monotonic() < deadline, f"Native Web startup timed out: {label}"
                    time.sleep(0.1)
            assert process.poll() is None, "Native Web exited after smoke requests"
            import sqlite3
            with closing(sqlite3.connect(child_env["QQMUSIC_CREDENTIAL_STORE_PATH"])) as database:
                assert database.execute("SELECT count(*) FROM credentials").fetchone() == (0,)
            log.seek(0)
            assert b"Offline smoke test blocked" not in log.read(), "Startup attempted external network access"
        except Exception as failure:
            log.seek(0)
            raise RuntimeError(f"{failure}\nNative Web output:\n{log.read().decode('utf-8', errors='replace')}") from failure
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    print(f"Offline native Web smoke passed: {label}")


def main():
    parser = argparse.ArgumentParser(description="Verify the pinned native QQMusic Web service offline.")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--port", type=int, default=8000)
    options = parser.parse_args()
    if options.source is None:
        deadline = time.monotonic() + 45
        while True:
            try:
                verify(options.port)
                return
            except (OSError, http.client.HTTPException):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)
    source = options.source.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="qqmusic-native-smoke-") as directory:
        working = Path(directory)
        poison = working / "poison"
        poison.mkdir()
        for name in ["web", "qqmusic_api", "fastapi", "uvicorn", "sitecustomize", "usercustomize"]:
            (poison / f"{name}.py").write_text('raise RuntimeError("Imported host pollution")\n', encoding="utf-8")
        env = {key: value for key, value in os.environ.items() if not key.upper().startswith(("PYTHON", "QQMUSIC_"))}
        env["PATH"] = ""
        for label, extra in [("clean", {}), ("polluted-python-environment", {
            "PYTHONHOME": str(poison), "PYTHONPATH": str(poison), "PYTHONUSERBASE": str(poison),
            "PYTHONSTARTUP": str(poison / "web.py"),
        })]:
            smoke(source, Path(sys.executable).absolute(), env | extra, working, label)


if __name__ == "__main__":
    main()

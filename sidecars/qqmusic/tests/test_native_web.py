import os
from pathlib import Path
import sys

import pytest


SOURCE = os.environ.get("QQMUSIC_UPSTREAM_SOURCE")
if not SOURCE:
    pytest.skip("Set QQMUSIC_UPSTREAM_SOURCE to the clean pinned upstream export", allow_module_level=True)
SOURCE = Path(SOURCE).resolve(strict=True)
sys.path.insert(0, str(SOURCE))

from fastapi.testclient import TestClient
from qqmusic_api.core.engine import RequestEngine
from qqmusic_api.models.search import HotkeyResponse
from web.src import app as native
from web.src.core.credential_store import load_account_configs


class OfflineTransport:
    def __init__(self):
        self.closed = False

    async def request(self, request):
        raise AssertionError("Fixture test attempted network access")

    async def close(self):
        self.closed = True


class FixtureExecutor:
    def __init__(self):
        self.calls = []

    async def execute(self, call):
        self.calls.append(call)
        assert call.request.module == "music.musicsearch.HotkeyService"
        assert call.request.method == "GetHotkeyForQQMusicMobile"
        return HotkeyResponse(hotkey_time="fixture", vec_hotkey=[{"query": "offline"}])


@pytest.fixture
def application(monkeypatch, tmp_path):
    assert not (SOURCE / "web/accounts.toml").exists()
    assert not (SOURCE / "web/config.toml").exists()
    assert not (SOURCE / ".env").exists()
    transport = OfflineTransport()
    executor = FixtureExecutor()
    engine = RequestEngine(cgi_executor=executor, http_executor=executor, transport=transport)
    monkeypatch.setattr(native.RequestEngine, "create", lambda **kwargs: engine)
    monkeypatch.setattr(native.settings.credential, "enabled", False)
    monkeypatch.setattr(native.settings.credential.store, "path", str(tmp_path / "credentials.sqlite3"))
    with TestClient(native.create_app(), client=("127.0.0.1", 50000)) as client:
        yield client, engine, executor
    assert engine._close_state == "closed"
    assert transport.closed


def test_native_readiness_schema_and_empty_pool(application):
    client, engine, executor = application
    health = client.get("/")
    assert health.status_code == 200
    assert health.json()["code"] == 0 and health.json()["msg"] == "ok"
    assert health.json()["time"]
    schema = client.get("/openapi.json").json()
    assert schema["info"]["title"] == "QQMusic API"
    assert "/search/search_by_type" in schema["paths"]
    assert "/song/{value}/detail" in schema["paths"]
    assert "/song/get_song_urls" in schema["paths"]
    assert not any(path.startswith("/v1/") for path in schema["paths"])
    assert client.get("/health").status_code == 404
    assert client.post("/v1/search", json={"params": {}}).status_code == 404
    assert client.app.state.services.require_engine is engine
    assert client.app.state.services.credential_pool.acquire() == ()
    assert executor.calls == []


def test_native_route_uses_real_request_engine_and_anonymous_scope(application):
    client, engine, executor = application
    response = client.get("/search/get_hotkey", headers={"Cookie": "musicid=123; musickey=fixture"})
    assert response.status_code == 200
    assert response.json()["code"] == 0
    assert response.json()["data"]["vec_hotkey"][0]["query"] == "offline"
    assert len(executor.calls) == 1
    assert not executor.calls[0].scope.credential.is_valid()
    assert client.get("/search/get_hotkey").json() == response.json()
    assert len(executor.calls) == 1


def test_existing_service_waits_for_startup(monkeypatch):
    import importlib.util
    spec = importlib.util.spec_from_file_location("native_smoke", Path(__file__).parents[1] / "smoke.py")
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    calls = []

    def delayed(port):
        calls.append(port)
        if len(calls) == 1:
            raise ConnectionRefusedError()

    monkeypatch.setattr(sys, "argv", ["smoke.py", "--port", "8123"])
    monkeypatch.setattr(smoke, "verify", delayed)
    monkeypatch.setattr(smoke.time, "sleep", lambda _: None)
    smoke.main()
    assert calls == [8123, 8123]


def test_upstream_account_example_is_not_an_active_account():
    accounts = load_account_configs(str(SOURCE / "web/accounts.example.toml"))
    assert accounts and not any(account.is_valid() for account in accounts)
    assert load_account_configs(str(SOURCE / "web/accounts.toml")) == []

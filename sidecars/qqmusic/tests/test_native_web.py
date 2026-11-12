from dataclasses import fields, replace
import os
import runpy
from pathlib import Path
import subprocess
import sys

import pytest


SOURCE = os.environ.get("QQMUSIC_UPSTREAM_SOURCE")
if not SOURCE:
    pytest.skip("Set QQMUSIC_UPSTREAM_SOURCE to the clean pinned upstream export", allow_module_level=True)
SOURCE = Path(SOURCE).resolve(strict=True)
sys.path.insert(0, str(SOURCE))

from fastapi.testclient import TestClient
from qqmusic_api.core.engine import RequestEngine
from qqmusic_api.core.exceptions import CgiApiException, CredentialExpiredError
from qqmusic_api.models.search import HotkeyResponse
from qqmusic_api.models.user import UserCreatedSonglistResponse, UserPlaylistSummary
from web.src import app as native
from web.src.core.credential_store import load_account_configs
from web.src.routes import ROUTES as upstream_routes
from web.src.routing.route_types import AuthPolicy

os.environ["QQMUSIC_UPSTREAM_SOURCE"] = str(SOURCE)
runpy.run_path(str(Path(__file__).parents[1] / "run_web.py"))


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
        self.error = None
        self.result = None

    async def execute(self, call):
        self.calls.append(call)
        if self.error is not None:
            raise self.error
        if self.result is not None:
            return self.result
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


def test_policy_only_changes_native_none_auth_and_public_cache():
    assert len(native.ROUTES) == len(upstream_routes)
    assert any(route.cache is not None for route in upstream_routes)
    for before, after in zip(upstream_routes, native.ROUTES, strict=True):
        if before.auth is AuthPolicy.NONE:
            assert after == replace(before, auth=AuthPolicy.OPTIONAL, cache=None)
            for field in fields(before):
                if field.name not in {"auth", "cache"}:
                    assert getattr(after, field.name) is getattr(before, field.name)
        else:
            assert after is before
    assert all(route.cache is None for route in native.ROUTES)


def test_native_route_uses_request_cookie_without_cross_account_cache(application):
    client, engine, executor = application
    for musicid in (123, 456, None, 123):
        headers = {"Cookie": f"musicid={musicid}; musickey=fixture-{musicid}"} if musicid else {}
        response = client.get("/search/get_hotkey", headers=headers)
        assert response.status_code == 200
        assert response.json()["code"] == 0
        assert response.json()["data"]["vec_hotkey"][0]["query"] == "offline"
        assert "etag" not in response.headers and "cache-control" not in response.headers
        credential = executor.calls[-1].scope.credential
        assert credential.musicid == (musicid or 0)
        assert credential.musickey == (f"fixture-{musicid}" if musicid else "")
        assert credential.is_valid() == bool(musicid)
    assert len(executor.calls) == 4


READ_ROUTES = [
    ("/search/search_by_type?keyword=fixture&search_type=0", "music.search.SearchCgiService", "DoSearchForQQMusicMobile"),
    ("/song/123/detail", "music.pf_song_detail_svr", "get_song_detail_yqq"),
    ("/song/123/lyric", "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo"),
    ("/album/123/detail", "music.musichallAlbum.AlbumInfoServer", "GetAlbumDetail"),
    ("/user/999/created_songlists", "music.musicasset.PlaylistBaseRead", "GetPlaylistByUin"),
    ("/songlist/123/detail", "music.srfDissInfo.DissInfo", "CgiGetDiss"),
]


@pytest.mark.parametrize("path,module,method", READ_ROUTES)
@pytest.mark.parametrize("musicid", [123, 456, None])
def test_native_read_routes_reach_real_engine_with_request_scope(application, path, module, method, musicid):
    client, engine, executor = application
    executor.error = CgiApiException("upstream permission denied", code=10001)
    headers = {"Cookie": (
        f"musicid={musicid}; musickey=fixture; openid=fixture-openid; access_token=fixture-access; "
        "refresh_token=fixture-refresh; expired_at=4102444800; unionid=fixture-union; refresh_key=fixture-key"
    )} if musicid else {}
    response = client.get(path, headers=headers)
    assert response.status_code == 400, response.text
    assert response.json() == {"code": -1, "msg": "upstream permission denied"}
    assert len(executor.calls) == 1
    call = executor.calls[0]
    assert call.request.module == module
    assert call.request.method == method
    assert call.scope.credential.musicid == (musicid or 0)
    assert call.scope.credential.is_valid() == bool(musicid)
    assert call.scope.credential.openid == ("fixture-openid" if musicid else "")
    assert call.scope.credential.access_token == ("fixture-access" if musicid else "")
    assert call.scope.credential.refresh_token == ("fixture-refresh" if musicid else "")
    assert call.scope.credential.expired_at == (4102444800 if musicid else 0)
    assert call.scope.credential.unionid == ("fixture-union" if musicid else "")
    assert call.scope.credential.refresh_key == ("fixture-key" if musicid else "")
    assert client.app.state.services.credential_pool.acquire() == ()


def test_native_user_result_is_not_filtered_by_local_visibility_policy(application):
    client, engine, executor = application
    playlist = UserPlaylistSummary(
        id=321, title="fixture upstream-visible result", create_time=0, update_time=0,
        uin="999", nick="fixture", bigpic_url="", album_pic_url="", avatar="",
        ident_icon="", layer_url="", invalid=False, dir_show=0, create_fav_cnt=0,
        play_cnt=0, comment_cnt=0, op_type=0, sort_weight=0,
    )
    executor.result = UserCreatedSonglistResponse(total=1, playlists=[playlist], deleted_ids=[], finished=True)
    response = client.get("/user/999/created_songlists", headers={"Cookie": "musicid=123; musickey=fixture"})
    assert response.status_code == 200
    assert response.json()["data"]["playlists"][0]["id"] == 321
    assert response.json()["data"]["playlists"][0]["dir_show"] == 0
    assert executor.calls[0].scope.credential.musicid == 123


def test_native_strict_auth_and_cookie_validation_are_preserved(application):
    client, engine, executor = application
    assert client.get("/user/999/fans").status_code == 401
    for cookie in ("musicid=123", "musicid=bad; musickey=fixture"):
        response = client.get("/search/get_hotkey", headers={"Cookie": cookie})
        assert response.status_code == 422
        assert response.json()["msg"] == "请求参数校验失败"
    assert executor.calls == []


def test_expired_caller_cookie_does_not_refresh_or_retry(application):
    client, engine, executor = application
    executor.error = CredentialExpiredError("fixture expired", code=1000)
    response = client.get("/search/get_hotkey", headers={"Cookie": "musicid=123; musickey=expired"})
    assert response.status_code == 401
    assert response.json() == {"code": -1, "msg": "fixture expired"}
    assert len(executor.calls) == 1
    assert client.app.state.services.credential_pool.acquire() == ()


@pytest.mark.parametrize("operation", [
    'socket.getaddrinfo("example.invalid", 443)',
    'socket.socket().connect(("192.0.2.1", 443))',
    'socket.socket(socket.AF_INET, socket.SOCK_DGRAM).sendto(b"x", ("192.0.2.1", 443))',
])
def test_offline_bootstrap_blocks_nonlocal_network(tmp_path, operation):
    smoke = runpy.run_path(str(Path(__file__).parents[1] / "smoke.py"))
    probe = tmp_path / "nonlocal.py"
    probe.write_text(f"import socket\n{operation}\n", encoding="utf-8")
    result = subprocess.run(
        [sys.executable, "-I", "-B", "-c", smoke["BOOTSTRAP"], str(probe)],
        capture_output=True, text=True, timeout=10,
    )
    assert result.returncode != 0
    assert "Offline smoke test blocked:" in result.stderr


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

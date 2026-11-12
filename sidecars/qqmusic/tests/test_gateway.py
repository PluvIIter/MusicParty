import asyncio
from dataclasses import dataclass, field
from types import SimpleNamespace

import httpx
import pytest
from qqmusic_api.models.request import Credential

from app import MAX_BODY, create_app
from gateway import Gateway, GatewayError, credential_snapshot, decode_user, map_media, track, user_ref


MID = "0027oMO61wWi55"
MEDIA = "002A6j2q1tdUUP"


def song(mid=MID):
    return {"mid": mid, "name": "Fixture song", "interval": 200, "type": 1,
            "singer": [{"name": "Artist"}], "album": {"mid": "0024bjiL2aocxT"},
            "file": {"media_mid": MEDIA, "size_128mp3": 3200000, "size_320mp3": 8000000,
                     "size_flac": 12000000, "size_try": 100000, "try_begin": 30000, "try_end": 60000}}


@dataclass
class Request:
    name: str
    args: tuple
    param: dict = field(default_factory=dict)
    credential: object = None
    response_model: object = object


class Module:
    def __init__(self, client, name):
        self.client, self.name = client, name

    def __getattr__(self, name):
        def build(*args, **kwargs):
            return Request(self.name + "." + name, args, kwargs, kwargs.get("credential"))
        return build


class FixtureClient:
    def __init__(self, responses, credential=None, **kwargs):
        self.responses, self.credential = responses, credential or Credential()
        self.calls = []
        self.closed = False
        for name in ("search", "song", "songlist", "user", "album", "lyric", "recommend"):
            setattr(self, name, Module(self, name))

    async def execute(self, request):
        self.calls.append(request)
        result = self.responses[request.name]
        return result(request) if callable(result) else result

    async def close(self):
        self.closed = True

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.close()


def public_playlist_detail(request):
    return {"dirinfo": {"id": request.args[0], "title": "Verified fixture", "songnum": 1, "picurl": "", "dir_show": 0}}


def gateway(responses):
    public = FixtureClient(responses)
    authenticated = []

    def factory(**kwargs):
        client = FixtureClient(responses, **kwargs)
        authenticated.append(client)
        return client

    return Gateway(public, factory), public, authenticated


@pytest.mark.asyncio
async def test_public_operations_use_empty_credentials_and_real_model_fields():
    g, public, authenticated = gateway({"search.search_by_type": {"body": {"item_song": [song()]}},
                                       "song.get_detail": {"track_info": song()}})
    result = await g.execute("search", {"query": "fixture"})
    assert result[0]["id"] == MID
    assert result[0]["duration"] == 200000
    assert await g.execute("track", {"id": MID}) == result[0]
    assert all(not request.credential.is_valid() for request in public.calls)
    assert not authenticated
    with pytest.raises(GatewayError, match="PUBLIC_CREDENTIAL_FORBIDDEN"):
        await g.execute("track", {"id": MID}, {"musicid": "123", "musickey": "secret"})


@pytest.mark.asyncio
async def test_user_reference_preserves_verified_uin_and_euin_never_docid_or_name():
    g, _, _ = gateway({"search.search_by_type": {"body": {"item_user": [
        {"uin": "1927552414", "encrypt_uin": "oKEA7i4kowv57n**", "title": "<em>Name</em>", "pic": ""},
        {"docid": "123", "title": "Nick", "pic": ""}]}}})
    users = await g.execute("users", {"query": "fixture"})
    assert len(users) == 1
    assert users[0]["name"] == "Name"
    assert decode_user(users[0]["id"]) == {"uin": "1927552414", "euin": "oKEA7i4kowv57n**"}
    with pytest.raises(GatewayError):
        decode_user("q1.eyJ1aW4iOiIxIiwibXVzaWNrZXkiOiJzZWNyZXQifQ")


@pytest.mark.asyncio
async def test_public_collection_uses_global_tid_and_checks_complete_list():
    entries = [{"tid": 123456, "dirId": 7, "dirName": "First", "songNum": 1, "picUrl": "", "invalid": False, "dirShow": 0},
               {"tid": 123457, "dirId": 8, "dirName": "Second", "songNum": 2, "picUrl": "", "invalid": False, "dirShow": 0}]
    g, public, _ = gateway({"user.get_created_songlist": {"total": 2, "bFinish": True, "v_playlist": entries},
                            "songlist.get_detail": public_playlist_detail})
    result = await g.execute("collections", {"userId": user_ref("123"), "category": "playlists", "limit": 1})
    assert result["items"][0]["id"] == "123456"
    assert result["nextOffset"] == 1
    assert public.calls[0].args == (123,)
    assert not public.calls[0].credential.is_valid()
    g.public.responses["user.get_created_songlist"]["bFinish"] = False
    with pytest.raises(GatewayError, match="COLLECTION_INCOMPLETE"):
        await g.execute("collections", {"userId": user_ref("123"), "category": "playlists"})
    with pytest.raises(GatewayError, match="USER_ID_UNAVAILABLE"):
        await g.execute("collections", {"userId": user_ref(euin="opaque**"), "category": "playlists"})


@pytest.mark.asyncio
async def test_playlist_cursor_counts_raw_invalid_entries_not_filtered_output():
    info = {"id": 123, "title": "List", "songnum": 3, "picurl": ""}
    g, public, _ = gateway({"songlist.get_detail": {"dirinfo": info, "total_song_num": 3,
                            "hasmore": 1, "songlist": [{"mid": "0"}, song()]}})
    result = await g.execute("tracks", {"type": "playlist", "id": "123", "offset": 0, "limit": 2})
    assert len(result["items"]) == 1
    assert result["nextOffset"] == 2
    assert public.calls[0].param["song_begin"] == 0
    g.public.responses["songlist.get_detail"]["songlist"] = []
    with pytest.raises(GatewayError, match="PAGINATION_STALLED"):
        await g.execute("tracks", {"type": "playlist", "id": "123"})


@pytest.mark.asyncio
async def test_album_pages_fully_or_fails_no_partial_success():
    album_mid = "0024bjiL2aocxT"
    def batch(request):
        return {"albumMid": album_mid, "totalNum": 2, "songList": [{"songInfo": song("abc123" if request.param["begin"] == 0 else "def456")} ]}
    g, _, _ = gateway({"album.get_detail": {"basicInfo": {"albumMid": album_mid, "albumName": "Album"}}, "album.get_song": batch})
    result = await g.execute("album", {"id": album_mid})
    assert result["trackCount"] == 2
    assert [item["id"] for item in result["songs"]] == ["abc123", "def456"]
    g.public.responses["album.get_song"] = {"albumMid": album_mid, "totalNum": 2, "songList": []}
    with pytest.raises(GatewayError, match="PAGINATION_STALLED"):
        await g.execute("album", {"id": album_mid})


def vkey(filename="M500" + MEDIA + ".mp3"):
    return {"expiration": 3600, "midurlinfo": [{"songmid": MID, "filename": filename,
            "purl": filename + "?vkey=fixture", "result": 0, "ekey": "", "vkey": "fixture"}]}


@pytest.mark.asyncio
async def test_media_looks_up_media_mid_anonymously_and_isolates_credential_snapshot():
    g, public, authenticated = gateway({"song.get_detail": {"track_info": song()}, "song.get_song_urls": vkey()})
    media = await g.execute("media", {"id": MID, "quality": "MP3_128", "consumer": "BROWSER"}, {"musicid": "123", "musickey": "private"})
    assert media["representationKey"].startswith("MP3_128:" + MEDIA)
    assert media["mimeType"] == "audio/mpeg"
    assert media["headers"] == {}
    assert media["expiresAt"].endswith("Z")
    assert not public.calls[0].credential.is_valid()
    request = authenticated[0].calls[0]
    assert request.args[0][0].media_mid == MEDIA
    assert request.credential.musicid == 123
    assert authenticated[0].closed
    await g.execute("media", {"id": MID, "quality": "MP3_128", "consumer": "SERVER"})
    assert not authenticated[1].credential.is_valid()


@pytest.mark.parametrize("change", ["error", "empty", "encrypted", "preview", "wrong-id", "unknown-expiry", "short-file", "url"])
def test_media_rejects_unproven_encrypted_preview_or_wrong_resource(change):
    raw = vkey()
    item = raw["midurlinfo"][0]
    if change == "error": item["result"] = 104003
    if change == "empty": item["purl"] = ""
    if change == "encrypted": item["ekey"] = "secret"
    if change == "preview": item["filename"] = "RS02preview.mp3"
    if change == "wrong-id": item["songmid"] = "another"
    if change == "unknown-expiry": raw["expiration"] = 0
    if change == "url": item["purl"] = "https://127.0.0.1/file"
    size = 50000 if change == "short-file" else 3200000
    with pytest.raises(GatewayError):
        map_media(MID, MEDIA, "MP3_128", "M500" + MEDIA + ".mp3", size, 200000, song()["file"], raw)


@pytest.mark.asyncio
async def test_native_fm_is_radio_id99_via_upstream_method_and_requires_explicit_account():
    g, public, authenticated = gateway({"recommend.get_guess_recommend": {"tracks": [song()]}})
    with pytest.raises(GatewayError, match="SOURCE_AUTH_REQUIRED"):
        await g.execute("fm", {})
    result = await g.execute("fm", {}, {"musicid": "123", "musickey": "private", "loginType": 2})
    assert result[0]["id"] == MID
    assert authenticated[0].calls[0].name == "recommend.get_guess_recommend"
    assert not public.calls
    with pytest.raises(GatewayError, match="CAPABILITY_UNAVAILABLE"):
        await g.execute("dj", {})


@pytest.mark.parametrize("value", [{"musicid": "1", "musickey": "x", "refresh_token": "secret"},
                                     {"musicid": "1", "musickey": "x", "loginType": True},
                                     {"musicid": "1", "musickey": "x\r\nCookie: bad"},
                                     {"musicid": True, "musickey": "x"}])
def test_credential_rejects_unknown_aliases_and_controls(value):
    with pytest.raises(GatewayError):
        credential_snapshot(value)


class FixtureGateway:
    def __init__(self): self.calls = []
    async def execute(self, operation, params, credential):
        self.calls.append((operation, params, credential))
        if params.get("fail"): raise RuntimeError("musickey=private-secret https://upstream/?vkey=private")
        return []
    async def close(self): pass


@pytest.mark.asyncio
async def test_http_boundary_auth_body_method_and_sanitized_errors():
    stub = FixtureGateway()
    app = create_app(stub, token="test-private-token-123")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://internal") as client:
        assert (await client.get("/health")).json() == {"protocolVersion": 1}
        assert (await client.post("/v1/search", json={"params": {}})).status_code == 401
        headers = {"Authorization": "Bearer test-private-token-123"}
        assert (await client.get("/v1/search", headers=headers)).status_code == 405
        assert (await client.post("/v1/search?token=oops", headers=headers, json={"params": {}})).status_code == 400
        assert (await client.post("/v1/search", headers=headers, content="x" * (MAX_BODY + 1))).status_code in (413, 415)
        response = await client.post("/v1/search", headers=headers, json={"params": {"fail": True}})
        assert response.status_code == 502
        assert "private" not in response.text
        response = await client.post("/v1/search", headers={**headers, "Content-Type": "application/json"}, content='{"params":{},"params":{}}')
        assert response.status_code == 400
        assert len(stub.calls) == 1


@pytest.mark.asyncio
async def test_http_timeout_returns_controlled_error():
    class Slow(FixtureGateway):
        async def execute(self, *args): await asyncio.sleep(1)
    app = create_app(Slow(), token="test-private-token-123", timeout=0.01)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://internal") as client:
        response = await client.post("/v1/search", headers={"Authorization": "Bearer test-private-token-123"}, json={"params": {}})
        assert response.status_code == 504

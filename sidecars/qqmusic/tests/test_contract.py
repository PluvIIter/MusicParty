import asyncio
from dataclasses import replace

import httpx
import pytest

from app import MAX_BODY, create_app
from gateway import GatewayError, identifier, user_ref
from test_gateway import MID, MEDIA, FixtureGateway, gateway, song, vkey


@pytest.mark.asyncio
async def test_private_playlist_flag_is_error_even_with_zero_outer_code():
    g, _, _ = gateway({"songlist.get_detail": {"code": 0, "dirinfo": {"id": 123, "title": "Private", "dir_show": 2},
                                              "total_song_num": 95, "hasmore": 1, "songlist": []}})
    with pytest.raises(GatewayError, match="COLLECTION_NOT_PUBLIC") as failure:
        await g.execute("tracks", {"type": "playlist", "id": "123"})
    assert failure.value.status == 403


@pytest.mark.asyncio
async def test_media_fallback_is_distinct_ordinary_representation_same_full_timeline():
    def authorized(request):
        kind = request.args[0][0].file_type
        return vkey(kind.s + MEDIA + kind.e)
    g, _, _ = gateway({"song.get_detail": {"track_info": song()}, "song.get_song_urls": authorized})
    primary = await g.execute("media", {"id": MID, "quality": "FLAC", "consumer": "SERVER"})
    fallback = await g.execute("media-fallback", {"id": MID, "quality": "FLAC", "consumer": "BROWSER"})
    assert primary["extension"] == ".flac"
    assert fallback["extension"] == ".mp3"
    assert primary["representationKey"] != fallback["representationKey"]
    assert primary["timelineKey"] == fallback["timelineKey"]


@pytest.mark.asyncio
async def test_concurrent_account_requests_never_replace_anonymous_public_identity():
    g, public, authenticated = gateway({"song.get_detail": {"track_info": song()}, "song.get_song_urls": vkey()})
    params = {"id": MID, "quality": "MP3_128", "consumer": "SERVER"}
    await asyncio.gather(g.execute("media", params, {"musicid": "111", "musickey": "first"}),
                         g.execute("media", params, {"musicid": "222", "musickey": "second"}),
                         g.execute("track", {"id": MID}))
    assert {client.credential.musicid for client in authenticated} == {111, 222}
    assert all(client.closed for client in authenticated)
    assert all(not request.credential.is_valid() for request in public.calls)


@pytest.mark.parametrize("mid", ["123456", "https://host/song", "abc\r\nsecret", "a" * 129])
def test_track_identifiers_cannot_be_numeric_songids_urls_or_controls(mid):
    with pytest.raises(GatewayError):
        identifier(mid)


@pytest.mark.asyncio
async def test_http_rejects_oversized_json_and_unexpected_body_fields_before_dispatch():
    stub = FixtureGateway()
    app = create_app(stub, token="test-private-token-123")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://internal") as client:
        headers = {"Authorization": "Bearer test-private-token-123", "Content-Type": "application/json"}
        response = await client.post("/v1/search", headers=headers, content='{"params":{"query":"' + 'a' * MAX_BODY + '"}}')
        assert response.status_code == 413
        response = await client.post("/v1/search", headers=headers, json={"params": {}, "account": "secret"})
        assert response.status_code == 400
        response = await client.post("/v1/search", headers=headers, content='{"params":{"query":NaN}}')
        assert response.status_code == 400
    assert not stub.calls


@pytest.mark.asyncio
async def test_invalid_or_partial_album_fails_instead_of_successful_partial_tracklist():
    album_mid = "albumABC"
    g, _, _ = gateway({"album.get_detail": {"basicInfo": {"albumMid": album_mid, "albumName": "Fixture"}},
                        "album.get_song": {"albumMid": album_mid, "totalNum": 2, "songList": [{"songInfo": {"mid": "123"}}]}})
    with pytest.raises(GatewayError):
        await g.execute("album", {"id": album_mid})

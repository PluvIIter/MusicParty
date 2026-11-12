import hashlib

import httpx
import pytest
from qqmusic_api.core.exceptions import TimeoutNetworkError

from app import create_app
from gateway import GatewayError, map_media, user_ref
from test_gateway import MID, MEDIA, FixtureGateway, gateway, song, vkey, public_playlist_detail


@pytest.mark.asyncio
async def test_created_collections_filter_private_entries_without_changing_raw_cursor():
    entries = [
        {"tid": 123, "dirName": "Visible fixture zero", "songNum": 1, "invalid": False, "dirShow": 0},
        {"tid": 124, "dirName": "Private fixture secret", "songNum": 2, "invalid": False, "dirShow": 2},
        {"tid": 125, "dirName": "Visible fixture one", "songNum": 3, "invalid": False, "dirShow": 1},
        {"tid": 126, "dirName": "Invalid fixture", "songNum": 4, "invalid": True, "dirShow": 0},
    ]
    g, _, _ = gateway({"user.get_created_songlist": {"total": 4, "bFinish": True, "v_playlist": entries}, "songlist.get_detail": public_playlist_detail})
    params = {"userId": user_ref("123"), "category": "playlists", "offset": 0, "limit": 2}
    first = await g.execute("collections", params)
    assert [item["id"] for item in first["items"]] == ["123"]
    assert "Private fixture secret" not in str(first)
    assert first["nextOffset"] == 2 and first["total"] == 4
    second = await g.execute("collections", {**params, "offset": first["nextOffset"]})
    assert [item["id"] for item in second["items"]] == ["125"]
    assert second["nextOffset"] is None and second["total"] == 4


@pytest.mark.asyncio
async def test_all_private_page_still_advances_raw_cursor_to_later_visible_entry():
    entries = [
        {"tid": 123, "dirName": "Private fixture", "songNum": 1, "invalid": False, "dirShow": 2},
        {"tid": 124, "dirName": "Visible fixture", "songNum": 1, "invalid": False, "dirShow": 0},
    ]
    g, _, _ = gateway({"user.get_created_songlist": {"total": 2, "bFinish": True, "v_playlist": entries}, "songlist.get_detail": public_playlist_detail})
    params = {"userId": user_ref("123"), "category": "playlists", "offset": 0, "limit": 1}
    result = await g.execute("collections", params)
    assert result == {"items": [], "nextOffset": 1, "total": 2}
    next_page = await g.execute("collections", {**params, "offset": result["nextOffset"]})
    assert [item["id"] for item in next_page["items"]] == ["124"]
    assert next_page["nextOffset"] is None


@pytest.mark.asyncio
@pytest.mark.parametrize("marker", [None, -1, 3, "0", "1", "2", True, False, {}, []])
async def test_unknown_missing_or_noninteger_privacy_marker_is_not_exposed(marker):
    entry = {"tid": 123, "dirName": "Unproven fixture", "songNum": 1, "invalid": False}
    if marker is not None:
        entry["dirShow"] = marker
    g, _, _ = gateway({"user.get_created_songlist": {"total": 1, "bFinish": True, "v_playlist": [entry]}})
    result = await g.execute("collections", {"userId": user_ref("123"), "category": "playlists"})
    assert result == {"items": [], "nextOffset": None, "total": 1}


@pytest.mark.asyncio
async def test_summary_visible_but_detail_private_is_filtered_and_detail_metadata_wins():
    entries = [{"tid": 123, "dirName": "Leaked private name", "songNum": 1, "invalid": False, "dirShow": 1},
               {"tid": 124, "dirName": "Stale summary name", "songNum": 1, "invalid": False, "dirShow": 0}]

    def detail(request):
        if request.args[0] == 123:
            return {"dirinfo": {"id": 123, "title": "Private detail", "dir_show": 2}}
        return {"dirinfo": {"id": 124, "title": "Verified public detail", "songnum": 7, "picurl": "", "dir_show": 0}}

    g, public, _ = gateway({"user.get_created_songlist": {"total": 2, "bFinish": True, "v_playlist": entries},
                            "songlist.get_detail": detail})
    result = await g.execute("collections", {"userId": user_ref("123"), "category": "playlists"})
    assert [item["id"] for item in result["items"]] == ["124"]
    assert result["items"][0]["name"] == "Verified public detail"
    assert result["items"][0]["trackCount"] == 7
    assert "Leaked" not in str(result) and "Stale" not in str(result)
    assert result["nextOffset"] is None and result["total"] == 2
    assert all(not call.credential.is_valid() for call in public.calls)


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["wrong-id", "upstream-error", "network-error"])
async def test_detail_failures_other_than_explicit_private_do_not_become_empty_success(failure):
    entries = [{"tid": 123, "dirName": "Summary", "songNum": 1, "invalid": False, "dirShow": 0}]

    def detail(request):
        if failure == "network-error":
            raise TimeoutNetworkError("private upstream diagnostic")
        if failure == "upstream-error":
            return {"code": 9}
        return {"dirinfo": {"id": 999, "title": "Wrong resource", "songnum": 1, "picurl": "", "dir_show": 0}}

    g, _, _ = gateway({"user.get_created_songlist": {"total": 1, "bFinish": True, "v_playlist": entries},
                       "songlist.get_detail": detail})
    with pytest.raises((GatewayError, TimeoutNetworkError)):
        await g.execute("collections", {"userId": user_ref("123"), "category": "playlists"})


@pytest.mark.asyncio
async def test_public_detail_verification_has_at_most_four_inflight_requests():
    import asyncio

    entries = [{"tid": 123 + index, "dirName": "Summary", "songNum": 1, "invalid": False, "dirShow": 0}
               for index in range(12)]
    g, public, _ = gateway({"user.get_created_songlist": {"total": 12, "bFinish": True, "v_playlist": entries}})
    original = public.execute
    active, peak = 0, 0

    async def execute(request):
        nonlocal active, peak
        if request.name != "songlist.get_detail":
            return await original(request)
        active += 1
        peak = max(peak, active)
        try:
            await asyncio.sleep(0.01)
            public.calls.append(request)
            return public_playlist_detail(request)
        finally:
            active -= 1

    public.execute = execute
    result = await g.execute("collections", {"userId": user_ref("123"), "category": "playlists", "limit": 12})
    assert len(result["items"]) == 12
    assert 1 < peak <= 4


@pytest.mark.asyncio
async def test_upstream_timeout_exception_is_504_and_never_exposes_original_message():
    class UpstreamTimeout(FixtureGateway):
        async def execute(self, *args):
            raise TimeoutNetworkError("private-musickey=https://upstream/?vkey=secret")

    app = create_app(UpstreamTimeout(), token="test-private-token-123")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://internal") as client:
        result = await client.post("/v1/search", headers={"Authorization": "Bearer test-private-token-123"}, json={"params": {}})
    assert result.status_code == 504
    assert result.json() == {"error": {"code": "UPSTREAM_TIMEOUT", "message": "UPSTREAM_TIMEOUT"}}
    assert "private" not in result.text and "vkey" not in result.text


def test_same_song_and_duration_but_different_media_file_changes_timeline():
    filename = "M500" + MEDIA + ".mp3"
    original = map_media(MID, MEDIA, "MP3_128", filename, 3200000, 200000, song()["file"], vkey(filename))
    changed_mid = "replacementMediaABC"
    changed_filename = "M500" + changed_mid + ".mp3"
    changed = map_media(MID, changed_mid, "MP3_128", changed_filename, 3200000, 200000, song()["file"], vkey(changed_filename))
    assert original["mediaMid"] == MEDIA
    assert changed["mediaMid"] == changed_mid
    assert original["id"] == changed["id"] == MID
    assert original["timelineKey"] != changed["timelineKey"]
    assert original["timelineKey"] == hashlib.sha256(f"{MID}:{MEDIA}:200000:full".encode("utf-8")).hexdigest()
    assert original["representationKey"] == f"MP3_128:{MEDIA}:3200000"


@pytest.mark.asyncio
async def test_cross_quality_same_media_preserves_timeline_and_explicit_media_mid():
    def authorize(request):
        item = request.args[0][0]
        return vkey(item.file_type.s + item.media_mid + item.file_type.e)

    g, _, _ = gateway({"song.get_detail": {"track_info": song()}, "song.get_song_urls": authorize})
    results = [await g.execute("media", {"id": MID, "quality": quality, "consumer": "SERVER"})
               for quality in ("MP3_128", "MP3_320", "FLAC")]
    assert {item["mediaMid"] for item in results} == {MEDIA}
    assert len({item["timelineKey"] for item in results}) == 1
    assert len({item["representationKey"] for item in results}) == 3


@pytest.mark.asyncio
async def test_numeric_upstream_media_mid_is_not_misinterpreted_as_songid():
    item = song()
    item["file"]["media_mid"] = "123456"
    g, _, _ = gateway({"song.get_detail": {"track_info": item}, "song.get_song_urls": vkey("M500123456.mp3")})
    result = await g.execute("media", {"id": MID, "quality": "MP3_128", "consumer": "BROWSER"})
    assert result["mediaMid"] == "123456"
    assert result["id"] == MID

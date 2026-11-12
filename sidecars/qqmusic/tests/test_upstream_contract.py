from dataclasses import replace

from qqmusic_api import Client
from qqmusic_api.models.request import Credential
from qqmusic_api.modules.search import SearchType
from qqmusic_api.modules.song import SongFileInfo, SongFileType


def test_pinned_library_builds_actual_native_fm_and_ordinary_media_descriptors_without_network():
    client = Client(max_concurrency=1)
    credential = Credential(musicid=123, str_musicid="123", musickey="fixture")
    radio = client.recommend.get_guess_recommend(credential=credential)
    assert radio.module == "music.radioProxy.MbTrackRadioSvr"
    assert radio.method == "get_radio_track"
    assert radio.param["id"] == 99
    request = client.song.get_song_urls([SongFileInfo("songABC", SongFileType.MP3_128, 1, "mediaXYZ")], credential=credential)
    assert request.module == "music.vkey.GetVkey"
    assert request.param["filename"] == ["M500mediaXYZ.mp3"]
    assert request.param["songmid"] == ["songABC"]
    assert request.param["uin"] == "123"


def test_pinned_public_descriptors_allow_explicit_empty_snapshot_without_placeholder():
    client = Client(max_concurrency=1)
    empty = Credential()
    public = client.user.get_created_songlist(123, credential=empty)
    assert public.credential is empty
    assert public.param == {"uin": "123"}
    search = client.search.search_by_type("fixture", SearchType.USER, num=1, highlight=False)
    anonymous = replace(search, response_model=None, credential=empty)
    assert anonymous.credential is empty
    assert anonymous.param["search_type"] == 8
    assert anonymous.response_model is None

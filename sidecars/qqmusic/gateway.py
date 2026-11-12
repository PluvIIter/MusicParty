import asyncio
import base64
import hashlib
import html
import ipaddress
import json
import re
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlsplit, urlunsplit

from qqmusic_api import Client
from qqmusic_api.models.request import Credential
from qqmusic_api.modules.search import SearchType
from qqmusic_api.modules.song import SongFileInfo, SongFileType


class GatewayError(Exception):
    def __init__(self, code, status=502):
        self.code = code
        self.status = status
        super().__init__(code)


def text(value, maximum=512):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum or any(ord(c) < 32 for c in value):
        raise GatewayError("INVALID_INPUT", 400)
    return value


def identifier(value):
    value = text(value, 128)
    if not re.fullmatch(r"[A-Za-z0-9]+", value) or value.isdecimal():
        raise GatewayError("INVALID_INPUT", 400)
    return value


def number(value, maximum=10**20, allow_zero=False):
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,20}", value):
        value = int(value)
    if type(value) is not int or value < (0 if allow_zero else 1) or value > maximum:
        raise GatewayError("INVALID_INPUT", 400)
    return value


def page(params):
    return number(params.get("offset", 0), 100000, True), number(params.get("limit", 20), 100)


def clean_name(value):
    return html.unescape(re.sub(r"<[^>]*>", "", text(value, 4096)))[:512]


def safe_url(value, media=False):
    if not isinstance(value, str) or not value:
        if not media:
            return ""
        raise GatewayError("MEDIA_UNAVAILABLE")
    parsed = urlsplit(value)
    host = parsed.hostname or ""
    try:
        ipaddress.ip_address(host)
        raise GatewayError("MEDIA_UNAVAILABLE")
    except ValueError:
        pass
    if parsed.scheme not in {"http", "https"} or parsed.username or parsed.password or parsed.fragment or parsed.port not in (None, 80, 443):
        raise GatewayError("MEDIA_UNAVAILABLE" if media else "UPSTREAM_INVALID")
    allowed = ("qq.com", "qqmusic.qq.com", "gtimg.cn", "qqmusic.tc.qq.com")
    if not any(host == suffix or host.endswith("." + suffix) for suffix in allowed) or any(c in value for c in "\r\n\\"):
        raise GatewayError("MEDIA_UNAVAILABLE" if media else "UPSTREAM_INVALID")
    return urlunsplit(("https", parsed.netloc, parsed.path, parsed.query, ""))


def credential_snapshot(value, required=False):
    if value is None or value == {}:
        if required:
            raise GatewayError("SOURCE_AUTH_REQUIRED", 401)
        return Credential()
    if not isinstance(value, dict) or set(value) - {"musicid", "musickey", "loginType", "encrypt_uin"}:
        raise GatewayError("INVALID_CREDENTIAL", 400)
    musicid = number(value.get("musicid"), 2**63 - 1)
    key = text(value.get("musickey"), 4096)
    login = value.get("loginType")
    if login is not None and (type(login) is not int or login not in (0, 1, 2)):
        raise GatewayError("INVALID_CREDENTIAL", 400)
    data = {"musicid": musicid, "str_musicid": str(musicid), "musickey": key}
    if login is not None:
        data["loginType"] = login
    if "encrypt_uin" in value:
        data["encrypt_uin"] = text(value["encrypt_uin"], 256)
    return Credential.model_validate(data)


def user_ref(uin=None, euin=None):
    data = {}
    if uin is not None:
        data["uin"] = str(number(uin, 2**63 - 1))
    if euin is not None:
        euin = text(euin, 256)
        if not re.fullmatch(r"[A-Za-z0-9*_=+/-]+", euin):
            raise GatewayError("INVALID_INPUT", 400)
        data["euin"] = euin
    if not data:
        raise GatewayError("USER_ID_UNAVAILABLE")
    encoded = base64.urlsafe_b64encode(json.dumps(data, separators=(",", ":"), sort_keys=True).encode()).rstrip(b"=").decode()
    return "q1." + encoded


def decode_user(value):
    value = text(value, 512)
    if not value.startswith("q1.") or not re.fullmatch(r"q1\.[A-Za-z0-9_-]+", value):
        raise GatewayError("INVALID_INPUT", 400)
    try:
        data = json.loads(base64.urlsafe_b64decode(value[3:] + "=" * (-len(value[3:]) % 4)))
    except (ValueError, UnicodeError):
        raise GatewayError("INVALID_INPUT", 400) from None
    if not isinstance(data, dict) or set(data) - {"uin", "euin"} or user_ref(data.get("uin"), data.get("euin")) != value:
        raise GatewayError("INVALID_INPUT", 400)
    return data


def track(song):
    if not isinstance(song, dict):
        raise GatewayError("UPSTREAM_INVALID")
    mid = identifier(song.get("mid"))
    duration = number(song.get("interval"), 86400) * 1000
    album = song.get("album", {})
    cover_mid = album.get("mid", "") or album.get("pmid", "")
    cover = f"https://y.gtimg.cn/music/photo_new/T002R300x300M000{quote(cover_mid, safe='')}.jpg" if cover_mid else ""
    singers = song.get("singer")
    if not isinstance(singers, list):
        raise GatewayError("UPSTREAM_INVALID")
    return {"id": mid, "name": clean_name(song.get("name") or song.get("title")),
            "artists": [clean_name(s["name"]) for s in singers if isinstance(s, dict) and s.get("name")],
            "duration": duration, "platform": "qqmusic", "coverUrl": cover,
            "externalUrl": f"https://y.qq.com/n/ryqq/songDetail/{mid}"}


def tracks(raw):
    items = []
    for item in raw:
        try:
            items.append(track(item))
        except GatewayError:
            continue
    return items


def container(info):
    if not isinstance(info, dict):
        raise GatewayError("UPSTREAM_INVALID")
    pid = info.get("id") or info.get("tid") or info.get("dissid")
    return {"id": str(number(pid)), "name": clean_name(info.get("title") or info.get("dissname") or info.get("name") or info.get("dirName")),
            "coverImgUrl": safe_url(info.get("picurl") or info.get("cover") or info.get("picUrl") or ""),
            "trackCount": number(info.get("songnum", info.get("songNum", info.get("song_cnt", 0))), 100000, True), "platform": "qqmusic"}


def checked(raw):
    if not isinstance(raw, dict):
        raise GatewayError("UPSTREAM_INVALID")
    for field in ("code", "subcode", "ret", "retCode"):
        if field in raw and raw[field] != 0:
            raise GatewayError("UPSTREAM_REJECTED")
    if isinstance(raw.get("meta"), dict) and raw["meta"].get("ret", 0) != 0:
        raise GatewayError("UPSTREAM_REJECTED")
    return raw


class Gateway:
    public_operations = {"search", "track", "lyrics", "users", "collections", "container", "tracks", "album", "track-album"}
    operations = public_operations | {"media", "media-fallback", "fm"}

    def __init__(self, public_client=None, client_factory=Client, device_path=None):
        self.public = public_client or Client(device_path=device_path, max_concurrency=4)
        self.client_factory = client_factory
        self.device_path = device_path

    async def close(self):
        await self.public.close()

    async def raw(self, request):
        return checked(await self.public.execute(replace(request, response_model=None, credential=Credential())))

    async def detail(self, mid):
        result = await self.raw(self.public.song.get_detail(identifier(mid)))
        song = result.get("track_info")
        if not isinstance(song, dict) or song.get("mid") != mid:
            raise GatewayError("UPSTREAM_INVALID")
        return song

    async def execute(self, operation, params, credential=None):
        if operation not in self.operations:
            raise GatewayError("CAPABILITY_UNAVAILABLE", 404)
        if not isinstance(params, dict):
            raise GatewayError("INVALID_INPUT", 400)
        if operation in self.public_operations and credential not in (None, {}):
            raise GatewayError("PUBLIC_CREDENTIAL_FORBIDDEN", 400)
        allowed = {"search": {"query"}, "track": {"id"}, "lyrics": {"id"}, "users": {"query"},
                   "collections": {"userId", "category", "offset", "limit"}, "container": {"type", "id"},
                   "tracks": {"type", "id", "offset", "limit"}, "album": {"id"}, "track-album": {"id"},
                   "media": {"id", "quality", "consumer"}, "media-fallback": {"id", "quality", "consumer"}, "fm": set()}
        if set(params) - allowed[operation]:
            raise GatewayError("INVALID_INPUT", 400)
        if operation in {"search", "users"}:
            query = text(params.get("query"), 200)
            kind = SearchType.USER if operation == "users" else SearchType.SONG
            raw = await self.raw(self.public.search.search_by_type(query, search_type=kind, num=20, highlight=False))
            body = raw.get("body")
            field = "item_user" if operation == "users" else "item_song"
            if not isinstance(body, dict) or not isinstance(body.get(field), list) or len(body[field]) > 100:
                raise GatewayError("UPSTREAM_INVALID")
            if operation == "search":
                return tracks(body[field])
            items = []
            for user in body[field]:
                if not isinstance(user, dict):
                    continue
                uin, euin = user.get("uin"), user.get("encrypt_uin")
                if not uin and not euin:
                    continue
                try:
                    ref = user_ref(uin if uin else None, euin if euin else None)
                    items.append({"id": ref, "name": clean_name(user.get("title")), "avatarUrl": safe_url(user.get("pic", "")), "platform": "qqmusic"})
                except GatewayError:
                    continue
            if body.get("item_user") and not items:
                raise GatewayError("USER_ID_UNAVAILABLE")
            return items
        if operation == "track":
            return track(await self.detail(params.get("id")))
        if operation == "lyrics":
            request = self.public.lyric.get_lyric(identifier(params.get("id")))
            response = await self.public.execute(replace(request, credential=Credential()))
            if not isinstance(response.lyric, str) or len(response.lyric) > 512000:
                raise GatewayError("UPSTREAM_INVALID")
            return response.lyric
        if operation == "collections":
            if params.get("category") != "playlists":
                raise GatewayError("CAPABILITY_UNAVAILABLE", 400)
            target = decode_user(params.get("userId"))
            if not target.get("uin"):
                raise GatewayError("USER_ID_UNAVAILABLE", 422)
            offset, limit = page(params)
            raw = await self.raw(self.public.user.get_created_songlist(int(target["uin"]), credential=Credential()))
            entries = raw.get("v_playlist")
            total = number(raw.get("total"), 10000, True)
            if not isinstance(entries, list) or not raw.get("bFinish") or len(entries) != total or len(entries) > 2000:
                raise GatewayError("COLLECTION_INCOMPLETE")
            selected = entries[offset:offset + limit]
            items = [container(item) for item in selected if not item.get("invalid", False)]
            return {"items": items, "nextOffset": offset + len(selected) if offset + len(selected) < total else None, "total": total}
        if operation in {"container", "tracks"}:
            if params.get("type") != "playlist":
                raise GatewayError("CAPABILITY_UNAVAILABLE", 400)
            pid = number(params.get("id"))
            offset, limit = (0, 1) if operation == "container" else page(params)
            request = self.public.songlist.get_detail(pid, num=limit, page=1)
            request = replace(request, param={**request.param, "song_begin": offset})
            raw = await self.raw(request)
            raw_info = raw.get("dirinfo", {})
            if raw_info.get("dir_show") == 2:
                raise GatewayError("COLLECTION_NOT_PUBLIC", 403)
            info = container(raw_info)
            if info["id"] != str(pid):
                raise GatewayError("UPSTREAM_INVALID")
            if operation == "container":
                return info
            songs = raw.get("songlist")
            total = number(raw.get("total_song_num"), 100000, True)
            if not isinstance(songs, list) or len(songs) > limit or offset + len(songs) > total:
                raise GatewayError("UPSTREAM_INVALID")
            more = bool(raw.get("hasmore")) or offset + len(songs) < total
            if more and not songs:
                raise GatewayError("PAGINATION_STALLED")
            return {"items": tracks(songs), "nextOffset": offset + len(songs) if more else None, "total": total}
        if operation in {"album", "track-album"}:
            mid = identifier(params.get("id"))
            if operation == "track-album":
                mid = identifier((await self.detail(mid)).get("album", {}).get("mid"))
            return await self.album(mid)
        snapshot = credential_snapshot(credential, required=operation == "fm")
        async with self.client_factory(credential=snapshot, device_path=self.device_path, max_concurrency=2) as client:
            if operation == "fm":
                raw = checked(await client.execute(replace(client.recommend.get_guess_recommend(credential=snapshot), response_model=None)))
                songs = raw.get("tracks")
                if not isinstance(songs, list) or not songs or len(songs) > 100:
                    raise GatewayError("MEDIA_UNAVAILABLE")
                result = tracks(songs)
                if not result:
                    raise GatewayError("MEDIA_UNAVAILABLE")
                return result
            mid = identifier(params.get("id"))
            quality = params.get("quality", "MP3_128")
            if quality not in {"MP3_128", "MP3_320", "FLAC"} or params.get("consumer") not in {"BROWSER", "SERVER"}:
                raise GatewayError("INVALID_INPUT", 400)
            if operation == "media-fallback":
                quality = "MP3_128"
            song = await self.detail(mid)
            file_type = getattr(SongFileType, quality)
            files = song.get("file", {})
            media_mid = identifier(files.get("media_mid"))
            if not isinstance(files, dict) or any(type(files.get(field)) is not int or files[field] < 0 for field in ("size_try", "try_begin", "try_end")):
                raise GatewayError("MEDIA_UNAVAILABLE")
            expected_size = number(files.get({"MP3_128": "size_128mp3", "MP3_320": "size_320mp3", "FLAC": "size_flac"}[quality]), 1024**3)
            normalized = track(song)
            expected_filename = file_type.s + media_mid + file_type.e
            request = client.song.get_song_urls([SongFileInfo(mid, file_type, song.get("type", 1), media_mid)], file_type=file_type, credential=snapshot)
            raw = checked(await client.execute(replace(request, response_model=None)))
            return map_media(mid, media_mid, quality, expected_filename, expected_size, normalized["duration"], files, raw)

    async def album(self, mid):
        raw = await self.raw(self.public.album.get_detail(mid))
        info = raw.get("basicInfo", {})
        if (info.get("mid") or info.get("albumMid")) != mid:
            raise GatewayError("UPSTREAM_INVALID")
        songs, offset, total = [], 0, None
        for _ in range(20):
            request = self.public.album.get_song(mid, num=100, page=1)
            batch = await self.raw(replace(request, param={**request.param, "begin": offset}))
            if batch.get("albumMid") != mid:
                raise GatewayError("UPSTREAM_INVALID")
            count = number(batch.get("totalNum"), 2000, True)
            if total is not None and total != count:
                raise GatewayError("COLLECTION_CHANGED")
            total = count
            entries = batch.get("songList")
            if not isinstance(entries, list) or len(entries) > 100:
                raise GatewayError("UPSTREAM_INVALID")
            mapped = [track(item.get("songInfo")) for item in entries]
            songs.extend(mapped)
            offset += len(entries)
            if offset == total:
                break
            if not entries or offset > total:
                raise GatewayError("PAGINATION_STALLED")
        if offset != total:
            raise GatewayError("COLLECTION_INCOMPLETE")
        return {"id": mid, "name": clean_name(info.get("name") or info.get("title") or info.get("albumName")),
                "coverUrl": f"https://y.gtimg.cn/music/photo_new/T002R300x300M000{mid}.jpg", "trackCount": total, "songs": songs}


def map_media(mid, media_mid, quality, filename, size, duration, files, raw):
    entries = raw.get("midurlinfo")
    expiration = number(raw.get("expiration"), 86400)
    if expiration <= 30 or not isinstance(entries, list) or len(entries) != 1:
        raise GatewayError("MEDIA_UNAVAILABLE")
    item = entries[0]
    if not isinstance(item, dict) or item.get("songmid") != mid or type(item.get("result")) is not int or item["result"] != 0:
        raise GatewayError("MEDIA_UNAVAILABLE")
    path = item.get("purl")
    if item.get("filename") != filename or item.get("ekey") or not isinstance(item.get("vkey"), str) or not item["vkey"] or not isinstance(path, str) or not path or path.startswith(("/", "http")):
        raise GatewayError("MEDIA_UNAVAILABLE")
    parsed = urlsplit(path)
    if parsed.path != filename or parsed.scheme or parsed.netloc or parsed.fragment or any(c in path for c in "\r\n\\"):
        raise GatewayError("MEDIA_UNAVAILABLE")
    # Ordinary full-file authorization must not silently point at the preview representation.
    if files.get("size_try", 0) and size <= files["size_try"]:
        raise GatewayError("MEDIA_CONTENT_CHANGED")
    url = safe_url("https://isure.stream.qqmusic.qq.com/" + path, media=True)
    timeline = hashlib.sha256(f"{mid}:{duration}:full".encode()).hexdigest()
    return {"id": mid, "representationKey": f"{quality}:{media_mid}:{size}", "url": url,
            "mimeType": "audio/flac" if quality == "FLAC" else "audio/mpeg", "extension": ".flac" if quality == "FLAC" else ".mp3",
            "expiresAt": (datetime.now(timezone.utc) + timedelta(seconds=expiration - 30)).isoformat().replace("+00:00", "Z"),
            "browserSafe": True, "headers": {}, "timelineKey": timeline}

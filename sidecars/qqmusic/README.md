# QQ Music private gateway

Protocol 1 is an internal adapter service, not a public QQ Music API proxy. It uses `qqmusic-api-python` at commit `c046788dd1f11daacc74da487cbbf1bcd8ac6f33`. Java remains responsible for room authorization, source epochs, delivery mode, download security, and playback timeline enforcement.

## Run

Use Python 3.13 and a fresh virtual environment:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
export QQMUSIC_GATEWAY_TOKEN='replace-with-a-random-private-token-at-least-16-characters'
.venv/bin/python app.py
```

The Windows one-click launcher bundles this gateway, an embedded CPython runtime, installed dependencies, and upstream source/license notices. Its default bundled mode generates a per-run loopback address and token, waits for authenticated readiness, and owns that child process until stop/exit. Users do not need a system Python installation. External mode keeps the standalone/Docker deployment below available.

Standalone defaults to `127.0.0.1:8000`. `QQMUSIC_BIND`, `QQMUSIC_PORT`, and `QQMUSIC_DEVICE_PATH` override listener/device storage. The device file contains anonymous upstream device state, not an account pool. Its directory must be writable by the gateway user. Never put the account Credential or gateway token in query parameters.

Docker:

```sh
docker build -t musicparty-qqmusic .
docker run --rm --network musicparty-internal \
  -e QQMUSIC_GATEWAY_TOKEN="$QQMUSIC_GATEWAY_TOKEN" musicparty-qqmusic
```

The image listens on port 8000, runs as UID 10001, and has a health check. Do not publish its port to untrusted networks. The root compose integration should connect backend and gateway on a private Docker network; the `QQMUSIC_GATEWAY_TOKEN` values must match. `GET /health` returns only `{"protocolVersion":1}` and is intentionally unauthenticated.

## Requests

`POST /v1/{operation}` requires `Authorization: Bearer <token>` and JSON `{"params":{...}}`. Only `media`, `media-fallback`, and `fm` may receive a top-level `credential`. That snapshot accepts exactly `musicid`, `musickey`, optional `loginType` (0/1/2), and optional `encrypt_uin`. `musicid` must be a positive decimal string/integer; it is normalized before constructing upstream Credential. Unknown fields, login endpoints, refresh tokens, arbitrary endpoint/URL parameters, and public operations carrying a nonempty credential are rejected.

Public search, metadata, lyrics, user search, created playlists, playlist pages, and albums always use the separate anonymous client with an explicit empty Credential on the request. Account-aware operations create a separate short-lived client and close it after that request. There is no saved account, credential pool, login, automatic token refresh, or fallback to another account.

Supported operations follow the repository gateway contract: `search`, `track`, `lyrics`, `users`, `collections`, `container`, `tracks`, `album`, `track-album`, `media`, `media-fallback`, and `fm`. `fm` calls the upstream native `recommend.get_guess_recommend` operation (`music.radioProxy.MbTrackRadioSvr.get_radio_track`, radio 99). DJ is unsupported and fails explicitly. FM requires a supplied Credential; metadata and collections never use it.

User references are `q1.<base64url compact JSON>` containing independently verified `uin` and/or `euin`. Search uses actual `uin` and `encrypt_uin` response fields, never nickname/docid inference. Created playlists require a reference containing numeric `uin`; euin-only targets fail clearly. Containers use global `tid`/`id`, not per-user `dirId`. A private playlist (`dirinfo.dir_show == 2`) returns `COLLECTION_NOT_PUBLIC` even when upstream returns outer code zero.

Created playlists are sliced only after upstream reports a complete bounded list. Playlist cursors advance by raw upstream entries, including unavailable tracks filtered out of output. Empty pages with continuation fail instead of looping. Albums fetch every page within a 2,000-track budget; no partial album success is returned.

Media supports only ordinary `MP3_128`, `MP3_320`, and `FLAC`. It obtains `file.media_mid` from anonymous detail, uses the corresponding ordinary filename prefix, and requires successful per-file authorization, exact song/filename/path identity, nonempty relative purl, no encrypted key, positive full-file size, and known expiration greater than the safety margin. Preview/unknown/encrypted results fail closed. Declared size no larger than the preview file is rejected. It does not request encrypted formats, decrypt media, or evade an upstream entitlement decision. The fallback operation explicitly requests MP3_128; Java decides whether it is genuinely distinct from the initial representation. Timeline identity excludes encoding/quality but includes source track and duration. Signed media URLs remain ephemeral and are never logged by this service.

Limits: 16 KiB request body, 200-character search query, 100 entries/page, offset at most 100,000, eight concurrent gateway operations, 20-second operation timeout (including waiting for a concurrency slot), 5-second body-read timeout, and 2 MiB normalized response. Errors contain stable codes only, not upstream exception bodies, account credentials, or signed URLs. Access logging is disabled.

## Tests and actual verification

```sh
.venv/bin/pip install -r requirements-test.txt
.venv/bin/python -m pytest -q
```

The test suite is deterministic gateway/mapper **fixture testing**, not proof that an account or a live playback works. It covers anonymous isolation, request-scoped credentials, raw pagination, complete album reads, native-FM method selection, ordinary-format authorization checks, input rejection, bearer/body boundaries, and sanitized failures.

A small anonymous check on 2026-11-12 used the pinned upstream client, without any account or downloaded audio. Song/user search and song detail succeeded; user results supplied both numeric uin and euin (including `*`). Created-playlist response for one public target returned a complete 23-entry list. Its first listed favorites playlist was actually private; the detail response was code zero with empty tracks and a privacy flag, which the gateway now rejects. Anonymous album raw responses confirmed `basicInfo.albumMid`/`albumName` and a 12-track song page; those observed field shapes are covered by fixtures. Anonymous authorization for a paid track was rejected. No authorized FM, VIP media, browser audio, Docker build, or end-to-end Java/FFmpeg playback has been live-verified here.

## Licensing

See `UPSTREAM-NOTICE.md`. The Docker image retains the complete pinned upstream source and its LICENSE under `/usr/share/licenses/qqmusic-api-python/`. Image distributors must satisfy the upstream GPL source and notice obligations; retaining a repository URL alone is not a substitute for providing corresponding source when required.

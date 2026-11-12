# QQMusicApi Web integration

MusicParty calls the unmodified Web service included in L-1124/QQMusicApi, pinned to `c046788dd1f11daacc74da487cbbf1bcd8ac6f33`. There is no MusicParty-specific Python HTTP gateway or `/v1/{operation}` protocol. The existing document path is retained for links from earlier development notes.

## Deployment and identity

- Upstream entry point: `web/run.py`, FastAPI application `web.src.app:create_app`. Its default standalone port is 8080; our optional Docker service uses internal port 8000; the Windows launcher selects a loopback port.
- Readiness: GET `/` returns `{code:0,msg:"ok",time:...}`; GET `/openapi.json` identifies `QQMusic API` and its routes. This pinned upstream does not have `/health`.
- Do not expose this API publicly. Bundled mode binds loopback; Compose does not publish its port. It has no MusicParty gateway Bearer token. External deployments must apply their own network access controls.
- Disable global default credentials (`QQMUSIC_CREDENTIAL_ENABLED=false`) and leave `web/accounts.toml` absent or empty. Upstream still initializes its credential store; configured seed accounts can trigger startup health checks even when default selection is disabled.
- Public requests carry neither QQ cookies nor authorization headers. Media and private FM use one captured Java credential snapshot in the request Cookie header; updates invalidate pending operations and returned media. No shared account pool or fallback to a configured account's collections.
- Administrator input remains JSON. Upstream-supported fields are `musicid`, `musickey`, `openid`, `refresh_token`, `access_token`, `expired_at`, `unionid`, `str_musicid`, and `refresh_key`; `musicid` and `musickey` form the required pair. `loginType` and `encrypt_uin` are not part of this Web Cookie interface.

## Native endpoints

Upstream responses use `{code:0,msg:"ok",data:...}` and typed snake_case models. Java maps them into existing MusicParty DTOs.

| Capability | Upstream route |
| --- | --- |
| Song / user search | GET `/search/search_by_type`, `search_type=0` / `8` |
| Song detail | GET `/song/{mid}/detail` |
| Lyrics | GET `/song/{mid}/lyric` |
| Target user's created playlists | GET `/user/{uin}/created_songlists` |
| Playlist metadata / tracks | GET `/songlist/{id}/detail`, `num` and 1-based `page` |
| Album metadata / tracks | GET `/album/{mid}/detail` and `/album/{mid}/songs` |
| Ordinary media authorization | GET `/song/{mid}/url`, `file_type=13` (MP3_128), `12` (MP3_320), `7` (FLAC) |
| Native id99 recommendation | GET `/recommend/get_guess_recommend` |

Song identity remains songmid, not songid or media_mid. Playlist identity remains the global playlist ID, not dirid. User binding remains an opaque validated uin/euin reference. Public playlist queries never use member-specific or deployment-account credentials.

## Validation scope

Follow the upstream Web models rather than requiring raw-field presence evidence. Created-playlist summaries retain `dir_show`, but playlist details do not; media trial metadata has model defaults. Missing raw `dir_show`, `size_try`, `try_begin`, or `try_end` is not by itself a failure. Known unavailable resources and upstream permission failures still surface as errors. Playlist pages must advance correctly, and bounded album reads must not silently return a partial album as complete.

Media still requires a usable ordinary-format authorization result and URL, a supported codec, valid resource identity and expiry. Encrypted `ekey` media is not decrypted. Java supplies `ResolvedMedia` with representation and media-identity timeline keys so the existing player, fallback, cache and Radio can retain authorization and synchronization safeguards. Quality does not alter the timeline of the same file identity.

FM uses the configured single account and the upstream id99 endpoint, not a random playlist or daily chart. Native DJ voice is not available. Existing `/api/v2`, queue limits, source epochs, credential revision checks, media URL policy, and local LIKESONG behavior remain in place.

The upstream SDK, Web sources and license are retained in distribution. Follow its GPL-3.0-or-later terms. Fixture and startup tests do not establish live member playback, CDN availability or rights to redistribute music.

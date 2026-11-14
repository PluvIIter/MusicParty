package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.*;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * QQ 音乐音源适配器.
 *
 * <p>直接调用 L-1124/QQMusicApi 原生 Web 服务，行为与网易云源保持一致：
 * 只做字段映射与必要的空值判断，不重复实现上游的权限、风控与音质策略。</p>
 */
@Service
public class QqMusicApiService implements IMusicApiService, SourceCredentialManager {
    private static final String SOURCE = "qqmusic";
    /** 上游歌单/专辑分页的每页数量。 */
    private static final int PAGE_SIZE = 100;
    /** 搜索每页数量与最多抓取页数：一次搜索最多返回 90 条，不再只有第一页。 */
    private static final int SEARCH_PAGE_SIZE = 30;
    private static final int SEARCH_MAX_PAGES = 3;

    private final QqMusicWebClient web;
    private final QqMusicCredentialService credentials;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public QqMusicApiService(QqMusicWebClient web, QqMusicCredentialService credentials,
                             AppProperties properties, ObjectMapper mapper) {
        this.web = web;
        this.credentials = credentials;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public String getPlatformName() { return SOURCE; }
    @Override public boolean defaultDisabled() { return true; }
    @Override public DeliveryMode defaultDeliveryMode() { return DeliveryMode.DIRECT_URL; }
    @Override public long credentialRevision() { return credentials.revision(); }
    @Override public String credentialSourceId() { return SOURCE; }
    @Override public String credentialType() { return "cookie"; }
    @Override public String credentialLabel() { return "QQ音乐 Cookie"; }
    @Override public boolean isCredentialConfigured() { return credentials.configured(); }
    @Override public void updateCredential(String value) { credentials.updateCookie(value); }
    public void updateCookie(String value) { updateCredential(value); }
    public boolean isCookieConfigured() { return isCredentialConfigured(); }
    @Override public String externalUrl(String id) { return "https://y.qq.com/n/ryqq/songDetail/" + segment(id); }

    @Override public SourceDescriptor descriptor() {
        // 与网易云一致：搜索/歌词/专辑/用户/收藏/下载/Radio 全部支持，QQ 不提供私人 FM。
        return new SourceDescriptor(SOURCE, "QQ音乐", "QQ音乐", "music",
                new SourceCapabilities(true, true, true, true, true, false, true, true),
                Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD),
                List.of(new CollectionCategory("playlists", "歌单", "playlist", "CONTAINER")));
    }

    // === 搜索 ===

    @Override public Mono<List<Music>> searchMusic(String keyword) {
        return Mono.defer(() -> search(keyword, 1, new ArrayList<>()));
    }

    private Mono<List<Music>> search(String keyword, int page, List<Music> output) {
        return web.get("/search/search_by_type",
                        Map.of("keyword", keyword, "search_type", 0, "num", SEARCH_PAGE_SIZE, "page", page))
                .flatMap(data -> {
                    JsonNode songs = data.path("song");
                    for (JsonNode song : songs) output.add(track(song));
                    boolean more = page < SEARCH_MAX_PAGES
                            && data.path("nextpage").asInt(-1) > 0
                            && songs.size() >= SEARCH_PAGE_SIZE;
                    return more ? search(keyword, page + 1, output) : Mono.just(List.copyOf(output));
                });
    }

    @Override public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        return Mono.defer(() -> web.get("/search/search_by_type",
                        Map.of("keyword", keyword, "search_type", 8, "num", SEARCH_PAGE_SIZE, "page", 1))
                .map(data -> {
                    List<UserSearchResult> result = new ArrayList<>();
                    for (JsonNode user : data.path("user")) {
                        String reference = userReference(user);
                        if (reference == null) continue;
                        result.add(new UserSearchResult(reference,
                                text(user.path("title").asText(user.path("name").asText(""))),
                                user.path("pic").asText(""), SOURCE));
                    }
                    return List.copyOf(result);
                }));
    }

    private String userReference(JsonNode user) {
        Map<String, String> reference = new TreeMap<>();
        String uin = user.path("uin").asText("").strip();
        String euin = user.path("encrypt_uin").asText("").strip();
        if (isUsableReference(uin)) reference.put("uin", uin);
        if (isUsableReference(euin)) reference.put("euin", euin);
        if (reference.isEmpty()) return null;
        try {
            return "q1." + Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(reference));
        } catch (Exception error) {
            return null;
        }
    }

    private boolean isUsableReference(String value) {
        return !value.isEmpty() && value.length() <= 256
                && value.chars().noneMatch(character -> character < 32 || character == 127);
    }

    // === 歌曲 / 歌词 / 专辑 ===

    private Mono<JsonNode> detail(String id) {
        return web.get("/song/" + segment(id) + "/detail", Map.of()).map(data -> data.path("track"));
    }

    @Override public Mono<Music> getMusic(String id) {
        return Mono.defer(() -> detail(id).map(song -> track(song, id)));
    }

    @Override public Mono<String> getLyric(String id) {
        return Mono.defer(() -> web.get("/song/" + segment(id) + "/lyric", Map.of("trans", true))
                .map(data -> LyricTranslations.merge(
                        data.path("lyric").asText(""), data.path("trans").asText(""))));
    }

    @Override public Mono<AlbumInfo> getAlbum(String id) {
        return Mono.defer(() -> album(id)).timeout(Duration.ofSeconds(30));
    }

    private Mono<AlbumInfo> album(String id) {
        return web.get("/album/" + segment(id) + "/detail", Map.of()).flatMap(data -> {
            JsonNode album = data.path("album");
            return albumSongs(id, 1, new ArrayList<>(), 0).map(songs -> new AlbumInfo(
                    album.path("mid").asText(id), text(album.path("name").asText("未命名专辑")),
                    artists(data.path("singers")), cover(album), songs.size(), songs));
        });
    }

    private Mono<List<Music>> albumSongs(String id, int page, List<Music> output, int requests) {
        if (requests > 200) return Mono.error(new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐专辑分页异常"));
        return web.get("/album/" + segment(id) + "/songs", Map.of("num", PAGE_SIZE, "page", page)).flatMap(data -> {
            JsonNode songs = data.path("song_list");
            for (JsonNode song : songs) output.add(track(song));
            long total = data.path("total_num").asLong(output.size());
            if (songs.isEmpty() || output.size() >= total) return Mono.just(List.copyOf(output));
            return albumSongs(id, page + 1, output, requests + 1);
        });
    }

    @Override public Mono<AlbumInfo> getAlbumForSong(String id) {
        return Mono.defer(() -> detail(id).flatMap(song -> {
            String album = song.path("album").path("mid").asText("");
            if (album.isBlank()) return Mono.error(new ApiRequestException("这首歌暂无可添加的专辑"));
            return album(album);
        }));
    }

    // === 收藏与歌单 ===

    @Override public Mono<SourcePage<Playlist>> publicCollections(String userId, String category, int offset, int limit) {
        return Mono.defer(() -> {
            if (!"playlists".equals(category)) throw unsupported();
            if (offset < 0 || limit < 1) throw new ApiRequestException("INVALID_CURSOR: 无效分页参数");
            Target target = target(userId);
            return created(target).flatMap(created -> {
                List<Playlist> items = new ArrayList<>();
                int index = Math.min(offset, created.size());
                while (index < created.size() && items.size() < limit) items.add(created.get(index++));
                if (items.size() == limit) {
                    return Mono.just(new SourcePage<>(items, index < created.size() ? index : null, null));
                }
                int favouriteOffset = Math.max(0, offset - created.size());
                return favourites(target, favouriteOffset, limit - items.size()).map(page -> {
                    items.addAll(page.items());
                    Integer next = page.nextOffset() == null ? null : created.size() + page.nextOffset();
                    return new SourcePage<>(items, next, null);
                });
            });
        });
    }

    /** 与网易云一致：绑定用户后的“歌单”包含他创建的歌单与他人公开收藏的歌单。 */
    private Mono<List<Playlist>> created(Target target) {
        if (target.uin().isBlank()) return Mono.just(List.of());
        return web.get("/user/" + segment(target.uin()) + "/created_songlists", Map.of())
                .map(data -> {
                    List<Playlist> result = new ArrayList<>();
                    for (JsonNode item : data.path("playlists")) result.add(playlist(item));
                    return List.copyOf(result);
                });
    }

    private Mono<SourcePage<Playlist>> favourites(Target target, int offset, int limit) {
        if (target.euin().isBlank()) return Mono.just(new SourcePage<>(List.of(), null, null));
        return web.get("/user/" + segment(target.euin()) + "/fav/songlists",
                        Map.of("num", PAGE_SIZE, "page", offset / PAGE_SIZE + 1))
                .map(data -> {
                    JsonNode raw = data.path("playlists");
                    List<Playlist> items = new ArrayList<>();
                    int start = offset % PAGE_SIZE;
                    int end = Math.min(raw.size(), start + limit);
                    for (int index = Math.min(start, raw.size()); index < end; index++) items.add(playlist(raw.get(index)));
                    long total = data.path("total").asLong(-1);
                    boolean more = end < raw.size() || (total >= 0 && offset + items.size() < total);
                    return new SourcePage<>(items, more && !items.isEmpty() ? offset + items.size() : null, null);
                });
    }

    @Override public Mono<Playlist> containerDetail(String type, String id) {
        if (!"playlist".equals(type)) return IMusicApiService.super.containerDetail(type, id);
        return Mono.defer(() -> songlist(id, 1).map(data -> playlist(data.path("info"))));
    }

    private Mono<JsonNode> songlist(String id, int page) {
        return web.get("/songlist/" + segment(id) + "/detail", Map.of("num", PAGE_SIZE, "page", page));
    }

    @Override public Mono<SourcePage<Music>> containerPage(String type, String id, int offset, int limit) {
        if (!"playlist".equals(type)) return IMusicApiService.super.containerPage(type, id, offset, limit);
        return Mono.defer(() -> {
            if (offset < 0 || limit < 1) return Mono.error(new ApiRequestException("INVALID_CURSOR: 无效分页参数"));
            return playlistPage(id, offset, limit, new ArrayList<>(), 0);
        });
    }

    private Mono<SourcePage<Music>> playlistPage(String id, int cursor, int remaining,
                                                 List<Music> output, int requests) {
        if (requests > 200) return Mono.error(new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐歌单分页异常"));
        return songlist(id, cursor / PAGE_SIZE + 1).flatMap(data -> {
            JsonNode songs = data.path("songs");
            long total = data.path("total").asLong(songs.size());
            int start = cursor % PAGE_SIZE;
            if (start >= songs.size()) return Mono.just(new SourcePage<>(List.copyOf(output), null, total));
            int take = Math.min(remaining, songs.size() - start);
            for (int index = start; index < start + take; index++) output.add(track(songs.get(index)));
            int next = cursor + take;
            boolean more = next < total;
            if (take < remaining && more) return playlistPage(id, next, remaining - take, output, requests + 1);
            return Mono.just(new SourcePage<>(List.copyOf(output), more ? next : null, total));
        });
    }

    // === 媒体 ===

    @Override public Mono<ResolvedMedia> resolveMedia(String id, MediaConsumer consumer) {
        return Mono.defer(() -> resolve(id, quality()));
    }

    @Override public Mono<ResolvedMedia> resolveFallbackMedia(String id) {
        return Mono.defer(() -> "MP3_128".equals(quality()) ? Mono.error(unsupported())
                : resolve(id, "MP3_128"));
    }

    private Mono<ResolvedMedia> resolve(String id, String quality) {
        return Mono.defer(() -> detail(id).flatMap(song -> {
            Music metadata = track(song, id);
            String mediaMid = song.path("file").path("media_mid").asText("").strip();
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("file_type", fileType(quality));
            query.put("song_type", song.path("type").asInt(1));
            if (!mediaMid.isEmpty()) query.put("media_mid", mediaMid);
            return web.get("/song/" + segment(id) + "/url", query)
                    .map(data -> authorized(data, id, quality, mediaMid, metadata));
        }));
    }

    private ResolvedMedia authorized(JsonNode data, String id, String quality, String mediaMid, Music metadata) {
        JsonNode chosen = null;
        for (JsonNode item : data.path("data")) {
            if (id.equals(item.path("mid").asText("")) && !item.path("purl").asText("").isBlank()) {
                chosen = item;
                break;
            }
        }
        if (chosen == null) {
            for (JsonNode item : data.path("data")) {
                if (!item.path("purl").asText("").isBlank()) {
                    chosen = item;
                    break;
                }
            }
        }
        if (chosen == null) throw new ApiRequestException("MEDIA_UNAVAILABLE: QQ音乐没有返回可播放地址");
        String filename = chosen.path("filename").asText("");
        String extension = extension(filename, quality);
        String location = location(chosen.path("purl").asText(""));
        // 同一媒体跨音质保持同一 timeline：media_mid 是内容身份，filename 会带音质前缀。
        String identity = mediaMid.isEmpty() ? (filename.isBlank() ? id : filename) : mediaMid;
        long expiration = Math.min(Math.max(data.path("expiration").asLong(600) - 30, 60), 3600);
        return new ResolvedMedia(new ResourceKey(SOURCE, id),
                quality + ":" + (filename.isBlank() ? identity : filename), location, mime(extension), extension,
                Instant.now().plusSeconds(expiration), Map.of(), true,
                timeline(id, identity, metadata.duration()));
    }

    private String location(String purl) {
        String location;
        try {
            location = URI.create(purl).isAbsolute()
                    ? purl
                    : "https://isure.stream.qqmusic.qq.com/" + (purl.startsWith("/") ? purl.substring(1) : purl);
        } catch (Exception error) {
            throw new ApiRequestException("MEDIA_UNAVAILABLE: QQ音乐没有返回可播放地址");
        }
        if (location.startsWith("http://")) location = "https://" + location.substring(7);
        if (!location.startsWith("https://")) throw new ApiRequestException("MEDIA_UNAVAILABLE: QQ音乐没有返回可播放地址");
        return location;
    }

    private String extension(String filename, String quality) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String encrypted : List.of(".mgg", ".mflac", ".mmp4", ".mnac")) {
            if (lower.endsWith(encrypted)) throw new ApiRequestException("MEDIA_UNAVAILABLE: QQ音乐加密音质暂不支持");
        }
        for (String known : List.of(".flac", ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".mp4")) {
            if (lower.endsWith(known)) return known;
        }
        return "FLAC".equals(quality) ? ".flac" : ".mp3";
    }

    private String mime(String extension) {
        return switch (extension) {
            case ".flac" -> "audio/flac";
            case ".m4a", ".mp4" -> "audio/mp4";
            case ".aac" -> "audio/aac";
            case ".ogg" -> "audio/ogg";
            case ".wav" -> "audio/wav";
            default -> "audio/mpeg";
        };
    }

    private int fileType(String quality) {
        return switch (quality) {
            case "FLAC" -> 7;
            case "MP3_320" -> 12;
            default -> 13;
        };
    }

    /** 音质配置与网易云一样“尽力而为”：无法识别时按 320 处理，不阻断播放。 */
    private String quality() {
        String value = properties.getQqmusic().getQuality();
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "FLAC", "LOSSLESS", "SQ" -> "FLAC";
            case "MP3_128", "STANDARD", "LOW" -> "MP3_128";
            default -> "MP3_320";
        };
    }

    // === 字段映射 ===

    private Music track(JsonNode song) {
        return track(song, song.path("mid").asText(""));
    }

    private Music track(JsonNode song, String fallbackId) {
        String id = song.path("mid").asText("");
        if (id.isBlank()) id = fallbackId;
        long seconds = Math.max(song.path("interval").asLong(0), 0);
        return new Music(id, text(song.path("name").asText(song.path("title").asText(""))),
                artists(song.path("singer")), seconds * 1000, SOURCE,
                cover(song.path("album")), externalUrl(id));
    }

    private List<String> artists(JsonNode singers) {
        List<String> result = new ArrayList<>();
        for (JsonNode singer : singers) {
            String name = text(singer.path("name").asText(""));
            if (!name.isEmpty()) result.add(name);
        }
        return List.copyOf(result);
    }

    private Playlist playlist(JsonNode item) {
        String id = item.path("id").asText(item.path("disstid").asText(""));
        return new Playlist(id, text(item.path("title").asText(item.path("dissname").asText(""))),
                item.path("picurl").asText(item.path("logo").asText("")),
                item.path("songnum").asInt(item.path("song_cnt").asInt(0)), SOURCE);
    }

    private String cover(JsonNode album) {
        String mid = album.path("mid").asText(album.path("pmid").asText("")).strip();
        return mid.isEmpty() ? null : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + mid + ".jpg";
    }

    private String text(String value) {
        if (value == null) return "";
        return org.springframework.web.util.HtmlUtils.htmlUnescape(value.replaceAll("<[^>]*>", "")).strip();
    }

    private String timeline(String id, String identity, long duration) {
        try {
            String seed = id + ":" + identity + ":" + duration + ":full";
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String segment(String value) {
        return UriUtils.encodePathSegment(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    // === 用户绑定 ===

    private record Target(String uin, String euin) { }

    private Target target(String reference) {
        if (reference == null || !reference.startsWith("q1.")) {
            throw new ApiRequestException("USER_ID_UNAVAILABLE: 此用户标识不支持歌单读取");
        }
        try {
            JsonNode value = mapper.readTree(Base64.getUrlDecoder().decode(reference.substring(3)));
            String uin = value.path("uin").asText("").strip();
            String euin = value.path("euin").asText("").strip();
            if (uin.isEmpty() && euin.isEmpty()) throw new IllegalArgumentException();
            return new Target(uin, euin);
        } catch (Exception error) {
            throw new ApiRequestException("USER_ID_UNAVAILABLE: 此用户标识不支持歌单读取");
        }
    }

    private ApiRequestException unsupported() {
        return new ApiRequestException("CAPABILITY_UNAVAILABLE: QQ音乐不支持此操作");
    }
}

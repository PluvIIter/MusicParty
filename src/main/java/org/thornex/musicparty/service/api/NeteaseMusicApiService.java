package org.thornex.musicparty.service.api;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AlbumInfo;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.SecretStoreService;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import org.thornex.musicparty.media.*;
import org.thornex.musicparty.source.*;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class NeteaseMusicApiService implements IMusicApiService {

    private final WebClient webClient;
    private final String baseUrl;
    private final AppProperties.NeteaseApiConfig neteaseConfig;
    private final String initialCookieFromConfig;
    private final String quality;
    private volatile String currentCookie;
    private final AtomicLong credentialRevision = new AtomicLong();
    @Autowired(required = false)
    private SecretStoreService secretStoreService;
    private static final String PLATFORM = "netease";

    public NeteaseMusicApiService(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getNetease().getBaseUrl();
        this.neteaseConfig = appProperties.getNetease();
        this.initialCookieFromConfig = appProperties.getNetease().getCookie();
        this.quality = appProperties.getNetease().getQuality();
        // 初始化时先使用配置文件的内容
        this.currentCookie = initialCookieFromConfig;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing NeteaseCloudMusic API client with quality: {}...", quality);
        if (!StringUtils.hasText(currentCookie)) {
            log.info("Netease Cookie is empty. Service running in passive mode (waiting for config).");
        } else {
            log.info("Netease Cookie configured.");
        }
    }

    public void updateCookie(String newCookie) {
        this.currentCookie = newCookie;
        this.neteaseConfig.setCookie(newCookie);
        credentialRevision.incrementAndGet();
        if (secretStoreService != null) secretStoreService.updateNeteaseCookie(newCookie);
    }

    private String getCookie() {
        String stored = secretStoreService == null ? null : secretStoreService.getNeteaseCookie();
        if (stored != null) return stored;
        String configured = neteaseConfig.getCookie();
        if (configured != null) return configured;
        return currentCookie != null ? currentCookie : "";
    }

    public boolean isCookieConfigured() {
        String cookie = getCookie();
        return StringUtils.hasText(cookie) && !"YOUR_NETEASE_COOKIE_STRING_HERE".equals(cookie);
    }

    public Mono<JsonNode> fetchPersonalFm() {
        return fetchRecommendations("/personal_fm?cookie={cookie}&timestamp={timestamp}", null, null);
    }

    public Mono<JsonNode> fetchAidjRcmd(Double latitude, Double longitude) {
        return Mono.defer(() -> {
            if ((latitude == null) != (longitude == null)
                    || (latitude != null && (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180))) {
                return Mono.error(new ApiRequestException("推荐位置参数无效"));
            }
            String route = "/aidj/content/rcmd?cookie={cookie}&timestamp={timestamp}";
            if (latitude != null) route += "&latitude={latitude}&longitude={longitude}";
            return fetchRecommendations(route, latitude, longitude);
        });
    }

    private Mono<JsonNode> fetchRecommendations(String route, Double latitude, Double longitude) {
        return Mono.defer(() -> {
            if (!isCookieConfigured()) {
                return Mono.error(new ApiRequestException("RECOMMENDATION_AUTH_REQUIRED: 请配置网易云 Cookie"));
            }
            long revision = credentialRevision.get();
            Object[] parameters = latitude == null
                    ? new Object[]{getCookie(), System.currentTimeMillis()}
                    : new Object[]{getCookie(), System.currentTimeMillis(), latitude, longitude};
            return webClient.get().uri(baseUrl + route, parameters).retrieve()
                    .onStatus(HttpStatusCode::isError, response -> response.releaseBody().thenReturn(
                            new ApiRequestException(response.statusCode().value() == 401 || response.statusCode().value() == 403
                                    ? "RECOMMENDATION_AUTH_REQUIRED: 网易云 Cookie 无效或已过期"
                                    : "RECOMMENDATION_UNAVAILABLE: 网易云推荐请求失败")))
                    .bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(new ApiRequestException("RECOMMENDATION_UNAVAILABLE: 网易云推荐返回空响应")))
                    .map(json -> {
                        if (credentialRevision.get() != revision) throw new ApiRequestException("推荐凭据已更新，请重试");
                        validateRecommendationResponse(json);
                        return json;
                    });
        }).timeout(Duration.ofSeconds(15))
                .onErrorMap(error -> error instanceof ApiRequestException ? error
                        : new ApiRequestException("RECOMMENDATION_UNAVAILABLE: 网易云推荐请求失败"));
    }

    static void validateRecommendationResponse(JsonNode json) {
        int code = json.path("code").asInt(-1);
        int dataCode = json.path("data").path("code").asInt(200);
        if (code == 301 || code == 302 || code == 401 || code == 403
                || dataCode == 301 || dataCode == 302 || dataCode == 401 || dataCode == 403) {
            throw new ApiRequestException("RECOMMENDATION_AUTH_REQUIRED: 网易云 Cookie 无效或已过期");
        }
        if (code != 200 || dataCode != 200) {
            throw new ApiRequestException("RECOMMENDATION_UNAVAILABLE: 网易云推荐业务请求失败");
        }
    }

    // Helper to force HTTPS
    private String upgradeToHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return url.replace("http://", "https://");
        }
        return url;
    }


    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    private Mono<ApiRequestException> handleApiError(String apiName, org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new ApiRequestException(
                        String.format("Netease API '%s' failed with status %d", apiName, response.statusCode().value())
                )));
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}", keyword)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("search", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();
                    JsonNode songs = jsonNode.path("result").path("songs");
                    if (songs.isArray()) {
                        for (JsonNode song : songs) {
                            List<String> artists = new ArrayList<>();
                            JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                            artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                            musicList.add(new Music(
                                    song.path("id").asText(),
                                    song.path("name").asText(),
                                    artists,
                                    song.path("dt").asLong(),
                                    PLATFORM,
                                    upgradeToHttps(song.path("al").path("picUrl").asText()),
                                    externalUrl(song.path("id").asText())));
                        }
                    }
                    return musicList;
                });
    }

    @Override
    public SourceDescriptor descriptor() {
        return new SourceDescriptor(PLATFORM, "网易云音乐", "NETEASE", "music",
                new SourceCapabilities(true, true, true, true, true, true, true, true),
                Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD),
                List.of(new CollectionCategory("playlists", "歌单", "playlist", "CONTAINER")));
    }

    @Override
    public long credentialRevision() { return credentialRevision.get(); }

    @Override
    public String externalUrl(String id) {
        return "https://music.163.com/song?id=" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Music> getMusic(String musicId) {
        return webClient.get()
                .uri(baseUrl + "/song/detail?ids={musicId}", musicId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song detail", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    JsonNode song = jsonNode.path("songs").path(0);
                    if (song.isMissingNode()) throw new ApiRequestException("歌曲不可用");
                    List<String> artists = new ArrayList<>();
                    JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                    artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                    return new Music(
                            song.path("id").asText(),
                            song.path("name").asText(),
                            artists,
                            song.path("dt").asLong(),
                            PLATFORM,
                            upgradeToHttps(song.path("al").path("picUrl").asText()),
                            externalUrl(musicId));
                });
    }

    @Override
    public Mono<ResolvedMedia> resolveMedia(String musicId, MediaConsumer consumer) {
        return Mono.defer(() -> requestMedia(musicId, true, consumer)
                .onErrorResume(error -> requestMedia(musicId, false, consumer)));
    }

    private Mono<ResolvedMedia> requestMedia(String id, boolean v1, MediaConsumer consumer) {
        String route = v1 ? "/song/url/v1?id={id}&level={quality}&cookie={cookie}&timestamp={timestamp}"
                : "/song/url?id={id}&br={quality}&cookie={cookie}&timestamp={timestamp}";
        return webClient.get().uri(baseUrl + route, id, v1 ? quality : resolveBr(quality), getCookie(), System.currentTimeMillis())
                .retrieve().bodyToMono(JsonNode.class).map(json -> {
                    JsonNode data = json.path("data").path(0);
                    String url = data.path("url").asText("");
                    if (!url.startsWith("http://") && !url.startsWith("https://")) throw new ApiRequestException("无有效播放地址");
                    String type = data.path("type").asText("mp3").toLowerCase();
                    String mime = switch (type) {
                        case "flac" -> "audio/flac";
                        case "m4a", "mp4" -> "audio/mp4";
                        case "ogg" -> "audio/ogg";
                        default -> "audio/mpeg";
                    };
                    String extension = Set.of("flac", "m4a", "mp4", "ogg", "mp3").contains(type) ? "." + type : ".mp3";
                    String representation = "audio:" + type + ":" + data.path("br").asText(quality)
                            + ":trial:" + (data.hasNonNull("freeTrialInfo")) + ":" + data.path("time").asText("");
                    String timeline = "trial:" + data.hasNonNull("freeTrialInfo") + ":duration:" + data.path("time").asText("")
                            + ":start:" + data.path("freeTrialInfo").path("start").asText("")
                            + ":end:" + data.path("freeTrialInfo").path("end").asText("");
                    return new ResolvedMedia(new ResourceKey(PLATFORM, id), representation, upgradeToHttps(url), mime,
                            extension, Instant.now().plusSeconds(Math.min(600, Math.max(1, data.path("expi").asLong(600)))),
                            consumer == MediaConsumer.SERVER ? Map.of("Referer", "https://music.163.com/", "User-Agent", "Mozilla/5.0") : Map.of(), true, timeline);
                });
    }

    /** 将 level 音质档位映射为 eapi /song/url 的 br 码率（exhigh≈320k 高音质） */
    int resolveBr(String level) {
        if (level == null) return 320_000;
        return switch (level.toLowerCase()) {
            case "standard" -> 128_000;
            case "higher" -> 192_000;
            case "lossless", "hires", "jymaster", "sky", "jyeffect" -> 999_000;
            default -> 320_000;
        };
    }

    @Override
    public Mono<org.thornex.musicparty.source.SourcePage<Playlist>> publicCollections(
            String userId, String category, int offset, int limit) {
        if (!"playlists".equals(category)) return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 收藏类别不存在"));
        return webClient.get()
                .uri(baseUrl + "/user/playlist?uid={uid}&offset={offset}&limit={limit}", userId, offset, limit)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get public playlists", response))
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    if (json.path("code").asInt(200) != 200 || !json.path("playlist").isArray()) {
                        throw new ApiRequestException("COLLECTION_NOT_PUBLIC: 该用户的歌单不可读取");
                    }
                    List<Playlist> items = new ArrayList<>();
                    json.path("playlist").forEach(pl -> items.add(mapPlaylist(pl)));
                    boolean more = json.has("more") ? json.path("more").asBoolean() : items.size() == limit;
                    return new org.thornex.musicparty.source.SourcePage<>(items,
                            more ? offset + json.path("playlist").size() : null, null);
                });
    }

    private Playlist mapPlaylist(JsonNode pl) {
        return new Playlist(pl.path("id").asText(), pl.path("name").asText(),
                upgradeToHttps(pl.path("coverImgUrl").asText()), pl.path("trackCount").asInt(), PLATFORM);
    }

    @Override
    public Mono<Playlist> containerDetail(String type, String id) {
        if (!"playlist".equals(type)) return IMusicApiService.super.containerDetail(type, id);
        return webClient.get().uri(baseUrl + "/playlist/detail?id={id}", id)
                .retrieve().bodyToMono(JsonNode.class).map(json -> {
                    JsonNode playlist = json.path("playlist");
                    if (json.path("code").asInt(200) != 200 || playlist.path("id").asText("").isBlank()) {
                        throw new ApiRequestException("COLLECTION_NOT_PUBLIC: 歌单不可读取");
                    }
                    return mapPlaylist(playlist);
                });
    }

    @Override
    public Mono<org.thornex.musicparty.source.SourcePage<Music>> containerPage(String type, String id, int offset, int limit) {
        if (!"playlist".equals(type)) return IMusicApiService.super.containerPage(type, id, offset, limit);
        if (offset < 0 || limit < 1) return Mono.error(new ApiRequestException("INVALID_CURSOR: 无效分页参数"));
        return webClient.get()
                .uri(baseUrl + "/playlist/track/all?id={playlistId}&limit={limit}&offset={offset}", id, limit, offset)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get playlist tracks", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt(200) != 200 || !jsonNode.path("songs").isArray()) {
                        throw new ApiRequestException("COLLECTION_NOT_PUBLIC: 歌单内容不可读取");
                    }
                    List<Music> musicList = new ArrayList<>();
                    jsonNode.path("songs").forEach(song -> {
                        JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                        List<String> artists = StreamSupport.stream(artistNode.spliterator(), false)
                                .map(artist -> artist.path("name").asText())
                                .toList();
                        musicList.add(new Music(
                                song.path("id").asText(),
                                song.path("name").asText(),
                                artists,
                                song.path("dt").asLong(),
                                PLATFORM,
                                upgradeToHttps(song.path("al").path("picUrl").asText()),
                                externalUrl(song.path("id").asText())));
                    });
                    return new SourcePage<>(musicList, musicList.size() == limit ? offset + musicList.size() : null, null);
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}&type=1002", keyword)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<UserSearchResult> users = new ArrayList<>();
                    // 网易云返回结构: result.userprofiles
                    JsonNode profiles = jsonNode.path("result").path("userprofiles");
                    if (profiles.isArray()) {
                        profiles.forEach(u -> users.add(new UserSearchResult(
                                u.path("userId").asText(),
                                u.path("nickname").asText(),
                                upgradeToHttps(u.path("avatarUrl").asText()),
                                PLATFORM
                        )));
                    }
                    return users;
                });
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        return webClient.get()
                .uri(baseUrl + "/lyric?id={id}", musicId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    String original = json.path("lrc").path("lyric").asText("");
                    String translation = json.path("tlyric").path("lyric").asText("");
                    return LyricTranslationMerger.merge(original, translation);
                })
                .onErrorReturn("");
    }

    private void validateAlbumId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) {
            throw new ApiRequestException("无效的歌曲或专辑 ID");
        }
    }

    private Mono<JsonNode> fetchAlbumData(String route, String id) {
        return webClient.get()
                .uri(baseUrl + route, id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.releaseBody()
                        .then(Mono.error(new ApiRequestException("专辑读取失败，请稍后重试"))))
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(8))
                .onErrorMap(error -> error instanceof ApiRequestException ? error
                        : new ApiRequestException("专辑读取失败，请检查音乐接口连接"));
    }

    @Override
    public Mono<AlbumInfo> getAlbumForSong(String musicId) {
        return Mono.defer(() -> {
            validateAlbumId(musicId);
            return fetchAlbumData("/song/detail?ids={id}", musicId).flatMap(json -> {
                JsonNode song = json.path("songs").path(0);
                String albumId = song.path("al").path("id").asText("");
                if (albumId.isBlank()) albumId = song.path("album").path("id").asText("");
                if (albumId.isBlank() || "0".equals(albumId)) {
                    return Mono.error(new ApiRequestException("这首歌暂无可添加的专辑"));
                }
                return getAlbum(albumId);
            });
        });
    }

    @Override
    public Mono<AlbumInfo> getAlbum(String albumId) {
        return Mono.defer(() -> {
            validateAlbumId(albumId);
            return fetchAlbumData("/album?id={id}", albumId).map(json -> {
                JsonNode album = json.path("album");
                if (album.path("id").asText("").isBlank() || !json.path("songs").isArray()) {
                    throw new ApiRequestException("未找到专辑，或当前账号无法读取该专辑");
                }
                Map<String, Music> songs = new LinkedHashMap<>();
                for (JsonNode song : json.path("songs")) {
                    String id = song.path("id").asText("");
                    if (id.isBlank() || "0".equals(id)) continue;
                    JsonNode al = song.has("al") ? song.path("al") : song.path("album");
                    songs.putIfAbsent(id, new Music(id, song.path("name").asText("未知歌曲"),
                            albumArtists(song), song.path("dt").asLong(song.path("duration").asLong(0)),
                            PLATFORM, upgradeToHttps(al.path("picUrl").asText(album.path("picUrl").asText(""))), externalUrl(id)));
                }
                if (songs.isEmpty()) throw new ApiRequestException("专辑中没有可添加的曲目");
                return new AlbumInfo(album.path("id").asText(),
                        album.path("name").asText("未命名专辑"), albumArtists(album),
                        upgradeToHttps(album.path("picUrl").asText("")), songs.size(), List.copyOf(songs.values()));
            });
        });
    }

    private List<String> albumArtists(JsonNode value) {
        JsonNode artists = value.has("ar") ? value.path("ar") : value.path("artists");
        List<String> names = new ArrayList<>();
        for (JsonNode artist : artists) {
            if (!artist.path("name").asText("").isBlank()) names.add(artist.path("name").asText());
        }
        if (names.isEmpty() && value.has("artist")) names.add(value.path("artist").path("name").asText("未知歌手"));
        return names;
    }

}

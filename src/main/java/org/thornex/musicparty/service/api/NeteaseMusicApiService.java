package org.thornex.musicparty.service.api;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
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
        if (!StringUtils.hasText(currentCookie) || "YOUR_NETEASE_COOKIE_STRING_HERE".equals(currentCookie)) {
            log.info("Netease Cookie is empty. Service running in passive mode (waiting for config).");
        } else {
            log.info("Netease Cookie configured.");
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(currentCookie) || "YOUR_NETEASE_COOKIE_STRING_HERE".equals(currentCookie)) {
            throw new ApiRequestException("尚未配置网易云 Cookie，请联系管理员设置");
        }
    }

    public void updateCookie(String newCookie) {
        this.currentCookie = newCookie;
        this.neteaseConfig.setCookie(newCookie);
        checkCookie(newCookie).subscribe(isValid -> {
            if (isValid) {
                log.info("Netease cookie updated and verified successfully.");
            } else {
                log.warn("The newly updated Netease cookie appears to be invalid.");
            }
        });
    }

    private Mono<Boolean> checkCookie(String cookie) {
        return webClient.get()
                .uri(baseUrl + "/user/account?cookie={cookie}", cookie)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.has("profile") && !jsonNode.get("profile").isNull())
                .onErrorReturn(false);
    }

    // UPDATED: Renamed method and removed encoding.
    private String getCookie() {
        return currentCookie != null ? currentCookie : "";
    }

    /** 是否已配置可用的网易云 cookie（非空且非占位符） */
    public boolean isCookieConfigured() {
        return StringUtils.hasText(neteaseConfig.getCookie())
                && !"YOUR_NETEASE_COOKIE_STRING_HERE".equals(neteaseConfig.getCookie());
    }

    /** 拉取私人FM 推荐（返回原始 JSON，解析在 PrivateDjService） */
    public Mono<JsonNode> fetchPersonalFm() {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/personal_fm?cookie={cookie}", getCookie())
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    /** 拉取私人DJ 推荐（经纬度可选，可为 null） */
    public Mono<JsonNode> fetchAidjRcmd(Double latitude, Double longitude) {
        ensureConfigured();
        if (latitude != null) {
            return webClient.get()
                    .uri(baseUrl + "/aidj/content/rcmd?cookie={cookie}&latitude={lat}&longitude={lon}",
                            getCookie(), latitude, longitude)
                    .retrieve()
                    .bodyToMono(JsonNode.class);
        }
        return webClient.get()
                .uri(baseUrl + "/aidj/content/rcmd?cookie={cookie}", getCookie())
                .retrieve()
                .bodyToMono(JsonNode.class);
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
                        String.format("Netease API '%s' failed with status %d: %s", apiName, response.statusCode().value(), errorBody)
                )));
    }

    // UPDATED: All API calls now use the raw cookie from getCookie()
    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}&cookie={cookie}", keyword, getCookie())
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
                                    upgradeToHttps(song.path("al").path("picUrl").asText())
                            ));
                        }
                    }
                    return musicList;
                });
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String musicId) {
        ensureConfigured();
        Mono<Music> musicDetailsMono = getMusicDetails(musicId);
        // 优先 xeapi /song/url/v1（支持更高音质），失败（404 或空 url）自动回退 eapi /song/url
        Mono<String> musicUrlMono = resolveSongUrl(musicId);

        return Mono.zip(musicDetailsMono, musicUrlMono)
                .map(tuple -> new PlayableMusic(
                        tuple.getT1().id(),
                        tuple.getT1().name(),
                        tuple.getT1().artists(),
                        tuple.getT1().duration(),
                        tuple.getT1().platform(),
                        upgradeToHttps(tuple.getT2()),
                        tuple.getT1().coverUrl(),
                        false
                ));
    }

    /**
     * 私人FM/DJ 推荐歌曲的播放直链（与普通点播一致的回退策略：xeapi 优先、eapi 兜底）。
     * 从段信息直接构造，省一次 /song/detail。
     */
    public Mono<String> getFmDjSongUrl(String musicId) {
        return resolveSongUrl(musicId).map(this::upgradeToHttps);
    }

    /**
     * 歌曲直链：先尝试 xeapi /song/url/v1（高音质档位），
     * 出错或返回空 url 时回退到 eapi /song/url（br 码率，稳定）。
     * 部署环境的 api-enhanced 若 xeapi 公钥未就绪，/song/url/v1 会一律 404，这里自动降级不影响播放。
     */
    private Mono<String> resolveSongUrl(String musicId) {
        return xeapiSongUrl(musicId)
                .flatMap(url -> StringUtils.hasText(url)
                        ? Mono.just(url)
                        : Mono.error(new ApiRequestException("xeapi song url empty, falling back to eapi")))
                .onErrorResume(e -> {
                    log.debug("xeapi /song/url/v1 failed ({}), falling back to eapi /song/url", e.getMessage());
                    return eapiSongUrl(musicId);
                });
    }

    private Mono<String> xeapiSongUrl(String musicId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/song/url/v1?id={musicId}&level={quality}&cookie={cookie}", musicId, quality, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song URL", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.path("data").get(0).path("url").asText());
    }

    private Mono<String> eapiSongUrl(String musicId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/song/url?id={musicId}&br={br}&cookie={cookie}", musicId, resolveBr(quality), getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song URL (eapi fallback)", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.path("data").get(0).path("url").asText());
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

    private Mono<Music> getMusicDetails(String musicId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/song/detail?ids={musicId}&cookie={cookie}", musicId, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song detail", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    JsonNode song = jsonNode.path("songs").get(0);
                    List<String> artists = new ArrayList<>();
                    JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                    artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                    return new Music(
                            song.path("id").asText(),
                            song.path("name").asText(),
                            artists,
                            song.path("dt").asLong(),
                            PLATFORM,
                            upgradeToHttps(song.path("al").path("picUrl").asText())
                    );
                });
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/user/playlist?uid={userId}&cookie={cookie}", userId, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get user playlists", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Playlist> playlists = new ArrayList<>();
                    jsonNode.path("playlist").forEach(pl -> playlists.add(new Playlist(
                            pl.path("id").asText(),
                            pl.path("name").asText(),
                            upgradeToHttps(pl.path("coverImgUrl").asText()),
                            pl.path("trackCount").asInt(),
                            PLATFORM
                    )));
                    return playlists;
                });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/playlist/track/all?id={playlistId}&limit={limit}&offset={offset}&cookie={cookie}", playlistId, limit, offset, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get playlist tracks", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
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
                                upgradeToHttps(song.path("al").path("picUrl").asText())
                        ));
                    });
                    return musicList;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        ensureConfigured();
        // type=1002 表示搜索用户
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}&type=1002&cookie={cookie}", keyword, getCookie())
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
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/lyric?id={id}", musicId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    // 尝试获取 lrc.lyric
                    if (json.has("lrc") && json.get("lrc").has("lyric")) {
                        return json.get("lrc").get("lyric").asText();
                    }
                    return ""; // 没有歌词
                })
                .onErrorReturn("");
    }
}
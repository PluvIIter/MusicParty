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
    private final String initialCookieFromConfig;
    private volatile String currentCookie;
    private static final String PLATFORM = "netease";

    public NeteaseMusicApiService(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getNetease().getBaseUrl();
        this.initialCookieFromConfig = appProperties.getNetease().getCookie();
        // 初始化时先使用配置文件的内容
        this.currentCookie = initialCookieFromConfig;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing NeteaseCloudMusic API client...");
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
        Mono<String> musicUrlMono = webClient.get()
                .uri(baseUrl + "/song/url/v1?id={musicId}&level=lossless&cookie={cookie}", musicId, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song URL", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.path("data").get(0).path("url").asText());

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
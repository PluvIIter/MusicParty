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


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class NeteaseMusicApiService implements IMusicApiService {

    private final WebClient webClient;
    private final String baseUrl;
    private final String initialCookieFromConfig;
    private static final String PLATFORM = "netease";
    private static final Path COOKIE_FILE_PATH = Paths.get("cookie.txt");

    public NeteaseMusicApiService(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getNetease().getBaseUrl();
        this.initialCookieFromConfig = appProperties.getNetease().getCookie();
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing NeteaseCloudMusic API client...");
        try {
            login().block();
            log.info("NeteaseCloudMusic API client login successful.");
        } catch (Exception e) {
            log.error("NeteaseCloudMusic API login failed. Application startup will be aborted.", e);
            throw new RuntimeException("Failed to initialize NeteaseCloudMusic API", e);
        }
    }

    private Mono<Void> login() {
        return Mono.defer(() -> {
            if (Files.exists(COOKIE_FILE_PATH)) {
                log.info("Found cookie.txt, attempting to use it for login.");
                try {
                    String cookieFromFile = Files.readString(COOKIE_FILE_PATH, StandardCharsets.UTF_8);
                    return checkCookie(cookieFromFile)
                            .flatMap(isValid -> {
                                if (isValid) {
                                    log.info("Cookie from file is valid.");
                                    return Mono.empty();
                                } else {
                                    log.warn("Cookie from file is invalid. Deleting it and trying config cookie.");
                                    try {
                                        Files.delete(COOKIE_FILE_PATH);
                                    } catch (IOException e) {
                                        return Mono.error(new RuntimeException("Failed to delete invalid cookie.txt", e));
                                    }
                                    return attemptLoginWithConfigCookie();
                                }
                            });
                } catch (IOException e) {
                    return Mono.error(new RuntimeException("Failed to read cookie.txt", e));
                }
            } else {
                return attemptLoginWithConfigCookie();
            }
        });
    }

    private Mono<Void> attemptLoginWithConfigCookie() {
        if (!StringUtils.hasText(initialCookieFromConfig) || "YOUR_NETEASE_COOKIE_STRING_HERE".equals(initialCookieFromConfig)) {
            return Mono.error(new ApiRequestException("cookie.txt not found and no valid cookie provided in application.yml."));
        }
        log.info("Attempting to login with cookie from application.yml.");
        return checkCookie(initialCookieFromConfig)
                .flatMap(isValid -> {
                    if (isValid) {
                        log.info("Cookie from config is valid. Saving to cookie.txt.");
                        try {
                            Files.writeString(COOKIE_FILE_PATH, initialCookieFromConfig, StandardCharsets.UTF_8);
                            return Mono.empty();
                        } catch (IOException e) {
                            return Mono.error(new RuntimeException("Failed to write valid cookie to cookie.txt", e));
                        }
                    } else {
                        return Mono.error(new ApiRequestException("The cookie provided in application.yml is invalid."));
                    }
                });
    }

    // UPDATED: No longer encoding the cookie.
    private Mono<Boolean> checkCookie(String cookie) {
        // REMOVED: String encodedCookie = URLEncoder.encode(cookie, StandardCharsets.UTF_8);
        return webClient.get()
                // Directly pass the raw cookie string. WebClient handles URI variable expansion correctly.
                .uri(baseUrl + "/user/account?cookie={cookie}", cookie)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.has("profile") && !jsonNode.get("profile").isNull())
                .onErrorReturn(false);
    }

    // UPDATED: Renamed method and removed encoding.
    private String getCookie() {
        try {
            // Just read the cookie and return it raw.
            return Files.readString(COOKIE_FILE_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read cookie.txt during API call.", e);
        }
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
                                    song.path("al").path("picUrl").asText()
                            ));
                        }
                    }
                    return musicList;
                });
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String musicId) {
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
                        tuple.getT2(),
                        tuple.getT1().coverUrl(),
                        false
                ));
    }

    private Mono<Music> getMusicDetails(String musicId) {
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
                            song.path("al").path("picUrl").asText()
                    );
                });
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
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
                            pl.path("coverImgUrl").asText(),
                            pl.path("trackCount").asInt(),
                            PLATFORM
                    )));
                    return playlists;
                });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
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
                                song.path("al").path("picUrl").asText()
                        ));
                    });
                    return musicList;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
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
                                u.path("avatarUrl").asText(),
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
                    // 尝试获取 lrc.lyric
                    if (json.has("lrc") && json.get("lrc").has("lyric")) {
                        return json.get("lrc").get("lyric").asText();
                    }
                    return ""; // 没有歌词
                })
                .onErrorReturn("");
    }
}
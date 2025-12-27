package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.MusicProxyService;
import org.thornex.musicparty.util.BilibiliApiUtils;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class BilibiliMusicApiService implements IMusicApiService {

    private final WebClient webClient;
    private final String baseUrl;
    private final String sessdata; // NEW: SESSDATA cookie
    private final MusicProxyService musicProxyService;
    private static final String PLATFORM = "bilibili";

    public BilibiliMusicApiService(WebClient webClient, AppProperties appProperties, MusicProxyService musicProxyService) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getBilibili().getBaseUrl();
        this.sessdata = appProperties.getBilibili().getSessdata();
        this.musicProxyService = musicProxyService; // NEW
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }
    private WebClient.RequestHeadersSpec<?> buildBilibiliRequest(String uri) {
        return webClient.get()
                .uri(uri)
                .header("Cookie", "SESSDATA=" + this.sessdata)
                .header("Referer", "https://www.bilibili.com/");
    }
    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        String searchUri = UriComponentsBuilder.fromUriString(baseUrl + "/x/web-interface/search/type")
                .queryParam("search_type", "video")
                .queryParam("keyword", keyword)
                .toUriString();

        return buildBilibiliRequest(searchUri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();
                    jsonNode.path("data").path("result").forEach(video -> musicList.add(new Music(
                            video.path("bvid").asText(),
                            video.path("title").asText().replaceAll("<[^>]*>", ""),
                            List.of(video.path("author").asText()),
                            BilibiliApiUtils.durationToMillis(video.path("duration").asText()),
                            PLATFORM,
                            "https:" + video.path("pic").asText()
                    )));
                    return musicList;
                });
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String bvid) {
        return BilibiliApiUtils.getVideoCid(bvid, webClient, baseUrl, sessdata)
                .flatMap(cid -> {
                    String playUrlApi = baseUrl + "/x/player/playurl?bvid={bvid}&cid={cid}&fnval=16";
                    return buildBilibiliRequest(UriComponentsBuilder.fromUriString(playUrlApi).build(bvid, cid).toString())
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .flatMap(jsonNode -> {
                                if (jsonNode.path("code").asInt() != 0) {
                                    return Mono.error(new ApiRequestException("Bilibili playurl error: " + jsonNode.path("message").asText()));
                                }
                                JsonNode audioStreams = jsonNode.path("data").path("dash").path("audio");
                                String audioUrl = StreamSupport.stream(audioStreams.spliterator(), false)
                                        .max(Comparator.comparingInt(a -> a.path("id").asInt()))
                                        .map(a -> a.path("baseUrl").asText())
                                        .orElseThrow(() -> new ApiRequestException("No audio stream found for bvid: " + bvid));

                                // --- UPDATED LOGIC ---
                                // 1. 命令 MusicProxyService 开始下载这个音频
                                musicProxyService.startProxy(audioUrl);

                                // 2. 返回一个指向我们自己代理控制器的静态URL
                                String proxyUrl = "/proxy/stream";

                                return BilibiliApiUtils.getVideoDetails(bvid, webClient, baseUrl, sessdata)
                                        .map(music -> new PlayableMusic(
                                                music.id(), music.name(), music.artists(), music.duration(), PLATFORM, proxyUrl, music.coverUrl(), true
                                        ));
                            });
                });
    }
    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        // Bilibili "Playlist" is "Favorites Folder" (收藏夹)
        // type=2 means video favorites
        String favListApi = baseUrl + "/x/v3/fav/folder/created/list-all?type=2&up_mid={userId}";
        return buildBilibiliRequest(UriComponentsBuilder.fromUriString(favListApi).build(userId).toString())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt() != 0) throw new ApiRequestException("Failed to get Bilibili favorites");
                    List<Playlist> playlists = new ArrayList<>();
                    jsonNode.path("data").path("list").forEach(fav -> playlists.add(new Playlist(
                            fav.path("id").asText(),
                            fav.path("title").asText(),
                            fav.path("cover").asText(),
                            fav.path("media_count").asInt(),
                            PLATFORM
                    )));
                    return playlists;
                });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        // Convert offset/limit to Bilibili's page number (pn) and page size (ps)
        int pageNumber = (offset / limit) + 1;

        String favDetailApi = baseUrl + "/x/v3/fav/resource/list?media_id={playlistId}&ps={pageSize}&pn={pageNumber}";
        return buildBilibiliRequest(UriComponentsBuilder.fromUriString(favDetailApi).build(playlistId, limit, pageNumber).toString())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt() != 0) {
                        // Check for specific error code indicating end of list
                        if (jsonNode.path("code").asInt() == -404 || jsonNode.path("data").path("medias").isEmpty()) {
                            return new ArrayList<Music>(); // Return empty list if page is out of bounds
                        }
                        throw new ApiRequestException("Failed to get musics from Bilibili favorite");
                    }
                    List<Music> musicList = new ArrayList<>();
                    jsonNode.path("data").path("medias").forEach(media -> {
                        if (media.path("title").asText().equals("已失效视频")) return;
                        musicList.add(new Music(
                                media.path("bvid").asText(),
                                media.path("title").asText(),
                                List.of(media.path("upper").path("name").asText()),
                                media.path("duration").asLong() * 1000,
                                PLATFORM,
                                media.path("cover").asText()
                        ));
                    });
                    return musicList;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        // B站搜索用户接口
        String searchUri = UriComponentsBuilder.fromUriString(baseUrl + "/x/web-interface/search/type")
                .queryParam("search_type", "bili_user")
                .queryParam("keyword", keyword)
                .toUriString();

        return buildBilibiliRequest(searchUri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<UserSearchResult> users = new ArrayList<>();
                    JsonNode results = jsonNode.path("data").path("result");
                    if (results.isArray()) {
                        results.forEach(u -> users.add(new UserSearchResult(
                                u.path("mid").asText(),
                                u.path("uname").asText(),
                                "https:" + u.path("upic").asText(), // B站图片通常缺协议头
                                PLATFORM
                        )));
                    }
                    return users;
                });
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        return Mono.just(""); // B站暂时不支持歌词
    }
}
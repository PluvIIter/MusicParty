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

import java.util.*;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class BilibiliMusicApiService implements IMusicApiService {

    private final WebClient webClient;
    private final String baseUrl;
    private final String sessdata; // NEW: SESSDATA cookie
    private final MusicProxyService musicProxyService;
    private static final String PLATFORM = "bilibili";
    private final BilibiliWbiService wbiService;

    //最大时长
    private static final long MAX_DURATION_MS = 10 * 60 * 1000;

    public BilibiliMusicApiService(WebClient webClient, AppProperties appProperties, MusicProxyService musicProxyService, BilibiliWbiService wbiService) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getBilibili().getBaseUrl();
        this.sessdata = appProperties.getBilibili().getSessdata();
        this.musicProxyService = musicProxyService; // NEW
        this.wbiService = wbiService;
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
        // 1. 准备请求参数（严格按照文档要求的 type 搜索）
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");      // 默认第一页
        params.put("page_size", "20"); // 文档默认 20

        // 2. 调用 WBI 签名服务
        return wbiService.signParams(params)
                .flatMap(signedParams -> {
                    // 3. 构建 QueryString，注意：WBI 签名要求参数顺序及 URL 编码
                    // 这里我们直接利用 UriComponentsBuilder 确保符合文档要求
                    UriComponentsBuilder builder = UriComponentsBuilder
                            .fromHttpUrl(baseUrl + "/x/web-interface/wbi/search/type");

                    signedParams.forEach(builder::queryParam);

                    return webClient.get()
                            .uri(builder.build().toUri()) // 使用编码后的 URI
                            .header("Cookie", "SESSDATA=" + sessdata)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.bilibili.com/") // 必须带 Referer
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .map(json -> {
                                List<Music> musicList = new ArrayList<>();

                                // 校验返回码
                                if (json.path("code").asInt() != 0) {
                                    log.error("Bilibili search failed: {}", json.path("message").asText());
                                    return musicList;
                                }

                                JsonNode results = json.path("data").path("result");
                                if (results.isArray()) {
                                    results.forEach(video -> {
                                        // 清洗标题中的 <em class="keyword">xxx</em> 标签
                                        String rawTitle = video.path("title").asText();
                                        String cleanTitle = rawTitle.replaceAll("<[^>]*>", "");

                                        // 处理时长
                                        String durationStr = video.path("duration").asText();
                                        long durationMs = BilibiliApiUtils.durationToMillis(durationStr);

                                        // 获取图片，确保有 https
                                        String picUrl = video.path("pic").asText();
                                        if (!picUrl.startsWith("http")) {
                                            picUrl = "https:" + picUrl;
                                        }

                                        musicList.add(new Music(
                                                video.path("bvid").asText(),
                                                cleanTitle,
                                                List.of(video.path("author").asText()),
                                                durationMs,
                                                PLATFORM,
                                                picUrl
                                        ));
                                    });
                                }
                                return musicList;
                            });
                });
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String bvid) {
        // 1. 先获取 CID (Page ID)
        return BilibiliApiUtils.getVideoInfo(bvid, webClient, baseUrl, sessdata)
                .flatMap(info -> {
                    if (info.music().duration() > MAX_DURATION_MS) {
                        return Mono.error(new ApiRequestException("视频过长（超过10分钟），禁止点播"));
                    }

                    String cid = info.cid();

                    // 2. 准备参数，严格按照 WBI 要求
                    Map<String, String> params = new HashMap<>();
                    params.put("bvid", bvid);
                    params.put("cid", cid);
                    // fnval=16 是关键：请求 DASH 格式，这样才能把 Audio 单独提取出来
                    // 如果不加这个，可能只返回 mp4 混流，处理起来很麻烦
                    params.put("fnval", "16");
                    // params.put("fourk", "1"); // 可选：请求 4K (虽然这里只取音频，但加上无妨)

                    // 3. 进行 WBI 签名
                    return wbiService.signParams(params)
                            .flatMap(signedParams -> {
                                // 4. 构建 WBI 接口 URL
                                UriComponentsBuilder builder = UriComponentsBuilder
                                        .fromHttpUrl(baseUrl + "/x/player/wbi/playurl");

                                signedParams.forEach(builder::queryParam);

                                return webClient.get()
                                        .uri(builder.build().toUri())
                                        .header("Cookie", "SESSDATA=" + sessdata)
                                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                        // Referer 必须带，B站防盗链检查很严
                                        .header("Referer", "https://www.bilibili.com/video/" + bvid)
                                        .retrieve()
                                        .bodyToMono(JsonNode.class)
                                        .flatMap(jsonNode -> {
                                            // 5. 错误检查
                                            if (jsonNode.path("code").asInt() != 0) {
                                                return Mono.error(new ApiRequestException("Bilibili playurl error: " + jsonNode.path("message").asText()));
                                            }

                                            // 6. 提取 DASH 音频流
                                            JsonNode dash = jsonNode.path("data").path("dash");
                                            JsonNode audioStreams = dash.path("audio");

                                            // 如果没有 dash.audio，尝试检查是否是老式 durl (通常意味着 fnval=16 没生效或视频不支持)
                                            if (audioStreams.isMissingNode() || !audioStreams.isArray() || audioStreams.isEmpty()) {
                                                // 兜底逻辑：有些非 DASH 视频
                                                JsonNode durl = jsonNode.path("data").path("durl");
                                                if (!durl.isEmpty() && durl.isArray()) {
                                                    String mp4Url = durl.get(0).path("url").asText();
                                                    log.info("Using fallback DURL (MP4) for bvid: {}", bvid);
                                                    return processAudioUrl(info.music(), mp4Url);
                                                }
                                                return Mono.error(new ApiRequestException("No audio stream found for bvid: " + bvid));
                                            }

                                            // 7. 寻找最佳音质 (ID 越大音质越好: 30280 > 30232 > 30216)
                                            String audioUrl = StreamSupport.stream(audioStreams.spliterator(), false)
                                                    .max(Comparator.comparingInt(a -> a.path("id").asInt()))
                                                    .map(a -> a.path("baseUrl").asText())
                                                    .orElseThrow(() -> new ApiRequestException("Failed to extract audio url"));

                                            return processAudioUrl(info.music(), audioUrl);
                                        });
                            });
                });
    }

    /**
     * 辅助方法：处理提取到的 URL (启动代理 + 获取元数据)
     */
    private Mono<PlayableMusic> processAudioUrl(Music music, String targetUrl) {

        return Mono.just(new PlayableMusic(
                music.id(),
                music.name(),
                music.artists(),
                music.duration(),
                PLATFORM,
                targetUrl,
                music.coverUrl(),
                true
        ));
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        // API: /x/v3/fav/folder/created/list-all
        // 参数: up_mid (目标用户ID)
        // 注意：移除了 type=2，以获取所有类型的收藏夹
        String favListApi = baseUrl + "/x/v3/fav/folder/created/list-all";

        // 构建 URI
        String uri = UriComponentsBuilder.fromHttpUrl(favListApi)
                .queryParam("up_mid", userId)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt() != 0) {
                        // 如果用户隐私设置导致无法获取，或者用户不存在，返回空列表而非报错
                        log.warn("Failed to get Bilibili favorites for user {}: {}", userId, jsonNode.path("message").asText());
                        return new ArrayList<>();
                    }

                    List<Playlist> playlists = new ArrayList<>();
                    JsonNode list = jsonNode.path("data").path("list");

                    if (list.isArray()) {
                        list.forEach(fav -> {
                            // 过滤掉媒体数为0的空收藏夹
                            int count = fav.path("media_count").asInt();
                            if (count > 0) {
                                playlists.add(new Playlist(
                                        fav.path("id").asText(), // 这里是 media_id / fid
                                        fav.path("title").asText(),
                                        // B站收藏夹有时候没有封面，可以用默认图，或者取第一张
                                        // cover 字段通常存在
                                        fav.path("cover").asText(),
                                        count,
                                        PLATFORM
                                ));
                            }
                        });
                    }
                    return playlists;
                });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        int safeLimit = Math.min(limit, 20);

        int pageNumber = (offset / safeLimit) + 1;

        // API: /x/v3/fav/resource/list
        // 关键参数: media_id (收藏夹ID), pn (页码), ps (页大小), platform=web
        String favDetailApi = baseUrl + "/x/v3/fav/resource/list";

        String uri = UriComponentsBuilder.fromHttpUrl(favDetailApi)
                .queryParam("media_id", playlistId)
                .queryParam("ps", safeLimit)
                .queryParam("pn", pageNumber)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();

                    int code = jsonNode.path("code").asInt();
                    if (code != 0) {
                        // -404 通常表示空页或没有权限，视为正常结束
                        if (code == -404) return musicList;
                        log.error("Failed to get Bilibili favorite details: {}", jsonNode.path("message").asText());
                        return musicList;
                    }

                    JsonNode medias = jsonNode.path("data").path("medias");
                    // 注意：如果是空文件夹，medias可能是 null
                    if (medias.isArray()) {
                        medias.forEach(media -> {
                            String title = media.path("title").asText();
                            // 过滤失效视频
                            if ("已失效视频".equals(title)) {
                                musicList.add(new Music(
                                        "INVALID_SKIP", // 特殊 ID
                                        "已失效视频",
                                        List.of("Unknown"),
                                        0,
                                        PLATFORM,
                                        ""
                                ));
                                return; // 结束当前循环，继续下一个
                                }

                            // 构造 Music 对象
                            musicList.add(new Music(
                                    media.path("bvid").asText(),
                                    title,
                                    List.of(media.path("upper").path("name").asText()),
                                    media.path("duration").asLong() * 1000,
                                    PLATFORM,
                                    media.path("cover").asText()
                            ));
                        });
                    }
                    return musicList;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        // 1. 准备 WBI 搜索参数
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "bili_user"); // 搜索用户类型
        params.put("keyword", keyword);
        // params.put("page", "1"); // 默认第1页，可选

        // 2. 调用 WBI 签名服务
        return wbiService.signParams(params)
                .flatMap(signedParams -> {
                    // 3. 构建 URL: /x/web-interface/wbi/search/type
                    UriComponentsBuilder builder = UriComponentsBuilder
                            .fromHttpUrl(baseUrl + "/x/web-interface/wbi/search/type");

                    signedParams.forEach(builder::queryParam);

                    return webClient.get()
                            .uri(builder.build().toUri())
                            .header("Cookie", "SESSDATA=" + sessdata)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.bilibili.com/")
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .map(jsonNode -> {
                                List<UserSearchResult> users = new ArrayList<>();

                                if (jsonNode.path("code").asInt() != 0) {
                                    log.error("Bilibili user search failed: {}", jsonNode.path("message").asText());
                                    return users;
                                }

                                JsonNode results = jsonNode.path("data").path("result");
                                if (results.isArray()) {
                                    results.forEach(u -> {
                                        String pic = u.path("upic").asText();
                                        if (!pic.startsWith("http")) {
                                            pic = "https:" + pic;
                                        }
                                        users.add(new UserSearchResult(
                                                u.path("mid").asText(),
                                                u.path("uname").asText(),
                                                pic,
                                                PLATFORM
                                        ));
                                    });
                                }
                                return users;
                            });
                });
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        return Mono.just(""); // B站暂时不支持歌词
    }
}
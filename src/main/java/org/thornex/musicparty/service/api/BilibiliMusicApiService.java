package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.util.BilibiliApiUtils;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.util.retry.Retry;

import java.util.*;

@Service
@Slf4j
public class BilibiliMusicApiService implements IMusicApiService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final String baseUrl;
    private final LocalCacheService localCacheService;
    private static final String PLATFORM = "bilibili";
    private final BilibiliWbiService wbiService;
    private final BilibiliCookieService cookieService;

    /**
     * 音质优先档位（B站音频 id）：30251=Hi-Res无损 / 30250=杜比全景声 / 30280=192K / 30232=132K / 30216=64K。
     * 采用"最佳可用"：响应里只包含账号可访问的流——大会员账号会出现 30250/30251（自动取最高），
     * 普通账号只有 30280/30232/30216（取 192K），不会选到无权访问的流。
     */
    private static final List<Integer> PREFERRED_AUDIO_IDS = List.of(30251, 30250, 30280, 30232, 30216);

    private static class WbiSignatureException extends RuntimeException {
        public WbiSignatureException(String message) { super(message); }
    }

    public BilibiliMusicApiService(WebClient webClient, AppProperties appProperties, LocalCacheService localCacheService, BilibiliWbiService wbiService, BilibiliCookieService cookieService) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getBilibili().getBaseUrl();
        this.localCacheService = localCacheService;
        this.wbiService = wbiService;
        this.cookieService = cookieService;
    }

    private void ensureConfigured() {
        if (!cookieService.hasCookie()) {
            // 与网易云一致：Cookie 为可选，缺失仅导致 B站源不可用，不影响其他音乐源
            throw new ApiRequestException("尚未配置 Bilibili Cookie，B站源不可用（不影响其他音乐源）");
        }
    }

    public void updateCookie(String newCookie) {
        cookieService.updateCookie(newCookie);
        this.wbiService.invalidateCache(); // Cookie 变化后 WBI key 可能也需要刷新
        log.info("Bilibili API Service cookie updated.");
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    /** 构建带完整 Cookie（含设备指纹 buvid3/buvid4、bili_ticket）的请求。 */
    private Mono<WebClient.RequestHeadersSpec<?>> buildBilibiliRequest(String uri) {
        return cookieService.getCookieHeader()
                .map(cookie -> webClient.get()
                        .uri(uri)
                        .header("Cookie", cookie)
                        .header("Referer", "https://www.bilibili.com/"));
    }

    private Mono<Music> getVideoDetailsWithCookie(String bvid) {
        return cookieService.getCookieHeader()
                .flatMap(cookie -> BilibiliApiUtils.getVideoDetails(bvid, webClient, baseUrl, cookie));
    }

    private Mono<String> getVideoCidWithCookie(String bvid) {
        return cookieService.getCookieHeader()
                .flatMap(cookie -> BilibiliApiUtils.getVideoCid(bvid, webClient, baseUrl, cookie));
    }

    /** 一次 /view 调用同时取 CID 与视频元数据。 */
    private Mono<BilibiliApiUtils.BilibiliVideoInfo> getVideoInfoWithCookie(String bvid) {
        return cookieService.getCookieHeader()
                .flatMap(cookie -> BilibiliApiUtils.getVideoInfo(bvid, webClient, baseUrl, cookie));
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        ensureConfigured();
        // 1. 准备请求参数（严格按照文档要求的 type 搜索）
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");      // 默认第一页
        params.put("page_size", "20"); // 文档默认 20

        // 2. 先取完整 Cookie（buvid3 是搜索接口硬性要求，缺失会 -412），再做 WBI 签名
        Mono<List<Music>> requestMono = cookieService.getCookieHeader()
                .flatMap(cookie -> wbiService.signParams(params)
                        .flatMap(signedParams -> {
                            UriComponentsBuilder builder = UriComponentsBuilder
                                    .fromHttpUrl(baseUrl + "/x/web-interface/wbi/search/type");

                            signedParams.forEach(builder::queryParam);

                            return webClient.get()
                                    .uri(builder.build().toUri()) // 使用编码后的 URI
                                    .header("Cookie", cookie)
                                    .header("User-Agent", USER_AGENT)
                                    .header("Referer", "https://www.bilibili.com/") // 必须带 Referer
                                    .retrieve()
                                    .bodyToMono(JsonNode.class)
                                    .handle((json, sink) -> {
                                        int code = json.path("code").asInt();
                                        // 🟢 关键点 1: 检测 WBI 潜在的错误码
                                        // -403: 访问权限不足 (可能是签名挂了)
                                        // -400: 请求错误 (可能是参数/签名校验不过)
                                        if (code == -403 || code == -400) {
                                            sink.error(new WbiSignatureException("WBI signature invalid, code: " + code));
                                            return;
                                        }
                                        // 🟢 关键点 2: -412 请求被拦截（IP 被风控或 Cookie 凭据不足）
                                        if (code == -412) {
                                            sink.error(new ApiRequestException("请求被B站风控拦截(IP受限或凭据不足)，请稍后重试"));
                                            return;
                                        }

                                        // 其他常规错误，不重试，直接记录日志返回空列表
                                        if (code != 0) {
                                            log.error("Bilibili search failed: {}", json.path("message").asText());
                                            sink.next(new ArrayList<>());
                                            return;
                                        }


                                        List<Music> musicList = new ArrayList<>();

                                        JsonNode results = json.path("data").path("result");
                                        if (results.isArray()) {
                                            results.forEach(video -> {
                                                // 清洗标题中的 <em class="keyword">xxx</em> 标签
                                                String rawTitle = video.path("title").asText();
                                                String cleanTitle = rawTitle.replaceAll("<[^>]*>", "");

                                                // 处理时长
                                                String durationStr = video.path("duration").asText();
                                                long durationMs = BilibiliApiUtils.durationToMillis(durationStr);

                                                // 获取图片，统一转 https（search 返回协议相对 //，view 返回 http://，https 页面上 http 会被混合内容拦截）
                                                String picUrl = BilibiliApiUtils.normalizeCoverUrl(video.path("pic").asText());

                                                musicList.add(new Music(
                                                        video.path("bvid").asText(),
                                                        cleanTitle,
                                                        List.of(video.path("author").asText()),
                                                        durationMs,
                                                        PLATFORM,
                                                        picUrl));
                                            });
                                        }
                                        sink.next(musicList);
                                    });
                        }));

        // 添加重试机制
        return requestMono.retryWhen(Retry.max(1) // 最多重试 1 次
                        .filter(throwable -> throwable instanceof WbiSignatureException) // 只针对签名异常重试
                        .doBeforeRetry(retrySignal -> {
                            log.warn("Detected WBI signature error, refreshing key and retrying...");
                            wbiService.invalidateCache(); // 清除缓存
                        }))
                // 如果重试后还是失败，降级为空列表
                .onErrorResume(WbiSignatureException.class, e -> {
                    log.error("Bilibili search failed after retry: {}", e.getMessage());
                    return Mono.just(new ArrayList<>());
                });
    }

    @Override
    public void prefetchMusic(String bvid) {
        ensureConfigured();
        // 检查缓存状态，如果已经下载或正在下载，直接返回
        CacheStatus status = localCacheService.getStatus(bvid);
        if (status == CacheStatus.COMPLETED || status == CacheStatus.DOWNLOADING) {
            return;
        }

        log.info("Prefetching Bilibili music: {}", bvid);

        // 下载目标：DASH 音频优先；若 wbi/playurl 被 B站挑战拦截(HTTP 412)，退化为 html5 MP4
        Mono<LocalCacheService.DownloadSource> source = resolveDownloadTarget(bvid);

        // 准备请求头 (防盗链)：CDN 下载只需 Referer + UA，无需 Cookie。
        // 保持同步提交，避免异步 subscribe 造成同一 bvid 的 TOCTOU 双下载。
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com/video/" + bvid);
        headers.put("User-Agent", USER_AGENT);

        localCacheService.submitDownload(bvid, source, headers);
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String bvid) {
        ensureConfigured();
        // 1. 检查本地缓存
        String localUrl = localCacheService.getLocalUrl(bvid);

        if (localUrl != null) {
            // 2. 如果本地存在，直接返回静态资源路径
            // 此时 needsProxy = false，因为对于前端来说，这就是一个普通的 http 链接
            return getVideoDetailsWithCookie(bvid)
                    .map(music -> new PlayableMusic(
                            music.id(), music.name(), music.artists(), music.duration(),
                            PLATFORM, localUrl, music.coverUrl(), false // needsProxy = false
                    ));
        } else {
            // 3. 本地没有缓存（可能是下载失败，或者还没下载完就被强制切歌）
            CacheStatus status = localCacheService.getStatus(bvid);

            // 🟢 兜底：下载已失败时，改走 html5 直连 MP4（无 referer 鉴权，前端 <audio> 可直接播放）。
            //    同时后台重试一次下载，以便修复本地缓存。
            if (status == CacheStatus.FAILED) {
                log.warn("Bilibili download failed for {}, falling back to html5 direct MP4.", bvid);
                prefetchMusic(bvid); // 后台重试修复缓存，不阻塞播放
                // 一次 /view 同时取 cid + 元数据，再解析 html5 直链；兜底失败抛错让播放器跳过该曲
                return getVideoInfoWithCookie(bvid)
                        .flatMap(info -> resolveHtml5Mp4Url(bvid, info.cid())
                                .map(url -> new PlayableMusic(
                                        info.music().id(), info.music().name(), info.music().artists(),
                                        info.music().duration(), PLATFORM, url, info.music().coverUrl(), false
                                ))
                                .onErrorResume(e -> {
                                    log.error("Bilibili html5 fallback failed for {}: {}", bvid, e.getMessage());
                                    return Mono.error(new ApiRequestException("B站资源获取失败，无法播放：" + bvid));
                                }));
            }

            // 4. 未下载/下载中：触发一次预加载（如果任务不存在的话）
            prefetchMusic(bvid);

            // 即使在下载中，也返回元数据，但 URL 设为特殊值
            // 这样 MusicPlayerService.enqueue 就能拿到名字、封面等信息成功入队
            return getVideoDetailsWithCookie(bvid)
                    .map(music -> new PlayableMusic(
                            music.id(), music.name(), music.artists(), music.duration(),
                            PLATFORM, "PENDING_DOWNLOAD", music.coverUrl(), false
                    ));
        }
    }

    /**
     * 兜底方案：通过旧 playurl 接口获取 html5 直连 MP4 地址。
     *
     * <p>与 DASH 方案的区别：{@code platform=html5} 返回的 MP4 流<b>无 referer 鉴权</b>，
     * 前端 {@code <audio>} 元素可直接播放，无需服务器中转下载；且旧端点无需 WBI 签名。
     * {@code try_look=1} 可免登录取到 480P/720P 档位。</p>
     */
    private Mono<String> resolveHtml5Mp4Url(String bvid, String cid) {
        return cookieService.getCookieHeader()
                .flatMap(cookie -> {
                    String uri = UriComponentsBuilder
                            .fromHttpUrl(baseUrl + "/x/player/playurl")
                            .queryParam("bvid", bvid)
                            .queryParam("cid", cid)
                            .queryParam("fnval", "1")         // MP4
                            .queryParam("platform", "html5")  // 移动端 HTML5，无 referer 鉴权
                            .queryParam("high_quality", "1")  // 1080P
                            .queryParam("qn", "32")           // 480P（体积/音质折中）
                            .queryParam("try_look", "1")      // 免登录高画质
                            .build()
                            .toUriString();

                    return webClient.get()
                            .uri(uri)
                            .header("Cookie", cookie)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://www.bilibili.com/video/" + bvid)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .flatMap(json -> {
                                int code = json.path("code").asInt();
                                if (code != 0) {
                                    return Mono.error(new ApiRequestException("Bilibili html5 playurl failed, code: " + code));
                                }
                                JsonNode durl = json.path("data").path("durl");
                                if (!durl.isArray() || durl.isEmpty()) {
                                    return Mono.error(new ApiRequestException("No MP4 stream in html5 playurl response"));
                                }
                                String url = durl.get(0).path("url").asText();
                                if (!StringUtils.hasText(url)) {
                                    return Mono.error(new ApiRequestException("Empty MP4 url"));
                                }
                                return Mono.just(url);
                            });
                });
    }

    /**
     * 解析 DASH 音频直链。
     * <p>使用旧端点 {@code /x/player/playurl}（非 WBI）而非 {@code /x/player/wbi/playurl}：
     * 后者对自动化/爬虫 IP 有 HTTP 412 反爬挑战（返回 HTML），纯 HTTP 客户端无法通过；
     * 旧端点不受该挑战，且 {@code fnval=16} 即可返回完整 DASH 音频流（普通账号含 30280 192K，大会员账号含 30250/30251）。</p>
     */
    private Mono<String> resolveDashAudioUrl(String bvid, String cid) {
        return cookieService.getCookieHeader()
                .flatMap(cookie -> {
                    String uri = UriComponentsBuilder
                            .fromHttpUrl(baseUrl + "/x/player/playurl")
                            .queryParam("bvid", bvid)
                            .queryParam("cid", cid)
                            .queryParam("fnval", "16") // DASH
                            .build()
                            .toUriString();

                    return webClient.get()
                            .uri(uri)
                            .header("Cookie", cookie)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://www.bilibili.com/video/" + bvid)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .flatMap(jsonNode -> {
                                int code = jsonNode.path("code").asInt();
                                if (code != 0) {
                                    return Mono.error(new ApiRequestException("Bilibili playurl failed, code: " + code));
                                }
                                JsonNode audioStreams = jsonNode.path("data").path("dash").path("audio");
                                if (audioStreams.isMissingNode() || audioStreams.isEmpty()) {
                                    return Mono.error(new ApiRequestException("No DASH audio found"));
                                }
                                String url = pickBestAudioUrl(audioStreams);
                                if (url == null) {
                                    return Mono.error(new ApiRequestException("No audio url found in json"));
                                }
                                return Mono.just(url);
                            });
                });
    }

    /**
     * 下载目标解析：优先 DASH 音频（体积小、音质高）。
     * <p>当 {@code /x/player/wbi/playurl} 被 B站风控以 HTTP 412 挑战拦截（返回 HTML 而非 JSON）时，
     * 退化为 html5 MP4（免 WBI、无 referer 鉴权，稳定可取），保证本地缓存仍能填满。</p>
     */
    private Mono<LocalCacheService.DownloadSource> resolveDownloadTarget(String bvid) {
        return getVideoCidWithCookie(bvid)
                .flatMap(cid -> resolveDashAudioUrl(bvid, cid)
                        .map(url -> new LocalCacheService.DownloadSource(url, ".m4a"))
                        .onErrorResume(e -> {
                            log.warn("DASH audio resolution failed for {}, falling back to html5 MP4 download: {}", bvid, e.getMessage());
                            return resolveHtml5Mp4Url(bvid, cid)
                                    .map(url -> new LocalCacheService.DownloadSource(url, ".mp4"));
                        }));
    }

    /**
     * 选择最稳的音频流：优先固定档位 192K/132K/64K，兜底取 id 最大。
     */
    private static String pickBestAudioUrl(JsonNode audioStreams) {
        for (int prefId : PREFERRED_AUDIO_IDS) {
            for (JsonNode a : audioStreams) {
                if (a.path("id").asInt() == prefId) {
                    return a.path("baseUrl").asText();
                }
            }
        }
        JsonNode best = null;
        int bestId = -1;
        for (JsonNode a : audioStreams) {
            int id = a.path("id").asInt();
            if (id > bestId) {
                bestId = id;
                best = a;
            }
        }
        return best != null ? best.path("baseUrl").asText() : null;
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        ensureConfigured();
        // API: /x/v3/fav/folder/created/list-all
        // 参数: up_mid (目标用户ID)
        String favListApi = baseUrl + "/x/v3/fav/folder/created/list-all";

        // 构建 URI
        String uri = UriComponentsBuilder.fromHttpUrl(favListApi)
                .queryParam("up_mid", userId)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .flatMap(spec -> spec.retrieve().bodyToMono(JsonNode.class))
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
        ensureConfigured();
        int safeLimit = Math.min(limit, 20);

        int pageNumber = (offset / safeLimit) + 1;

        // API: /x/v3/fav/resource/list
        String favDetailApi = baseUrl + "/x/v3/fav/resource/list";

        String uri = UriComponentsBuilder.fromHttpUrl(favDetailApi)
                .queryParam("media_id", playlistId)
                .queryParam("ps", safeLimit)
                .queryParam("pn", pageNumber)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .flatMap(spec -> spec.retrieve().bodyToMono(JsonNode.class))
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
                                    BilibiliApiUtils.normalizeCoverUrl(media.path("cover").asText())
                            ));
                        });
                    }
                    return musicList;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        ensureConfigured();
        // 1. 准备 WBI 搜索参数
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "bili_user"); // 搜索用户类型
        params.put("keyword", keyword);
        // params.put("page", "1"); // 默认第1页，可选

        // 2. 先取完整 Cookie（buvid3 硬性要求），再做 WBI 签名
        return cookieService.getCookieHeader()
                .flatMap(cookie -> wbiService.signParams(params)
                        .flatMap(signedParams -> {
                            // 3. 构建 URL: /x/web-interface/wbi/search/type
                            UriComponentsBuilder builder = UriComponentsBuilder
                                    .fromHttpUrl(baseUrl + "/x/web-interface/wbi/search/type");

                            signedParams.forEach(builder::queryParam);

                            return webClient.get()
                                    .uri(builder.build().toUri())
                                    .header("Cookie", cookie)
                                    .header("User-Agent", USER_AGENT)
                                    .header("Referer", "https://www.bilibili.com/")
                                    .retrieve()
                                    .bodyToMono(JsonNode.class)
                                    .map(jsonNode -> {
                                        List<UserSearchResult> users = new ArrayList<>();

                                        int code = jsonNode.path("code").asInt();
                                        // 与 searchMusic 一致：-412 风控拦截直接报错，而非静默空列表
                                        if (code == -412) {
                                            throw new ApiRequestException("请求被B站风控拦截(IP受限或凭据不足)，请稍后重试");
                                        }
                                        if (code != 0) {
                                            log.error("Bilibili user search failed: {}", jsonNode.path("message").asText());
                                            return users;
                                        }

                                        JsonNode results = jsonNode.path("data").path("result");
                                        if (results.isArray()) {
                                            results.forEach(u -> {
                                                String pic = BilibiliApiUtils.normalizeCoverUrl(u.path("upic").asText());
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
                        }));
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        return Mono.just(""); // B站暂时不支持歌词
    }
}

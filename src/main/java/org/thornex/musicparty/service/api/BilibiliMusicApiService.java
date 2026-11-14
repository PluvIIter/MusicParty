package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.util.BilibiliApiUtils;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.util.retry.Retry;

import java.util.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.thornex.musicparty.media.*;
import org.thornex.musicparty.source.*;

@Service
@Slf4j
public class BilibiliMusicApiService implements IMusicApiService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final String baseUrl;
    private static final String PLATFORM = "bilibili";
    private final AtomicLong credentialRevision = new AtomicLong();
    private final java.util.Map<String, CachedVideoInfo> videoInfoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object videoInfoLock = new Object();

    /**
     * 同一 bvid 在同一个凭据版本下的 view 请求：既保存 60 秒结果，也保存尚未完成的在途请求，
     * 让入队（getMusic）与预取（resolveMedia）共享同一次上游调用。
     */
    private static final class CachedVideoInfo {
        private Mono<BilibiliApiUtils.BilibiliVideoInfo> result;
        private volatile java.time.Instant expiresAt;

        boolean isUsable() {
            java.time.Instant expiry = expiresAt;
            // 尚未完成（expiry==null）时同样复用，避免并发订阅各发一次请求。
            return expiry == null || expiry.isAfter(java.time.Instant.now());
        }
    }

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

    public BilibiliMusicApiService(WebClient webClient, AppProperties appProperties, BilibiliWbiService wbiService, BilibiliCookieService cookieService) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getBilibili().getBaseUrl();
        this.wbiService = wbiService;
        this.cookieService = cookieService;
    }

    public void updateCookie(String newCookie) {
        videoInfoCache.clear();
        cookieService.updateCookie(newCookie);
        credentialRevision.incrementAndGet();
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

    private Mono<String> getVideoCidWithCookie(String bvid) {
        return getVideoInfo(bvid).map(BilibiliApiUtils.BilibiliVideoInfo::cid);
    }

    /**
     * /x/web-interface/view 结果短缓存：入队（getMusic）与预取（resolveMedia）会各请求一次，
     * 缓存 60 秒可把同一首的 view 请求从 2 次降到 1 次；并发时合并同一个在途请求，
     * 避免两次同时打到上游、提高触发风控的概率。失败或空结果不留在缓存里，允许下次重试。
     */
    private Mono<BilibiliApiUtils.BilibiliVideoInfo> getVideoInfo(String bvid) {
        return Mono.defer(() -> {
            long revision = credentialRevision.get();
            String cacheKey = revision + ":" + bvid;
            synchronized (videoInfoLock) {
                CachedVideoInfo cached = videoInfoCache.get(cacheKey);
                if (cached != null && cached.isUsable()) return cached.result;
                if (videoInfoCache.size() >= 512) videoInfoCache.clear();
                CachedVideoInfo entry = new CachedVideoInfo();
                entry.result = cookieService.getCookieHeader()
                        .flatMap(cookie -> BilibiliApiUtils.getVideoInfo(bvid, webClient, baseUrl, cookie))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .doOnNext(info -> {
                            synchronized (videoInfoLock) {
                                // 迟到结果不能写进新凭据版本的缓存。
                                if (revision != credentialRevision.get()) videoInfoCache.remove(cacheKey, entry);
                                else entry.expiresAt = java.time.Instant.now().plusSeconds(60);
                            }
                        })
                        .doOnError(error -> {
                            synchronized (videoInfoLock) { videoInfoCache.remove(cacheKey, entry); }
                        })
                        .doOnSuccess(info -> {
                            if (info == null) synchronized (videoInfoLock) { videoInfoCache.remove(cacheKey, entry); }
                        })
                        .cache();
                videoInfoCache.put(cacheKey, entry);
                return entry.result;
            }
        });
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        // 1. 准备请求参数（严格按照文档要求的 type 搜索）
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");      // 默认第一页
        params.put("page_size", "20"); // 文档默认 20

        // 2. 先取完整 Cookie（buvid3 是搜索接口硬性要求，缺失会 -412），再做 WBI 签名
        Mono<List<Music>> requestMono = cookieService.getCookieHeader()
                .flatMap(cookie -> wbiService.signParams(params, cookie)
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
                                            sink.error(new ApiRequestException("B站搜索暂不可用"));
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
                                                        picUrl, externalUrl(video.path("bvid").asText())));
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
                    return Mono.error(new ApiRequestException("B站搜索签名失败，请稍后重试"));
                })
                // HTTP 级风控挑战（HTTP 412/403 + HTML 挑战页、或 200 但正文非 JSON → DecodingException）
                // 会逃过上方 JSON body 的 code:-412 判断，漏到通用 500；这里捕获转成友好 502
                .onErrorResume(e -> isRiskControlChallenge(e), e -> riskControlFallback(e))
                // 网络层失败（DNS 解析失败 / 连接失败 / 超时，由 WebClientRequestException 包装）也会漏到通用 500；
                // 常见于 Netty 在 Windows 读不到系统 DNS 回退 Google 解析失败，这里捕获转成友好 502
                .onErrorResume(e -> isNetworkFailure(e), e -> networkFailureFallback(e));
    }

    @Override
    public SourceDescriptor descriptor() {
        return new SourceDescriptor(PLATFORM, "哔哩哔哩", "BILIBILI", "video",
                new SourceCapabilities(true, false, false, true, true, false, true, true),
                Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD),
                List.of(new CollectionCategory("favorites", "收藏夹", "favorites", "CONTAINER")));
    }

    @Override
    public DeliveryMode defaultDeliveryMode() { return DeliveryMode.SERVER_DOWNLOAD; }

    @Override
    public long credentialRevision() { return credentialRevision.get(); }

    @Override
    public String externalUrl(String id) {
        return "https://www.bilibili.com/video/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public Mono<Music> getMusic(String bvid) {
        return getVideoInfo(bvid).map(BilibiliApiUtils.BilibiliVideoInfo::music)
                .map(music -> new Music(music.id(), music.name(), music.artists(), music.duration(), music.platform(), music.coverUrl(), externalUrl(bvid)));
    }

    @Override
    public Mono<ResolvedMedia> resolveMedia(String bvid, MediaConsumer consumer) {
        return Mono.defer(() -> getVideoCidWithCookie(bvid).flatMap(cid -> {
            if (consumer == MediaConsumer.BROWSER) {
                return resolveHtml5Mp4Url(bvid, cid).map(source -> new ResolvedMedia(
                        new ResourceKey(PLATFORM, bvid), "html5", BilibiliApiUtils.normalizeCoverUrl(source.url()), "video/mp4", ".mp4",
                        Instant.now().plusSeconds(600), Map.of(), true, source.timeline()));
            }
            return resolveDownloadTarget(bvid, cid).map(source -> new ResolvedMedia(
                    new ResourceKey(PLATFORM, bvid), source.extension().equals(".mp4") ? "html5" : "dash",
                    source.url(), source.extension().equals(".mp4") ? "video/mp4" : "audio/mp4", source.extension(),
                    Instant.now().plusSeconds(600), Map.of("Referer", "https://www.bilibili.com/", "User-Agent", USER_AGENT), false, source.timeline()));
        }));
    }

    /**
     * 下载降级表示：主表示（DASH 或解析兜底的 html5 MP4）下载失败时，改用
     * {@code platform=html5} 的直连 MP4 再次由服务端下载。该 URL 无 referer 鉴权，
     * 因此请求头留空；依旧走本地缓存与票据，不向浏览器暴露上游地址。
     */
    @Override
    public Mono<ResolvedMedia> resolveFallbackMedia(String bvid) {
        return getVideoCidWithCookie(bvid).flatMap(cid -> resolveHtml5Mp4Url(bvid, cid)
                .map(source -> new ResolvedMedia(new ResourceKey(PLATFORM, bvid),
                        "html5:" + source.timeline(), source.url(), "video/mp4", ".mp4",
                        Instant.now().plusSeconds(600), Map.of(), true, source.timeline())));
    }

    /**
     * 兜底方案：通过旧 playurl 接口获取 html5 直连 MP4 地址。
     *
     * <p>与 DASH 方案的区别：{@code platform=html5} 返回的 MP4 流<b>无 referer 鉴权</b>，
     * 前端 {@code <audio>} 元素可直接播放，无需服务器中转下载；且旧端点无需 WBI 签名。
     * {@code try_look=1} 可免登录取到 480P/720P 档位。</p>
     */
    private record MediaUrl(String url, String extension, String timeline) {}

    private String timeline(String cid, JsonNode data) {
        return "cid:" + cid + ":duration:" + data.path("timelength").asText("")
                + ":preview:" + data.path("is_preview").asBoolean(false);
    }

    private Mono<MediaUrl> resolveHtml5Mp4Url(String bvid, String cid) {
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
                                return Mono.just(new MediaUrl(url, ".mp4", timeline(cid, json.path("data"))));
                            });
                });
    }

    /**
     * 解析 DASH 音频直链。
     * <p>使用旧端点 {@code /x/player/playurl}（非 WBI）而非 {@code /x/player/wbi/playurl}：
     * 后者对自动化/爬虫 IP 有 HTTP 412 反爬挑战（返回 HTML），纯 HTTP 客户端无法通过；
     * 旧端点不受该挑战，且 {@code fnval=16} 即可返回完整 DASH 音频流（普通账号含 30280 192K，大会员账号含 30250/30251）。</p>
     */
    private Mono<MediaUrl> resolveDashAudioUrl(String bvid, String cid) {
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
                                return Mono.just(new MediaUrl(url, ".m4a", timeline(cid, jsonNode.path("data"))));
                            });
                });
    }

    /**
     * 下载目标解析：优先 DASH 音频（体积小、音质高）。
     * <p>当 {@code /x/player/wbi/playurl} 被 B站风控以 HTTP 412 挑战拦截（返回 HTML 而非 JSON）时，
     * 退化为 html5 MP4（免 WBI、无 referer 鉴权，稳定可取），保证本地缓存仍能填满。</p>
     */
    private Mono<MediaUrl> resolveDownloadTarget(String bvid, String cid) {
        return resolveDashAudioUrl(bvid, cid).onErrorResume(error -> {
            log.warn("DASH audio unavailable; resolving html5 MP4 for server download");
            return resolveHtml5Mp4Url(bvid, cid);
        });
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
    public Mono<SourcePage<Playlist>> publicCollections(String userId, String category, int offset, int limit) {
        if (!"favorites".equals(category)) return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 收藏类别不存在"));
        if (offset < 0 || limit < 1) return Mono.error(new ApiRequestException("INVALID_CURSOR: 无效分页参数"));
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
                        log.warn("Failed to get Bilibili favorites for user {}: {}", userId, jsonNode.path("message").asText());
                        throw new ApiRequestException("收藏夹不可读取");
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
                    int start = Math.min(offset, playlists.size());
                    int end = Math.min(start + limit, playlists.size());
                    return new SourcePage<>(playlists.subList(start, end), end < playlists.size() ? end : null, (long) playlists.size());
                });
    }

    @Override
    public Mono<Playlist> containerDetail(String type, String id) {
        if (!"favorites".equals(type)) return IMusicApiService.super.containerDetail(type, id);
        String uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/x/v3/fav/resource/list")
                .queryParam("media_id", id).queryParam("ps", 1).queryParam("pn", 1).toUriString();
        return buildBilibiliRequest(uri).flatMap(spec -> spec.retrieve().bodyToMono(JsonNode.class)).map(json -> {
            if (json.path("code").asInt() != 0 || !json.path("data").path("info").isObject()) {
                throw new ApiRequestException("COLLECTION_NOT_PUBLIC: 收藏夹不可读取");
            }
            JsonNode info = json.path("data").path("info");
            return new Playlist(info.path("id").asText(id), info.path("title").asText(),
                    BilibiliApiUtils.normalizeCoverUrl(info.path("cover").asText()),
                    info.path("media_count").asInt(), PLATFORM);
        });
    }

    @Override
    public Mono<SourcePage<Music>> containerPage(String type, String id, int offset, int limit) {
        if (!"favorites".equals(type)) return IMusicApiService.super.containerPage(type, id, offset, limit);
        int pageSize = Math.min(limit, 20);
        if (pageSize < 1 || offset < 0 || offset % pageSize != 0) {
            return Mono.error(new ApiRequestException("INVALID_CURSOR: 无效的收藏夹分页位置"));
        }
        int pageNumber = offset / pageSize + 1;

        // API: /x/v3/fav/resource/list
        String favDetailApi = baseUrl + "/x/v3/fav/resource/list";

        String uri = UriComponentsBuilder.fromHttpUrl(favDetailApi)
                .queryParam("media_id", id)
                .queryParam("ps", pageSize)
                .queryParam("pn", pageNumber)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .flatMap(spec -> spec.retrieve().bodyToMono(JsonNode.class))
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();

                    int code = jsonNode.path("code").asInt();
                    if (code != 0) {
                        if (code == -404) throw new ApiRequestException("收藏夹不可读取");
                        log.error("Failed to get Bilibili favorite details: {}", jsonNode.path("message").asText());
                        throw new ApiRequestException("收藏夹不可读取");
                    }

                    JsonNode medias = jsonNode.path("data").path("medias");
                    // 注意：如果是空文件夹，medias可能是 null
                    if (medias.isArray()) {
                        medias.forEach(media -> {
                            String title = media.path("title").asText();
                            // 过滤失效视频
                            if ("已失效视频".equals(title)) return;

                            // 构造 Music 对象
                            musicList.add(new Music(
                                    media.path("bvid").asText(),
                                    title,
                                    List.of(media.path("upper").path("name").asText()),
                                    media.path("duration").asLong() * 1000,
                                    PLATFORM,
                                    BilibiliApiUtils.normalizeCoverUrl(media.path("cover").asText()),
                                    externalUrl(media.path("bvid").asText())));
                        });
                    }
                    int received = medias.isArray() ? medias.size() : 0;
                    boolean hasMore = jsonNode.path("data").has("has_more")
                            ? jsonNode.path("data").path("has_more").asBoolean() : received == pageSize;
                    // pn/ps 要求游标按整页前进，不能由返回或过滤后的条目数推导。
                    Integer nextOffset = hasMore && received > 0 ? offset + pageSize : null;
                    return new SourcePage<>(musicList, nextOffset, null);
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        // 1. 准备 WBI 搜索参数
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "bili_user"); // 搜索用户类型
        params.put("keyword", keyword);
        // params.put("page", "1"); // 默认第1页，可选

        // 2. 先取完整 Cookie（buvid3 硬性要求），再做 WBI 签名
        return cookieService.getCookieHeader()
                .flatMap(cookie -> wbiService.signParams(params, cookie)
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
                                            throw new ApiRequestException("B站用户搜索暂不可用");
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
                        }))
                // 与 searchMusic 一致：HTTP 级风控挑战漏到 500，这里捕获转成友好 502
                .onErrorResume(e -> isRiskControlChallenge(e), e -> riskControlFallback(e))
                // 与 searchMusic 一致：网络层失败（DNS/连接/超时）漏到 500，这里捕获转成友好 502
                .onErrorResume(e -> isNetworkFailure(e), e -> networkFailureFallback(e));
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        return Mono.just(""); // B站暂时不支持歌词
    }

    /**
     * 判断异常是否为 B站 HTTP 级风控挑战：
     * <ul>
     *   <li>{@link WebClientResponseException} 且状态码 412/403（B站对风控 IP 返回 HTTP 412 + HTML 挑战页）；</li>
     *   <li>{@link DecodingException}（挑战页正文不是 JSON，bodyToMono(JsonNode) 解码失败）。</li>
     * </ul>
     * 这类异常不会产生 JSON body 的 {@code code:-412}，因此走不到上方 sink.error 的 -412 判断，需要在此兜底。
     */
    static boolean isRiskControlChallenge(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 412 || status == 403;
        }
        return e instanceof DecodingException;
    }

    /** 风控挑战统一转成友好 502（与 JSON code:-412 文案一致），并保留真实异常日志供排查。 */
    static <T> Mono<T> riskControlFallback(Throwable e) {
        log.warn("Bilibili request blocked by risk control");
        return Mono.error(new ApiRequestException("请求被B站风控拦截(IP受限或凭据不足)，请稍后重试"));
    }

    /** 判断异常是否为网络层失败：DNS 解析失败 / 连接失败 / 超时等，由 {@link WebClientRequestException} 包装。 */
    static boolean isNetworkFailure(Throwable e) {
        return e instanceof WebClientRequestException;
    }

    /** 网络层失败统一转成友好 502（提示检查网络/DNS），并保留真实异常日志供排查。 */
    static <T> Mono<T> networkFailureFallback(Throwable e) {
        log.warn("Bilibili network request failed");
        return Mono.error(new ApiRequestException("无法连接B站服务器，请检查网络/DNS配置后重试"));
    }
}

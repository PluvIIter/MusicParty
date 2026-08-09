package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * B站 WBI 签名服务。
 *
 * <p>优先复用 {@link BilibiliCookieService} 从 bili_ticket 响应中收割的 WBI key，
 * 仅在无新鲜 key（每日更替）时退化为请求 {@code /x/web-interface/nav} 获取。</p>
 */
@Service
@Slf4j
public class BilibiliWbiService {

    private final WebClient webClient;
    private final BilibiliCookieService cookieService;

    // 混淆表
    private static final int[] MIXIN_TABLE = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    // 缓存密钥，避免每次请求都去拿 nav
    private final Map<String, String> keyCache = new ConcurrentHashMap<>();
    private long lastCacheTime = 0;
    private static final long CACHE_DURATION = 1000 * 60 * 60; // 1小时刷新一次

    public BilibiliWbiService(WebClient webClient, BilibiliCookieService cookieService) {
        this.webClient = webClient;
        this.cookieService = cookieService;
    }

    /**
     * 获取最新的 WBI 密钥。
     * 优先使用 bili_ticket 响应中收割的 key；否则请求 nav 接口（带完整 Cookie）。
     */
    private Mono<String> getMixinKey() {
        if (System.currentTimeMillis() - lastCacheTime < CACHE_DURATION && keyCache.containsKey("mixin_key")) {
            return Mono.just(keyCache.get("mixin_key"));
        }

        return cookieService.getCookieHeader()
                .flatMap(cookie -> {
                    // ① 优先使用 bili_ticket 收割的 WBI key
                    Optional<BilibiliCookieService.WbiKeys> fresh = cookieService.getFreshWbiKeys();
                    if (fresh.isPresent()) {
                        String mixinKey = computeMixinKey(fresh.get().imgKey(), fresh.get().subKey());
                        keyCache.put("mixin_key", mixinKey);
                        lastCacheTime = System.currentTimeMillis();
                        return Mono.just(mixinKey);
                    }
                    // ② 退化为请求 nav 接口（key 每日更替，ticket 收割可能已过期）
                    return fetchMixinKeyFromNav(cookie);
                });
    }

    private Mono<String> fetchMixinKeyFromNav(String cookie) {
        return webClient.get()
                .uri("https://api.bilibili.com/x/web-interface/nav")
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    JsonNode wbiImg = json.path("data").path("wbi_img");
                    String imgUrl = wbiImg.path("img_url").asText();
                    String subUrl = wbiImg.path("sub_url").asText();
                    String mixinKey = computeMixinKey(getFileName(imgUrl), getFileName(subUrl));
                    keyCache.put("mixin_key", mixinKey);
                    lastCacheTime = System.currentTimeMillis();
                    return mixinKey;
                })
                // nav 也是 WBI 关键端点，风控 IP 会在这里被 HTTP 412 挑战拦截；
                // 与搜索链路一致兜底转友好 502，避免漏到通用 500
                .onErrorResume(e -> BilibiliMusicApiService.isRiskControlChallenge(e),
                        e -> BilibiliMusicApiService.<String>riskControlFallback(e));
    }

    /**
     * 由 img_key + sub_key 计算 mixin_key（截取前 32 位）。
     */
    private static String computeMixinKey(String imgKey, String subKey) {
        String rawKey = imgKey + subKey;
        StringBuilder mixinKey = new StringBuilder();
        for (int i : MIXIN_TABLE) {
            if (i < rawKey.length()) {
                mixinKey.append(rawKey.charAt(i));
            }
        }
        return mixinKey.substring(0, 32);
    }

    private static String getFileName(String url) {
        String[] parts = url.split("/");
        String file = parts[parts.length - 1];
        return file.split("\\.")[0];
    }

    /**
     * 对参数进行 WBI 加签
     */
    public Mono<Map<String, String>> signParams(Map<String, String> params) {
        return getMixinKey().map(mixinKey -> {
            TreeMap<String, String> sortedParams = new TreeMap<>(params);
            sortedParams.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

            // 过滤非法字符
            String query = sortedParams.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));

            String s = query + mixinKey;
            String wRid = md5(s);
            sortedParams.put("w_rid", wRid);
            return sortedParams;
        });
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public void invalidateCache() {
        keyCache.remove("mixin_key");
        lastCacheTime = 0;
        // 同时清除 bili_ticket 收割的 WBI key：
        // 否则 -403 重试期间 getFreshWbiKeys() 仍返回旧 key，nav 兜底在 24h 有效期内不可达
        cookieService.invalidateWbiKeys();
        log.info("WBI Key cache invalidated due to retry mechanism.");
    }
}

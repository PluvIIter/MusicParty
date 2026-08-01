package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * B站反风控凭据服务。
 *
 * <p>集中维护请求所需的 Cookie，依据 bilibili-API-collect 文档：</p>
 * <ul>
 *   <li>搜索等接口要求 Cookie 中含 {@code buvid3} 设备指纹，否则会返回 {@code -412} 搜索被拦截；</li>
 *   <li>{@code bili_ticket} 非必需，但存在可显著降低风控概率；</li>
 *   <li>{@code bili_ticket} 响应的 {@code nav} 字段即为最新 WBI {@code img_key}/{@code sub_key}，
 *       可顺带收割，避免单独请求 nav 接口。</li>
 * </ul>
 *
 * <p>所有对外请求应通过 {@link #getCookieHeader()} 取得完整 Cookie 头。
 * 完整 Cookie 由 {@code BILIBILI_COOKIE} 配置提供（可选，不填仅 B站源不可用，不影响其他源）。
 * 运行期会定期刷新其中的 {@code bili_ticket}（3 天过期）并从响应收割最新 WBI key。</p>
 */
@Service
@Slf4j
public class BilibiliCookieService {

    private final WebClient webClient;

    /** 完整 Cookie 串（来自 BILIBILI_COOKIE 配置，可选），含 buvid3/SESSDATA/bili_jct/_uuid 等。 */
    private volatile String fullCookie;

    // ---- 设备指纹 buvid3 / buvid4 / b_nut ----
    private static final long FINGERPRINT_RETRY_MIN_MS = 30_000;
    private volatile String buvid3;
    private volatile String buvid4;
    private volatile String bNut;
    private volatile boolean fingerprintReady;
    private volatile long lastFingerprintAttempt;
    private Mono<Void> fingerprintInit;

    // ---- bili_ticket（有效期 3 天，每 2 天刷新） ----
    private static final String TICKET_SECRET = "XgwSnGZ1p";
    private static final long TICKET_REFRESH_MS = 2L * 24 * 3600 * 1000;
    private static final long TICKET_RETRY_MIN_MS = 60_000;
    private volatile String biliTicket;
    private volatile long ticketFetchTime;
    private volatile long lastTicketAttempt;
    private Mono<Void> ticketInit;

    // ---- 从 bili_ticket 响应收割的 WBI key（文档称每日更替） ----
    private static final long WBI_KEY_VALIDITY_MS = 24L * 3600 * 1000;
    private volatile String imgKey;
    private volatile String subKey;
    private volatile long wbiKeyTime;

    private final Object initLock = new Object();

    public record WbiKeys(String imgKey, String subKey) {}

    public BilibiliCookieService(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        String full = appProperties.getBilibili().getCookie();
        if (StringUtils.hasText(full)) {
            this.fullCookie = full;
            parseFullCookie(full);
        }
    }

    /**
     * 从完整 Cookie 中提取关键字段，使 getBuvid3 / getCookieHeader 等直接可用，无需再走 spi 合成。
     */
    private void parseFullCookie(String cookie) {
        for (String part : cookie.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            switch (name) {
                case "buvid3" -> this.buvid3 = value;
                case "buvid4" -> this.buvid4 = value;
                case "b_nut" -> this.bNut = value;
                case "bili_ticket" -> {
                    this.biliTicket = value;
                    this.ticketFetchTime = System.currentTimeMillis();
                }
                default -> { }
            }
        }
        this.fingerprintReady = StringUtils.hasText(buvid3);
    }

    /**
     * 管理员热更新完整 Cookie（AdminController 调用）。
     */
    public void updateCookie(String newCookie) {
        this.fullCookie = null;
        this.buvid3 = null;
        this.buvid4 = null;
        this.bNut = null;
        this.biliTicket = null;
        this.fingerprintReady = false;
        if (StringUtils.hasText(newCookie)) {
            this.fullCookie = newCookie;
            parseFullCookie(newCookie);
        }
        log.info("Bilibili Cookie Service cookie updated.");
    }

    public boolean hasCookie() {
        return StringUtils.hasText(fullCookie);
    }

    /** 完整 Cookie 头，确保设备指纹与 bili_ticket 已初始化（惰性 + 并发去重）。 */
    public Mono<String> getCookieHeader() {
        return ensureFingerprint()
                .then(ensureTicket())
                .then(Mono.defer(() -> Mono.just(buildCookie())));
    }

    /** 若最近从 bili_ticket 响应中收割到新鲜 WBI key，则返回之；否则为空。 */
    public Optional<WbiKeys> getFreshWbiKeys() {
        if (StringUtils.hasText(imgKey) && StringUtils.hasText(subKey)
                && System.currentTimeMillis() - wbiKeyTime < WBI_KEY_VALIDITY_MS) {
            return Optional.of(new WbiKeys(imgKey, subKey));
        }
        return Optional.empty();
    }

    /** 清除从 bili_ticket 收割的 WBI key，用于 WBI 签名失败时强制回退到 nav 重新获取。 */
    public void invalidateWbiKeys() {
        this.imgKey = null;
        this.subKey = null;
        this.wbiKeyTime = 0;
        log.info("Bilibili harvested WBI keys invalidated.");
    }

    public String getBuvid3() {
        return buvid3;
    }

    // ==================== 设备指纹 ====================

    private Mono<Void> ensureFingerprint() {
        if (fingerprintReady) return Mono.empty();
        synchronized (initLock) {
            if (fingerprintReady) return Mono.empty();
            if (fingerprintInit == null) {
                long now = System.currentTimeMillis();
                if (now - lastFingerprintAttempt < FINGERPRINT_RETRY_MIN_MS) {
                    return Mono.empty(); // 失败冷却期内不再尝试，降级请求
                }
                lastFingerprintAttempt = now;
                fingerprintInit = fetchFingerprint()
                        .doOnSuccess(v -> fingerprintReady = true)
                        .onErrorResume(e -> {
                            log.warn("Bilibili device fingerprint init failed: {}", e.getMessage());
                            return Mono.empty(); // 降级：无指纹也能请求，只是风控概率升高
                        })
                        .doFinally(sig -> {
                            if (!fingerprintReady) {
                                synchronized (initLock) { fingerprintInit = null; } // 失败则冷却后重试
                            }
                        })
                        .cache();
            }
            return fingerprintInit;
        }
    }

    private Mono<Void> fetchFingerprint() {
        return webClient.get()
                .uri("https://api.bilibili.com/x/frontend/finger/spi")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnNext(json -> {
                    JsonNode data = json.path("data");
                    String b3 = data.path("b_3").asText();
                    String b4 = data.path("b_4").asText();
                    if (StringUtils.hasText(b3)) {
                        this.buvid3 = b3;
                        this.buvid4 = StringUtils.hasText(b4) ? b4 : "";
                        // 文档：无 Cookie 请求时 b_nut 为响应生成时刻的 UNIX 秒级时间戳
                        this.bNut = String.valueOf(System.currentTimeMillis() / 1000);
                        log.info("Bilibili device fingerprint acquired: buvid3={}", buvid3);
                    } else {
                        // 抛错走 onErrorResume 降级路径：fingerprintReady 保持 false，冷却后重试
                        throw new IllegalStateException("Bilibili fingerprint spi returned no buvid3");
                    }
                })
                // 防挂起：无响应超时会污染缓存的 fingerprintInit，导致后续 getCookieHeader 永久卡死
                .timeout(Duration.ofSeconds(10))
                .then();
    }

    // ==================== bili_ticket ====================

    private Mono<Void> ensureTicket() {
        if (ticketFresh()) return Mono.empty();
        synchronized (initLock) {
            if (ticketInit == null) {
                long now = System.currentTimeMillis();
                if (now - lastTicketAttempt < TICKET_RETRY_MIN_MS) {
                    return Mono.empty(); // 失败冷却期内不再尝试
                }
                lastTicketAttempt = now;
                ticketInit = fetchTicket()
                        .onErrorResume(e -> {
                            log.warn("bili_ticket generation failed: {}", e.getMessage());
                            return Mono.empty(); // 降级
                        })
                        .doFinally(sig -> {
                            // 无论成败都清空 ticketInit：
                            //  成功 → 由顶部 ticketFresh() 门控短路，过期后能重新进入本段刷新；
                            //  失败 → 由冷却时间（TICKET_RETRY_MIN_MS）避免频繁重试。
                            synchronized (initLock) { ticketInit = null; }
                        })
                        .cache();
            }
            return ticketInit;
        }
    }

    private boolean ticketFresh() {
        return StringUtils.hasText(biliTicket)
                && System.currentTimeMillis() - ticketFetchTime < TICKET_REFRESH_MS;
    }

    private Mono<Void> fetchTicket() {
        long ts = System.currentTimeMillis() / 1000;
        String hexSign = hmacSha256Hex(TICKET_SECRET, "ts" + ts);
        String uri = UriComponentsBuilder
                .fromHttpUrl("https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket")
                .queryParam("key_id", "ec02")
                .queryParam("hexsign", hexSign)
                .queryParam("context[ts]", ts)
                .queryParam("csrf", "")
                .build()
                .toUriString();

        return webClient.post()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnNext(json -> {
                    if (json.path("code").asInt() == 0) {
                        String ticket = json.path("data").path("ticket").asText();
                        if (StringUtils.hasText(ticket)) {
                            this.biliTicket = ticket;
                            this.ticketFetchTime = System.currentTimeMillis();
                            log.info("bili_ticket acquired (valid for 3 days).");
                            // 收割 WBI img/sub key（文档：ticket 响应的 nav 即最新 key）
                            String img = json.path("data").path("nav").path("img").asText();
                            String sub = json.path("data").path("nav").path("sub").asText();
                            if (StringUtils.hasText(img) && StringUtils.hasText(sub)) {
                                this.imgKey = getFileName(img);
                                this.subKey = getFileName(sub);
                                this.wbiKeyTime = System.currentTimeMillis();
                                log.info("WBI keys harvested from bili_ticket response.");
                            }
                        } else {
                            log.warn("bili_ticket response missing ticket field");
                        }
                    } else {
                        log.warn("bili_ticket generation failed, code: {}", json.path("code").asInt());
                    }
                })
                // 防挂起：无响应超时会污染缓存的 ticketInit
                .timeout(Duration.ofSeconds(10))
                .then();
    }

    // ==================== 工具 ====================

    private String buildCookie() {
        // 以完整 Cookie 为基础，运行期刷新 bili_ticket 后替换回，其余字段保持浏览器原样
        String base = fullCookie;
        if (StringUtils.hasText(biliTicket)) {
            base = replaceCookie(base, "bili_ticket", biliTicket);
        }
        return base != null ? base : "";
    }

    /** 替换（或追加）Cookie 串中指定键的值。 */
    private static String replaceCookie(String cookie, String name, String value) {
        String token = name + "=";
        String replacement = token + value;
        if (cookie.contains(token)) {
            return cookie.replaceAll(token + "[^;]*", java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return cookie + "; " + replacement;
    }

    private static String getFileName(String url) {
        String[] parts = url.split("/");
        String file = parts[parts.length - 1];
        return file.split("\\.")[0];
    }

    private static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}

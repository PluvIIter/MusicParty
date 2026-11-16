package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 音乐凭据：与网易云源保持一致，按原始 Cookie 字符串配置。
 *
 * <p>上游 {@code web/src/core/auth.py} 从请求 Cookie 读 {@code musicid}/{musickey}
 * （其余字段可选），而 y.qq.com 里复制的 Cookie 用的是 {@code uin}/{@code qm_keyst}
 * （或 {@code qqmusic_key}）。这里统一做一次映射，让「直接粘贴浏览器 Cookie」可用；
 * 已经带 {@code musicid}/{@code musickey} 的串保持原样（幂等）。</p>
 */
@Slf4j
@Service
public class QqMusicCredentialService {
    private static final int MAXIMUM_LENGTH = 8192;
    private final AppProperties properties;
    private final AtomicLong revision = new AtomicLong();
    private volatile String cookie;

    public QqMusicCredentialService(AppProperties properties) {
        this.properties = properties;
        String configured = properties.getQqmusic().getCookie();
        // 旧版把凭据写成 JSON；现在只接受 Cookie 串，部署配置降级为匿名但明确记录原因。
        if (configured != null && configured.strip().startsWith("{")) {
            log.warn("QQ Music credential uses the removed JSON format and is ignored; configure a Cookie header instead");
            configured = "";
        }
        // 部署配置里的非法值只按未配置处理，不阻断其他音源启动。
        this.cookie = configured == null || !isValid(configured.strip()) ? "" : normalize(configured);
    }

    public String cookie() {
        return cookie;
    }

    public boolean configured() {
        return !cookie.isBlank();
    }

    public long revision() {
        return revision.get();
    }

    public synchronized void updateCookie(String value) {
        // 先去掉首尾空白（粘贴常见），再校验：行尾换行可以接受，内嵌控制字符仍然拒绝。
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("{")) {
            throw new ApiRequestException("INVALID_CREDENTIAL: QQ音乐凭据已改为 Cookie 串，请粘贴浏览器 Cookie");
        }
        if (!isValid(normalized)) throw new ApiRequestException("INVALID_CREDENTIAL: QQ音乐Cookie格式无效");
        String canonical = normalize(normalized);
        if (canonical.equals(cookie)) return;
        cookie = canonical;
        properties.getQqmusic().setCookie(canonical);
        revision.incrementAndGet();
    }

    /**
     * 把浏览器 Cookie 规范成上游需要的形态：缺 {@code musicid}/{@code musickey} 时，
     * 用 {@code uin}/{@code qm_keyst}（或 {@code qqmusic_key}）补上，并顺带映射可选的登录态字段。
     * 缺少必要字段时原样返回（匿名）。
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String stripped = raw.strip();
        if (stripped.isEmpty()) return "";
        Map<String, String> pairs = parse(stripped);
        if (hasText(pairs.get("musicid")) && hasText(pairs.get("musickey"))) return join(pairs);
        String musicId = firstText(pairs.get("uin"), pairs.get("wxuin"));
        String musicKey = firstText(pairs.get("qm_keyst"), pairs.get("qqmusic_key"));
        if (!hasText(musicId) || !hasText(musicKey)) return join(pairs);
        pairs.putIfAbsent("musicid", musicId);
        pairs.putIfAbsent("musickey", musicKey);
        putIfMissing(pairs, "openid", pairs.get("psrf_qqopenid"), pairs.get("wxopenid"));
        putIfMissing(pairs, "refresh_token", pairs.get("psrf_qqrefresh_token"), pairs.get("wxrefresh_token"));
        putIfMissing(pairs, "access_token", pairs.get("psrf_qqaccess_token"));
        putIfMissing(pairs, "expired_at", pairs.get("psrf_access_token_expiresAt"));
        return join(pairs);
    }

    private static Map<String, String> parse(String value) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String part : value.split(";")) {
            String entry = part.strip();
            if (entry.isEmpty()) continue;
            int equals = entry.indexOf('=');
            if (equals <= 0) continue;
            pairs.putIfAbsent(entry.substring(0, equals).strip(), entry.substring(equals + 1).strip());
        }
        return pairs;
    }

    private static String join(Map<String, String> pairs) {
        StringBuilder builder = new StringBuilder();
        pairs.forEach((name, value) -> {
            if (builder.length() > 0) builder.append("; ");
            builder.append(name).append('=').append(value);
        });
        return builder.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstText(String... values) {
        for (String value : values) if (hasText(value)) return value;
        return null;
    }

    private static void putIfMissing(Map<String, String> pairs, String name, String... candidates) {
        if (pairs.containsKey(name)) return;
        String value = firstText(candidates);
        if (value != null) pairs.put(name, value);
    }

    private static boolean isValid(String value) {
        if (value == null) return true;
        return value.length() <= MAXIMUM_LENGTH
                && value.chars().noneMatch(character -> character < 32 || character == 127);
    }
}

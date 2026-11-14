package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 音乐凭据：与网易云源保持一致，按原始 Cookie 字符串配置并原样转发给上游 Web.
 *
 * <p>上游 {@code web/src/core/auth.py} 直接从请求 Cookie 读取
 * {@code musicid}/{musickey}（其余字段可选），因此这里不再解析 JSON。</p>
 */
@Service
public class QqMusicCredentialService {
    private static final int MAXIMUM_LENGTH = 8192;
    private final AppProperties properties;
    private final AtomicLong revision = new AtomicLong();
    private volatile String cookie;

    public QqMusicCredentialService(AppProperties properties) {
        this.properties = properties;
        // 部署配置里的非法值只按未配置处理，不阻断其他音源启动。
        String configured = properties.getQqmusic().getCookie();
        this.cookie = configured == null || !isValid(configured) ? "" : configured.strip();
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
        String raw = value == null ? "" : value;
        if (!isValid(raw)) throw new ApiRequestException("INVALID_CREDENTIAL: QQ音乐Cookie格式无效");
        String normalized = raw.strip();
        if (normalized.equals(cookie)) return;
        cookie = normalized;
        properties.getQqmusic().setCookie(normalized);
        revision.incrementAndGet();
    }

    private static boolean isValid(String value) {
        if (value == null) return true;
        return value.length() <= MAXIMUM_LENGTH
                && value.chars().noneMatch(character -> character < 32 || character == 127);
    }
}

package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
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
        this.cookie = configured == null || !isValid(configured.strip()) ? "" : configured.strip();
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

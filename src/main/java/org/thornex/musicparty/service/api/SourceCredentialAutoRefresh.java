package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.source.SourceCredentialRegistry;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放时遇到凭据类错误后的按需刷新：没有定时刷新，只在真的播放失败时尝试一次。
 *
 * <p>同一音源做单飞 + 冷却，避免队列里连续几首坏歌反复请求上游或并发刷新。</p>
 */
@Slf4j
@Service
public class SourceCredentialAutoRefresh {
    private static final long COOLDOWN_MS = 120_000L;
    private final SourceCredentialRegistry credentials;
    private final Map<String, Long> lastAttemptAt = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public SourceCredentialAutoRefresh(SourceCredentialRegistry credentials) {
        this.credentials = credentials;
    }

    /** 上游返回的“登录态无效/过期”类错误，才值得刷新凭据。 */
    public static boolean isAuthFailure(Throwable error) {
        if (error == null) return false;
        String message = String.valueOf(error.getMessage());
        return message.contains("SOURCE_AUTH_REQUIRED")
                || message.contains("RECOMMENDATION_AUTH_REQUIRED")
                || message.contains("INVALID_CREDENTIAL");
    }

    /**
     * 尝试刷新指定音源的凭据。
     *
     * @return 真正刷新成功返回 true；冷却中、已有刷新在进行、音源不支持或上游拒绝都返回 false
     */
    public boolean refresh(String source) {
        if (source == null || source.isBlank()) return false;
        long now = System.currentTimeMillis();
        Long last = lastAttemptAt.get(source);
        if (last != null && now - last < COOLDOWN_MS) return false;
        if (!inFlight.add(source)) return false;
        lastAttemptAt.put(source, now);
        try {
            credentials.refresh(source);
            log.info("Refreshed {} credential after a playback auth failure", source);
            return true;
        } catch (RuntimeException error) {
            log.warn("Automatic {} credential refresh failed: {}", source, error.getMessage());
            return false;
        } finally {
            inFlight.remove(source);
        }
    }
}

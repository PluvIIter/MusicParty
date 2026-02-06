package org.thornex.musicparty.service.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class StreamTokenService {

    private static final long EXPIRE_MS = 24 * 60 * 60 * 1000L; // 24小时绝对过期
    private static final long IDLE_EXPIRE_MS = 4 * 60 * 60 * 1000L; // 4小时闲置过期

    private record TokenInfo(String token, String userId, long creationTime, long lastAccessTime) {
        TokenInfo updateAccessTime() {
            return new TokenInfo(token, userId, creationTime, System.currentTimeMillis());
        }
    }

    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();

    public String generateToken(String userId) {
        // 先清理该用户旧的 Token (可选，如果允许单用户多 Token 则不清理)
        tokens.values().removeIf(t -> t.userId().equals(userId));

        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        tokens.put(token, new TokenInfo(token, userId, now, now));
        return token;
    }

    public boolean validateToken(String token) {
        if (token == null || !tokens.containsKey(token)) {
            return false;
        }

        TokenInfo info = tokens.get(token);
        long now = System.currentTimeMillis();

        // 检查绝对过期
        if (now - info.creationTime() > EXPIRE_MS) {
            tokens.remove(token);
            return false;
        }

        // 检查闲置过期
        if (now - info.lastAccessTime() > IDLE_EXPIRE_MS) {
            tokens.remove(token);
            return false;
        }

        // 更新最后访问时间
        tokens.put(token, info.updateAccessTime());
        return true;
    }

    @Scheduled(fixedRate = 3600000) // 每小时清理一次
    public void cleanup() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(entry -> {
            TokenInfo info = entry.getValue();
            return (now - info.creationTime() > EXPIRE_MS) || (now - info.lastAccessTime() > IDLE_EXPIRE_MS);
        });
        log.info("Cleaned up stream tokens. Current count: {}", tokens.size());
    }
}

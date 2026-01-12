package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.event.UserCountChangeEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class UserService {

    // 主存储：Token -> User
    private final Map<String, User> usersByToken = new ConcurrentHashMap<>();

    // 辅助索引：SessionId -> Token (用于快速查找当前发消息的是谁)
    private final Map<String, String> sessionToToken = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;

    private static final long USER_EXPIRATION_MS = 1 * 60 * 60 * 1000L;

    public UserService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处理连接
     * @param sessionId WebSocket Session ID
     * @param tokenFront 前端传来的 Token (可能为空)
     * @param nameFront 前端传来的名字 (可能为空)
     * @return 最终确定的 User 对象
     */
    public User handleConnect(String sessionId, String tokenFront, String nameFront) {
        User user;

        // 1. 尝试找回老用户
        if (StringUtils.hasText(tokenFront) && usersByToken.containsKey(tokenFront)) {
            user = usersByToken.get(tokenFront);
            log.info("User Reconnected: {} (Token: {}) -> New Session: {}", user.getName(), user.getToken(), sessionId);

            // 更新 SessionID
            // 如果旧Session还在索引里，先移除（防止幽灵连接）
            if (user.getSessionId() != null) {
                sessionToToken.remove(user.getSessionId());
            }
            user.setSessionId(sessionId);

            // 如果前端传了新名字且不为空，顺便更新一下（可选）
            // 这里我们选择保持后端存储的名字为主，防止被覆盖
        }
        // 2. 新用户注册
        else {
            // 如果前端没传 Token，或者 Token 无效，生成新的
            String newToken = StringUtils.hasText(tokenFront) ? tokenFront : UUID.randomUUID().toString();
            // 确保名字不重复的初始逻辑比较复杂，这里先生成默认名，稍后由 rename 处理
            String initialName = StringUtils.hasText(nameFront) ? nameFront : "User-" + sessionId.substring(0, 4);

            // 🟢 强制去重：如果初始名字被占用了，加随机后缀
            initialName = deduplicateName(initialName);

            user = new User(newToken, sessionId, initialName);
            usersByToken.put(newToken, user);
            log.info("New User Registered: {} (Token: {})", initialName, newToken);
        }

        user.setLastActiveTime(System.currentTimeMillis());

        // 建立索引
        sessionToToken.put(sessionId, user.getToken());

        // 发布用户数量变更事件
        eventPublisher.publishEvent(new UserCountChangeEvent(this, getOnlineUserSummaries().size()));
        return user;
    }

    public Optional<User> disconnectUser(String sessionId) {
        String token = sessionToToken.remove(sessionId);
        if (token == null) return Optional.empty();

        User user = usersByToken.get(token);
        if (user != null) {
            // 注意：我们不删除 userByToken，因为用户可能只是刷新页面
            // 可以做一个定时清理任务（比如 1小时不连才删），或者永久保留直到重启
            user.setSessionId(null); // 标记离线
            user.setLastActiveTime(System.currentTimeMillis());
            log.info("User Offline: {}", user.getName());
            eventPublisher.publishEvent(new UserCountChangeEvent(this, getOnlineUserSummaries().size()));
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public Optional<User> getUserBySession(String sessionId) {
        String token = sessionToToken.get(sessionId);
        if (token == null) return Optional.empty();
        return Optional.ofNullable(usersByToken.get(token));
    }

    public Optional<User> getUser(String sessionId) {
        return getUserBySession(sessionId);
    }

    // 🟢 改名逻辑：增加查重
    public boolean renameUser(String sessionId, String newName) {
        return getUserBySession(sessionId).map(user -> {
            String rawName = newName.trim();
            // 使用一个新的变量 finalName，确保它不被修改
            String finalName = rawName.length() > 20 ? rawName.substring(0, 20) : rawName;

            if (finalName.isEmpty()) return false;

            // 检查是否重名 (排除自己)
            boolean exists = usersByToken.values().stream()
                    .anyMatch(u -> u.getName().equalsIgnoreCase(finalName) && !u.getToken().equals(user.getToken()));

            if (exists) {
                log.warn("Rename failed: {} is already taken.", finalName);
                return false;
            }

            log.info("User Renamed: '{}' -> '{}'", user.getName(), finalName);
            user.setName(finalName);
            return true;
        }).orElse(false);
    }

    // 辅助：名字去重
    private String deduplicateName(String name) {
        String finalName = name;
        int counter = 1;
        while (isNameTaken(finalName)) {
            finalName = name + "_" + counter++;
        }
        return finalName;
    }

    private boolean isNameTaken(String name) {
        return usersByToken.values().stream().anyMatch(u -> u.getName().equalsIgnoreCase(name));
    }

    public boolean bindAccount(String sessionId, String platform, String accountId) {
        return getUserBySession(sessionId).map(user -> {
            user.getBindings().put(platform, accountId);
            return true;
        }).orElse(false);
    }

    public List<UserSummary> getOnlineUserSummaries() {
        return usersByToken.values().stream()
                // 只返回在线用户 (sessionId != null)
                .filter(u -> u.getSessionId() != null)
                .map(user -> new UserSummary(user.getToken(), user.getSessionId(), user.getName()))
                .toList();
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredUsers() {
        long now = System.currentTimeMillis();
        int initialSize = usersByToken.size();

        // removeIf 是线程安全的 (ConcurrentHashMap)
        usersByToken.entrySet().removeIf(entry -> {
            User user = entry.getValue();
            boolean isOffline = user.getSessionId() == null;
            boolean isExpired = (now - user.getLastActiveTime()) > USER_EXPIRATION_MS;

            if (isOffline && isExpired) {
                log.debug("Cleaning up expired user: {} (Token: {})", user.getName(), user.getToken());
                return true; // 删除
            }
            return false; // 保留
        });

        int finalSize = usersByToken.size();
        if (initialSize != finalSize) {
            log.info("Cleanup Complete. Removed {} expired users. Current memory users: {}", (initialSize - finalSize), finalSize);
        }
    }
}
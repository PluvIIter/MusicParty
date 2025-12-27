package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.dto.UserSummary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class UserService {

    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();

    public User registerUser(String sessionId, String initialName) {
        User newUser = new User(sessionId);

        // 1. 处理名字逻辑
        if (StringUtils.hasText(initialName)) {
            // 简单防注入和过长截断
            String cleanName = initialName.trim();
            if (cleanName.length() > 20) {
                cleanName = cleanName.substring(0, 20);
            }
            newUser.setName(cleanName);
        }
        // 否则 User 构造函数里默认会生成 "User-XXXXXX"

        onlineUsers.put(sessionId, newUser);
        log.info("User Registered: name='{}', session='{}', total online: {}", newUser.getName(), sessionId, onlineUsers.size());
        return newUser;
    }

    public Optional<User> disconnectUser(String sessionId) {
        User removedUser = onlineUsers.remove(sessionId);
        if (removedUser != null) {
            log.info("User disconnected: {}, total online: {}", removedUser.getName(), onlineUsers.size());
        }
        return Optional.ofNullable(removedUser);
    }

    public Optional<User> getUser(String sessionId) {
        return Optional.ofNullable(onlineUsers.get(sessionId));
    }

    public boolean renameUser(String sessionId, String newName) {
        return getUser(sessionId).map(user -> {
            log.info("User {} renamed to {}", user.getName(), newName);
            user.setName(newName);
            return true;
        }).orElse(false);
    }

    public boolean bindAccount(String sessionId, String platform, String accountId) {
        return getUser(sessionId).map(user -> {
            log.info("User {} bound account for {}: {}", user.getName(), platform, accountId);
            user.getBindings().put(platform, accountId);
            return true;
        }).orElse(false);
    }

    public List<UserSummary> getOnlineUserSummaries() {
        return onlineUsers.values().stream()
                .map(user -> new UserSummary(user.getSessionId(), user.getName()))
                .toList();
    }
}
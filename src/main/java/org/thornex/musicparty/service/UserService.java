package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    public User registerUser(String sessionId) {
        User newUser = new User(sessionId);
        onlineUsers.put(sessionId, newUser);
        log.info("User connected: {}, total online: {}", newUser.getName(), onlineUsers.size());
        return newUser;
    }

    public User registerUser(String sessionId, String initialName) {
        User newUser = new User(sessionId);
        if (initialName != null && !initialName.isBlank()) {
            // 简单过滤一下名字长度，防止过长
            if (initialName.length() > 20) initialName = initialName.substring(0, 20);
            newUser.setName(initialName);
        }
        onlineUsers.put(sessionId, newUser);
        log.info("User connected: {} (Session: {}), total online: {}", newUser.getName(), sessionId, onlineUsers.size());
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
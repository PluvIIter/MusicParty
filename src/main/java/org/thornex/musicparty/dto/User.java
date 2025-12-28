package org.thornex.musicparty.dto;

import lombok.Data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class User {
    private final String token; // 🟢 真正的唯一标识 (UUID)
    private String sessionId;   // 🟢 当前的 WebSocket 会话 ID (会变)
    private String name;
    private long lastActiveTime;
    private final Map<String, String> bindings = new ConcurrentHashMap<>();

    public User(String token, String sessionId, String name) {
        this.token = token;
        this.sessionId = sessionId;
        this.name = name;
        this.lastActiveTime = System.currentTimeMillis();
    }
}

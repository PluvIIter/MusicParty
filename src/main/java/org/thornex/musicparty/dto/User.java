package org.thornex.musicparty.dto;

import lombok.Data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class User {
    private final String sessionId;
    private String name;
    // <platform, accountId> e.g. <"netease", "123456">
    private final Map<String, String> bindings = new ConcurrentHashMap<>();

    public User(String sessionId) {
        this.sessionId = sessionId;
        this.name = "User-" + sessionId.substring(0, 6); // Default name
    }
}

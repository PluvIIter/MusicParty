package org.thornex.musicparty.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;

@Component
@Slf4j
public class WebSocketEventListener {

    private final UserService userService;
    private final MusicPlayerService musicPlayerService;

    public WebSocketEventListener(UserService userService, MusicPlayerService musicPlayerService) {
        this.userService = userService;
        this.musicPlayerService = musicPlayerService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        // 读取前端传来的 user-name 头
        String initialName = headerAccessor.getFirstNativeHeader("user-name");

        if (sessionId != null) {
            userService.registerUser(sessionId, initialName);
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        if (sessionId != null) {
            userService.disconnectUser(sessionId);
            musicPlayerService.broadcastOnlineUsers();
        }
    }
}

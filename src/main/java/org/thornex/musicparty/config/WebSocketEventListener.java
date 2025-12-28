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

        String initialName = headerAccessor.getFirstNativeHeader("user-name");
        String token = headerAccessor.getFirstNativeHeader("user-token");

        log.info("WebSocket Connect Request: Session={}, InitialName={}", sessionId, initialName);

        if (sessionId != null) {
            userService.handleConnect(sessionId, token, initialName);
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
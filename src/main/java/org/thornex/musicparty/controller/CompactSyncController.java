package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.service.CompactSyncService;

@Controller
public class CompactSyncController {
    private final CompactSyncService sync;
    private final SimpMessagingTemplate messaging;

    public CompactSyncController(CompactSyncService sync, SimpMessagingTemplate messaging) {
        this.sync = sync;
        this.messaging = messaging;
    }

    @SubscribeMapping("/player/compact")
    public ObjectNode initial() {
        return sync.snapshot();
    }

    @MessageMapping("/player/compact-resync")
    public void resync(@Header("simpSessionId") String sessionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        // A reconnecting phone must not force full snapshots onto every other listener.
        messaging.convertAndSendToUser(sessionId, "/queue/player/compact", sync.snapshot(), headers.getMessageHeaders());
    }
}

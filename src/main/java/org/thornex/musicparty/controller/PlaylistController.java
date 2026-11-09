package org.thornex.musicparty.controller;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.service.*;

import java.io.IOException;

/** Ownership comes exclusively from the authenticated WebSocket session, never from the payload. */
@Controller
public class PlaylistController {
    private final UserService users;
    private final MusicQueueManager queue;
    private final MusicPlayerService player;
    private final QueuePersistenceService persistence;
    private final SimpMessagingTemplate messages;

    public PlaylistController(UserService users, MusicQueueManager queue, MusicPlayerService player,
                              QueuePersistenceService persistence, SimpMessagingTemplate messages) {
        this.users = users; this.queue = queue; this.player = player; this.persistence = persistence; this.messages = messages;
    }
    public record ClearRequest(String requestId) {}
    public record ClearResult(String requestId, int removed, String message, boolean error) {}

    @MessageMapping("/queue/clear-mine")
    public void clearMine(@Payload ClearRequest request, @Header("simpSessionId") String sessionId) {
        String requestId = request == null ? null : request.requestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 80) return;
        int removed;
        boolean saved = true;
        // Match persistence's normal lock order and recheck membership inside the mutation boundary.
        synchronized (persistence) {
            synchronized (queue) {
                var member = users.getUser(sessionId);
                if (member.isEmpty() || member.get().isGuest()) {
                    reply(sessionId, new ClearResult(requestId, 0, "请先设置昵称并加入房间", true));
                    return;
                }
                removed = queue.removeByUser(member.get().getToken());
            }
            try { persistence.saveNow(); }
            catch (IOException error) { saved = false; }
        }
        player.broadcastQueueUpdate();
        reply(sessionId, new ClearResult(requestId, removed, saved
                ? "已清空我的歌单，移除 " + removed + " 首"
                : "已移除 " + removed + " 首，但保存失败；请检查存储后重试", !saved));
    }
    private void reply(String sessionId, ClearResult result) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messages.convertAndSendToUser(sessionId, "/queue/playlist/clear", result, headers.getMessageHeaders());
    }
}

package org.thornex.musicparty.controller;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.*;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.dto.UserSummary;

@Controller
public class PlaylistClaimController {
    private final PlaylistClaimService claims;
    private final MusicPlayerService player;
    private final SimpMessagingTemplate messages;
    public PlaylistClaimController(PlaylistClaimService claims, MusicPlayerService player, SimpMessagingTemplate messages) {
        this.claims = claims; this.player = player; this.messages = messages;
    }
    public record ClaimRequest(String requestId, String sourceToken) {}
    public record ClaimResult(String requestId, int claimed, String message, boolean error, UserSummary member) {}

    @MessageMapping("/queue/claim")
    public void claim(@Payload ClaimRequest request, @Header("simpSessionId") String sessionId) {
        String id = request == null ? null : request.requestId();
        if (id == null || id.isBlank() || id.length() > 80) return;
        PlaylistClaimService.Claimed result;
        try { result = claims.claim(sessionId, request.sourceToken()); }
        catch (IllegalArgumentException error) { reply(sessionId, new ClaimResult(id, 0, error.getMessage(), true, null)); return; }
        catch (Exception error) { reply(sessionId, new ClaimResult(id, 0, "保存认领结果失败，请稍后重试", true, null)); return; }
        // Keep the success receipt authoritative even if a subsequent broadcast encounters a disconnected subscriber.
        var headers = headers(sessionId);
        messages.convertAndSendToUser(sessionId, "/queue/me", result.member(), headers);
        reply(sessionId, new ClaimResult(id, result.claimed(), "已认领 " + result.claimed() + " 首，昵称已改为「" + result.member().name() + "」", false, result.member()));
        player.broadcastOnlineUsers();
        player.broadcastQueueUpdate();
        player.broadcastFullPlayerState();
    }
    private org.springframework.messaging.MessageHeaders headers(String sessionId) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId); headers.setLeaveMutable(true);
        return headers.getMessageHeaders();
    }
    private void reply(String sessionId, ClaimResult result) {
        messages.convertAndSendToUser(sessionId, "/queue/playlist/claim", result, headers(sessionId));
    }
}

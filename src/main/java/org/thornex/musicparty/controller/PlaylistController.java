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
    public record ReorderRequest(String queueId, String beforeQueueId, String playMode) {
        public ReorderRequest(String queueId, String beforeQueueId) { this(queueId, beforeQueueId, null); }
    }
    public record MemberShuffleRequest(Boolean shuffle) {}

    @MessageMapping("/queue/member-shuffle")
    public void memberShuffle(@Payload MemberShuffleRequest request, @Header("simpSessionId") String sessionId) {
        if (request == null || request.shuffle() == null) return;
        synchronized (persistence) {
            synchronized (queue) {
                var member = users.getUser(sessionId);
                if (member.isEmpty() || member.get().isGuest()) return;
                var previous = queue.getMemberShufflePreferences();
                queue.setMemberShuffle(member.get().getToken(), request.shuffle());
                try { persistence.saveQueueSnapshot(queue.getQueueSnapshot()); }
                catch (IOException error) {
                    queue.restoreMemberShufflePreferences(previous);
                    error(sessionId, "歌曲选择偏好保存失败，请重试");
                    return;
                }
            }
        }
        player.broadcastFullPlayerState();
    }

    @MessageMapping("/queue/reorder")
    public void reorder(@Payload ReorderRequest request, @Header("simpSessionId") String sessionId) {
        if (request == null || request.queueId() == null || request.queueId().length() > 80
                || (request.beforeQueueId() != null && request.beforeQueueId().length() > 80)) return;
        synchronized (persistence) {
            synchronized (player) {
                var mode = player.getPlayMode();
                if (mode == null || !mode.name().equals(request.playMode())) {
                    error(sessionId, "播放模式已变化，请按当前模式重新排序");
                    return;
                }
                synchronized (queue) {
                    var member = users.getUser(sessionId);
                    if (member.isEmpty() || member.get().isGuest()) return;
                    var previous = queue.getQueueSnapshot();
                    if (!queue.reorder(request.queueId(), request.beforeQueueId(), member.get().getToken(), mode)) {
                        error(sessionId, "排序未应用：顺序模式可调整全局普通歌曲，其他模式仅可调整自己的普通歌曲");
                        return;
                    }
                    try { persistence.saveQueueSnapshot(queue.getQueueSnapshot()); }
                    catch (IOException failure) {
                        queue.restoreQueueOrder(previous);
                        error(sessionId, "排序保存失败，已保留原顺序");
                        return;
                    }
                }
            }
        }
        player.broadcastQueueUpdate();
    }

    private void error(String sessionId, String message) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messages.convertAndSendToUser(sessionId, "/queue/events",
                new org.thornex.musicparty.dto.PlayerEvent("ERROR", "QUEUE_UPDATE_FAILED", sessionId, message, null),
                headers.getMessageHeaders());
    }

    private void reply(String sessionId, ClearResult result) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messages.convertAndSendToUser(sessionId, "/queue/playlist/clear", result, headers.getMessageHeaders());
    }
}

package org.thornex.musicparty.controller;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;

import java.util.List;

@Controller
public class MusicSocketController {

    private final MusicPlayerService musicPlayerService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate; // NEW: Inject messaging template

    public MusicSocketController(MusicPlayerService musicPlayerService, UserService userService, SimpMessagingTemplate messagingTemplate) {
        this.musicPlayerService = musicPlayerService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate; // NEW
    }

    // --- NEW: Method for active resynchronization ---
    /**
     * Client can send a message to this endpoint to request the latest full player state.
     * The state will be sent back directly to the user who requested it.
     */
    @MessageMapping("/player/resync")
    public void requestResync(@Header("simpSessionId") String sessionId) {
        musicPlayerService.broadcastPlayerState();
    }

    // UPDATED: Pass session ID to service
    @MessageMapping("/enqueue")
    public void enqueue(EnqueueRequest request, @Header("simpSessionId") String sessionId) {
        musicPlayerService.enqueue(request, sessionId);
    }

    // UPDATED: Pass session ID to service
    @MessageMapping("/enqueue/playlist")
    public void enqueuePlaylist(EnqueuePlaylistRequest request, @Header("simpSessionId") String sessionId) {
        musicPlayerService.enqueuePlaylist(request, sessionId);
    }

    @MessageMapping("/control/next")
    public void nextSong() {
        musicPlayerService.skipToNext();
    }

    @MessageMapping("/control/toggle-shuffle")
    public void toggleShuffle() {
        musicPlayerService.toggleShuffle();
    }

    // NEW: Endpoint to top a song in the queue
    @MessageMapping("/queue/top")
    public void topSong(@Payload QueueActionRequest request) {
        musicPlayerService.topSong(request.queueId());
    }

    @MessageMapping("/queue/remove")
    public void removeSong(@Payload QueueActionRequest request) {
        musicPlayerService.removeSongFromQueue(request.queueId());
    }

    // NEW: User-related endpoints
    @MessageMapping("/user/rename")
    public void rename(RenameRequest request, @Header("simpSessionId") String sessionId) {
        if (userService.renameUser(sessionId, request.newName())) {
            musicPlayerService.broadcastOnlineUsers(); // Notify everyone of the name change
        }
    }

    @MessageMapping("/user/bind")
    public void bindAccount(BindRequest request, @Header("simpSessionId") String sessionId) {
        userService.bindAccount(sessionId, request.platform(), request.accountId());
    }

    @SubscribeMapping("/topic/player/state")
    public PlayerState getInitialPlayerState() {
        return musicPlayerService.getCurrentPlayerState();
    }

    // NEW: Endpoint for clients to get the initial list of online users upon subscription
    @SubscribeMapping("/topic/users/online")
    public List<UserSummary> getInitialOnlineUsers() {
        return userService.getOnlineUserSummaries();
    }

    @MessageMapping("/control/toggle-pause")
    public void togglePause() {
        musicPlayerService.togglePause();
    }

    // 🟢 新增：前端订阅这个地址，直接返回当前用户的详细信息（包含SessionID）
    // 前端订阅地址: /app/user/me
    @SubscribeMapping("/user/me")
    public UserSummary getMyUserInfo(@Header("simpSessionId") String sessionId) {
        return userService.getUser(sessionId)
                .map(u -> new UserSummary(u.getSessionId(), u.getName()))
                .orElse(new UserSummary(sessionId, "Unknown"));
    }

    @MessageMapping("/req/state")
    public void requestState() {
        musicPlayerService.broadcastPlayerState();
    }
}
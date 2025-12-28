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
    private final SimpMessagingTemplate messagingTemplate;

    public MusicSocketController(MusicPlayerService musicPlayerService, UserService userService, SimpMessagingTemplate messagingTemplate) {
        this.musicPlayerService = musicPlayerService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/player/resync")
    public void requestResync(@Header("simpSessionId") String sessionId) {
        musicPlayerService.broadcastPlayerState();
    }

    @MessageMapping("/enqueue")
    public void enqueue(EnqueueRequest request, @Header("simpSessionId") String sessionId) {
        musicPlayerService.enqueue(request, sessionId);
    }

    @MessageMapping("/enqueue/playlist")
    public void enqueuePlaylist(EnqueuePlaylistRequest request, @Header("simpSessionId") String sessionId) {
        musicPlayerService.enqueuePlaylist(request, sessionId);
    }

    // 🟢 修改：增加 sessionId 参数
    @MessageMapping("/control/next")
    public void nextSong(@Header("simpSessionId") String sessionId) {
        musicPlayerService.skipToNext(sessionId);
    }

    // 🟢 修改：增加 sessionId 参数
    @MessageMapping("/control/toggle-shuffle")
    public void toggleShuffle(@Header("simpSessionId") String sessionId) {
        musicPlayerService.toggleShuffle(sessionId);
    }

    // 🟢 修改：增加 sessionId 参数
    @MessageMapping("/control/toggle-pause")
    public void togglePause(@Header("simpSessionId") String sessionId) {
        musicPlayerService.togglePause(sessionId);
    }

    // 🟢 修改：增加 sessionId 参数
    @MessageMapping("/queue/top")
    public void topSong(@Payload QueueActionRequest request, @Header("simpSessionId") String sessionId) {
        musicPlayerService.topSong(request.queueId(), sessionId);
    }

    // 🟢 修改：增加 sessionId 参数
    @MessageMapping("/queue/remove")
    public void removeSong(@Payload QueueActionRequest request, @Header("simpSessionId") String sessionId) {
        musicPlayerService.removeSongFromQueue(request.queueId(), sessionId);
    }

    @MessageMapping("/user/rename")
    public void rename(RenameRequest request, @Header("simpSessionId") String sessionId) {
        if (userService.renameUser(sessionId, request.newName())) {
            musicPlayerService.broadcastOnlineUsers();
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

    @SubscribeMapping("/topic/users/online")
    public List<UserSummary> getInitialOnlineUsers() {
        return userService.getOnlineUserSummaries();
    }

    @SubscribeMapping("/user/me")
    public UserSummary getMyUserInfo(@Header("simpSessionId") String sessionId) {
        return userService.getUser(sessionId)
                .map(u -> new UserSummary(u.getToken(), u.getSessionId(), u.getName()))
                .orElse(new UserSummary(sessionId, sessionId, "Unknown"));
    }
}
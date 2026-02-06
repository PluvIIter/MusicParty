package org.thornex.musicparty.controller;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;

import java.util.List;

@Controller
public class MusicSocketController {

    private final MusicPlayerService musicPlayerService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatService chatService;

    public MusicSocketController(MusicPlayerService musicPlayerService, UserService userService, SimpMessagingTemplate messagingTemplate, ChatService chatService) {
        this.musicPlayerService = musicPlayerService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.chatService = chatService;
    }

    @MessageMapping("/player/resync")
    public void requestResync(@Header("simpSessionId") String sessionId) {
        musicPlayerService.broadcastFullPlayerState();
    }

    @MessageMapping("/enqueue")
    public void enqueue(EnqueueRequest request, @Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.enqueue(request, sessionId);
    }

    @MessageMapping("/enqueue/playlist")
    public void enqueuePlaylist(EnqueuePlaylistRequest request, @Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.enqueuePlaylist(request, sessionId);
    }

    @MessageMapping("/control/next")
    public void nextSong(@Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.skipToNext(sessionId);
    }

    @MessageMapping("/control/toggle-shuffle")
    public void toggleShuffle(@Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.toggleShuffle(sessionId);
    }

    @MessageMapping("/control/toggle-pause")
    public void togglePause(@Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.togglePause(sessionId);
    }

    @MessageMapping("/queue/top")
    public void topSong(@Payload QueueActionRequest request, @Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.topSong(request.queueId(), sessionId);
    }

    @MessageMapping("/queue/remove")
    public void removeSong(@Payload QueueActionRequest request, @Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.removeSongFromQueue(request.queueId(), sessionId);
    }

    // 点赞接口
    @MessageMapping("/control/like")
    public void likeSong(@Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        musicPlayerService.likeSong(sessionId);
    }

    @MessageMapping("/user/rename")
    public void rename(RenameRequest request, @Header("simpSessionId") String sessionId) {
        if (userService.renameUser(sessionId, request.newName())) {
            musicPlayerService.broadcastOnlineUsers();
            // PUSH updated user info to the user
            userService.getUser(sessionId).ifPresent(user -> {
                UserSummary summary = new UserSummary(user.getToken(), user.getSessionId(), user.getName(), user.isGuest());
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/me", summary, createSessionHeaders(sessionId));
            });
        } else {
            // RENAME_FAILED
            userService.getUser(sessionId).ifPresent(user -> {
                PlayerEvent errorEvent = new PlayerEvent("ERROR", "RENAME_FAILED", user.getToken(), "该名称已被占用或包含非法字符，请更换。");
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/events", errorEvent, createSessionHeaders(sessionId));
            });
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
                .map(u -> new UserSummary(u.getToken(), u.getSessionId(), u.getName(), u.isGuest()))
                .orElse(new UserSummary(sessionId, sessionId, "Unknown", true));
    }

    private boolean isGuest(String sessionId) {
        return userService.getUser(sessionId).map(User::isGuest).orElse(true);
    }

    // 聊天消息处理
    @MessageMapping("/chat")
    public void handleChat(ChatRequest request, @Header("simpSessionId") String sessionId) {
        if (isGuest(sessionId)) return;
        userService.getUser(sessionId).ifPresent(user -> {
            if (request.content() == null || request.content().trim().isEmpty()) return;
            if (request.content().length() > 200) return;

            ChatMessage message = new ChatMessage(
                    java.util.UUID.randomUUID().toString(),
                    user.getToken(),
                    user.getName(), // 这个名字作为 Snapshot 存着也行，但前端我们会用 Token 动态查
                    request.content().trim(),
                    System.currentTimeMillis(),
                    MessageType.CHAT
            );

            // 保存到历史
            chatService.addMessage(message);

            messagingTemplate.convertAndSend("/topic/chat", message);
        });
    }

    // 订阅时获取历史记录
    @SubscribeMapping("/chat/history")
    public List<ChatMessage> getChatHistory() {
        return chatService.getHistory(0, 50);
    }

    // 处理分页获取历史记录的请求
    @MessageMapping("/chat/history/fetch")
    public void fetchChatHistory(@Payload ChatHistoryFetchRequest request, @Header("simpSessionId") String sessionId) {
        List<ChatMessage> history = chatService.getHistory(request.offset(), request.limit());
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/chat/history",
                history,
                createSessionHeaders(sessionId)
        );
    }

    private MessageHeaders createSessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor.getMessageHeaders();
    }
}
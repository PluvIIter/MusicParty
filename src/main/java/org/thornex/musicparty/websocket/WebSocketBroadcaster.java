package org.thornex.musicparty.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.UserService;
import org.thornex.musicparty.util.MessageFormatter;

@Component
@RequiredArgsConstructor
public class WebSocketBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;

    /**
     * 监听播放器完整状态变更事件
     */
    @EventListener
    public void onPlayerStateChanged(PlayerStateEvent event) {
        messagingTemplate.convertAndSend("/topic/player/state", event.getState());
    }

    /**
     * 监听队列更新事件
     */
    @EventListener
    public void onQueueChanged(QueueUpdateEvent event) {
        messagingTemplate.convertAndSend("/topic/player/queue", event.getQueue());
    }

    /**
     * 监听系统消息事件（用于 Toast 通知等）
     */
    @EventListener
    public void onSystemMessage(SystemMessageEvent event) {
        // 将内部的 SystemMessageEvent 转换为对外的 PlayerEvent DTO
        String actionCode = event.getAction() != null ? event.getAction().name() : "";
        String type = event.getLevel().name();

        String userName = "SYSTEM";
        if (!"SYSTEM".equals(event.getUserId())) {
            userName = userService.getUserByToken(event.getUserId())
                    .map(User::getName)
                    .orElse("Unknown");
        }

        String formattedMessage = MessageFormatter.format(event, userName);

        // 特殊处理密码修改的广播
        if ("PASSWORD_CHANGED".equals(event.getPayload())) {
            actionCode = "PASSWORD_CHANGED";
            type = "ERROR";
        }

        PlayerEvent playerEvent = new PlayerEvent(
                type,
                actionCode,
                event.getUserId(),
                formattedMessage,
                event.getPayload()
        );
        messagingTemplate.convertAndSend("/topic/player/events", playerEvent);
    }
}
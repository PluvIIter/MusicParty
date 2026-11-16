package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.command.ChatCommand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 播放失败的歌曲必须留在聊天历史里（含音源与原因），否则事后无法排查；
 * 其余 ERROR 仍然只弹 Toast，避免刷屏。
 */
class ChatServicePlaybackFailureTest {
    private static final String DEST = "/topic/chat";

    private ChatService chatService(SimpMessagingTemplate template) {
        return new ChatService(template, mock(UserService.class), new AppProperties(), List.<ChatCommand>of());
    }

    @Test
    void playbackFailureIsPersistedWithPlatformAndReason() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ChatService chatService = chatService(template);

        chatService.onSystemEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR,
                PlayerAction.ERROR_LOAD, "SYSTEM", "퇴사할게여（qqmusic：QQ音乐没有返回播放地址）"));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(template).convertAndSend(eq(DEST), captor.capture());
        String content = captor.getValue().content();
        assertTrue(content.contains("加载失败"), "应保留「加载失败」前缀，实际: " + content);
        assertTrue(content.contains("qqmusic"), "应带上音源，实际: " + content);
        assertTrue(content.contains("没有返回播放地址"), "应带上失败原因，实际: " + content);
    }

    @Test
    void otherErrorsStayToastOnly() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ChatService chatService = chatService(template);

        chatService.onSystemEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR,
                PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "添加失败: 你的点歌数量已达上限"));

        verifyNoInteractions(template);
    }
}

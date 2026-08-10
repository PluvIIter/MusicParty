package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.command.ChatCommand;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 回归测试：点歌人离线超 1h 被 {@link UserService} 清理后，其歌曲播出时
 * 聊天仍应显示点歌人的名字（入队时快照），而不是 Unknown。
 * <p>背景：UserService.cleanupExpiredUsers() 每小时清理离线超 1h 的用户，
 * ChatService 原实现播放时现场按 token 查名字 → 查不到 → "Unknown"。</p>
 */
class ChatServicePlayStartNameTest {

    private static final String DEST = "/topic/chat";

    @Test
    void playStartUsesSnapshotNameWhenUserPurged() {
        // 模拟点歌人已离线超 1h，被 cleanupExpiredUsers 清理出内存 → 查不到
        UserService userService = mock(UserService.class);
        when(userService.getUserByToken("tok1")).thenReturn(Optional.empty());

        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ChatService chatService = new ChatService(template, userService, new AppProperties(), List.<ChatCommand>of());

        // PLAY_START 携带入队时的名字快照
        SystemMessageEvent event = new SystemMessageEvent(
                this, SystemMessageEvent.Level.INFO, PlayerAction.PLAY_START,
                "tok1", "小明", "测试歌曲");

        chatService.onSystemEvent(event);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(template).convertAndSend(eq(DEST), captor.capture());
        ChatMessage msg = captor.getValue();

        assertEquals("小明", msg.userName(), "点歌人过期后名字快照应生效，而非 Unknown");
        assertEquals(MessageType.PLAY_START, msg.type());
        assertTrue(msg.content().contains("小明"), "消息内容应包含点歌人名字，实际: " + msg.content());
        assertTrue(msg.content().contains("测试歌曲"), "消息内容应包含歌曲名，实际: " + msg.content());
    }

    @Test
    void playStartWithoutSnapshotFallsBackToLiveLookup() {
        // 未携带快照（老调用方）：用户在线 → 现场查到
        UserService userService = mock(UserService.class);
        when(userService.getUserByToken("tok1")).thenReturn(Optional.of(new User("tok1", "sess1", "小红")));

        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ChatService chatService = new ChatService(template, userService, new AppProperties(), List.<ChatCommand>of());

        // 旧 5 参构造器（不携带名字快照）
        SystemMessageEvent event = new SystemMessageEvent(
                this, SystemMessageEvent.Level.INFO, PlayerAction.PLAY_START, "tok1", "测试歌曲");

        chatService.onSystemEvent(event);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(template).convertAndSend(eq(DEST), captor.capture());
        assertEquals("小红", captor.getValue().userName(), "无快照时应回退现场查询");
    }
}

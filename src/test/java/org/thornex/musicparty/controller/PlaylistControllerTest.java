package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaylistControllerTest {
    private final UserService users = mock(UserService.class);
    private final MusicQueueManager queue = new MusicQueueManager(new AppProperties());
    private final MusicPlayerService player = mock(MusicPlayerService.class);
    private final QueuePersistenceService persistence = mock(QueuePersistenceService.class);
    private final SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
    private final PlaylistController controller = new PlaylistController(users, queue, player, persistence, messages);

    private MusicQueueItem add(String owner) {
        return queue.add(new Music(owner, owner, List.of(), 1000, "netease", ""),
                new UserSummary(owner, owner, owner, false), QueueItemStatus.READY);
    }

    @Test void clearUsesSessionIdentityAndRepliesOnlyToThatSession() throws Exception {
        add("A");
        MusicQueueItem b = add("B");
        queue.addToHistory(b.music());
        when(users.getUser("session-A")).thenReturn(Optional.of(new User("A", "session-A", "Alice")));
        controller.clearMine(new PlaylistController.ClearRequest("request-A"), "session-A");
        assertEquals(List.of(b), queue.getQueueSnapshot());
        assertEquals(List.of(b.music()), queue.getHistorySnapshot());
        verify(persistence).saveNow();
        verify(player).broadcastQueueUpdate();
        verifyNoMoreInteractions(player); // No pause/skip/reset of current audio.
        ArgumentCaptor<PlaylistController.ClearResult> result = ArgumentCaptor.forClass(PlaylistController.ClearResult.class);
        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messages).convertAndSendToUser(eq("session-A"), eq("/queue/playlist/clear"), result.capture(), headers.capture());
        assertEquals(1, result.getValue().removed());
        assertFalse(result.getValue().error());
        assertEquals("request-A", result.getValue().requestId());
        assertEquals("session-A", headers.getValue().get("simpSessionId"));
    }

    @Test void guestAndDisconnectedSessionCannotDeleteAnything() {
        MusicQueueItem a = add("A");
        when(users.getUser("guest")).thenReturn(Optional.of(new User("guest-token", "guest", "游客")));
        when(users.getUser("gone")).thenReturn(Optional.empty());
        controller.clearMine(new PlaylistController.ClearRequest("g"), "guest");
        controller.clearMine(new PlaylistController.ClearRequest("d"), "gone");
        assertEquals(List.of(a), queue.getQueueSnapshot());
        verifyNoInteractions(persistence, player);
    }

    @Test void emptyPlaylistSucceedsAndMalformedRequestDoesNothing() throws Exception {
        when(users.getUser("s")).thenReturn(Optional.of(new User("A", "s", "Alice")));
        controller.clearMine(new PlaylistController.ClearRequest("empty"), "s");
        verify(messages).convertAndSendToUser(eq("s"), anyString(), argThat((Object value) ->
                value instanceof PlaylistController.ClearResult r && r.removed() == 0 && !r.error()), anyMap());
        clearInvocations(users, messages, player, persistence);
        controller.clearMine(new PlaylistController.ClearRequest(" "), "s");
        controller.clearMine(new PlaylistController.ClearRequest("x".repeat(81)), "s");
        verifyNoInteractions(users, messages, player, persistence);
    }

    @Test void persistenceFailureIsReportedWithoutClaimingSavedSuccess() throws Exception {
        add("A");
        MusicQueueItem b = add("B");
        when(users.getUser("s")).thenReturn(Optional.of(new User("A", "s", "Alice")));
        doThrow(new java.io.IOException("test disk full")).when(persistence).saveNow();
        controller.clearMine(new PlaylistController.ClearRequest("failure"), "s");
        assertEquals(List.of(b), queue.getQueueSnapshot());
        verify(player).broadcastQueueUpdate();
        verify(messages).convertAndSendToUser(eq("s"), anyString(), argThat((Object value) ->
                value instanceof PlaylistController.ClearResult r && r.error() && r.removed() == 1
                        && r.message().contains("保存失败")), anyMap());
    }
}

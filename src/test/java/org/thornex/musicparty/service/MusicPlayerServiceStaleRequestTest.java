package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEvent;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceStaleRequestTest {
    private final UserSummary owner = new UserSummary("member", "session", "Alice", false);
    private MusicQueueManager queue;
    private NeteaseMusicApiService api;
    private ApplicationEventPublisher publisher;
    private MusicPlayerService player;
    private Sinks.One<PlayableMusic> first;
    private Sinks.One<PlayableMusic> second;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getPrivateDj().setMode("OFF");
        queue = mock(MusicQueueManager.class);
        MusicQueueItem a = item("a"), b = item("b"), c = item("c");
        when(queue.getQueueSnapshot()).thenReturn(List.of(a, b, c));
        when(queue.pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet())).thenReturn(a, b, c);
        UserService users = mock(UserService.class);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(owner));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(owner.token()));
        api = mock(NeteaseMusicApiService.class);
        when(api.getPlatformName()).thenReturn("netease");
        first = Sinks.one(); second = Sinks.one();
        when(api.getPlayableMusic("a")).thenReturn(first.asMono());
        when(api.getPlayableMusic("b")).thenReturn(second.asMono());
        when(api.getPlayableMusic("c")).thenReturn(Mono.never());
        publisher = mock(ApplicationEventPublisher.class);
        player = new MusicPlayerService(List.of(api), users, mock(LocalCacheService.class),
                mock(LiveStreamService.class), queue, publisher, properties, api, mock(PrivateDjService.class));
    }

    private MusicQueueItem item(String id) {
        return new MusicQueueItem(id, new Music(id, id, List.of("Artist"), 180_000L, "netease", null), owner, QueueItemStatus.READY);
    }

    private PlayableMusic playable(String id) {
        return new PlayableMusic(id, id, List.of("Artist"), 180_000L, "netease", "https://example.invalid/" + id, null, false);
    }

    @Test
    void oldFailureAfterSkipDoesNotClearNewLoadingStateOrAdvanceQueue() {
        player.playerLoop();
        player.skipToNext("SYSTEM");
        clearInvocations(publisher);

        first.tryEmitError(new IllegalStateException("old request failed"));

        assertTrue(player.getCurrentPlayerState().isLoading());
        verify(api, never()).getPlayableMusic("c");
        verify(queue, times(2)).pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet());
        verifyNoInteractions(publisher);
        second.tryEmitValue(playable("b"));
        assertEquals("b", player.getCurrentPlayerState().nowPlaying().music().id());
    }

    @Test
    void currentFailureStillNotifiesAndStartsNextSong() {
        player.playerLoop();
        first.tryEmitError(new IllegalStateException("current request failed"));

        verify(api).getPlayableMusic("b");
        assertTrue(player.getCurrentPlayerState().isLoading());
        verify(publisher).publishEvent(argThat((ApplicationEvent event) ->
                event instanceof SystemMessageEvent message && message.getAction() == PlayerAction.ERROR_LOAD));
    }

    @Test
    void resetInvalidatesLateFailure() {
        player.playerLoop();
        player.resetSystem();
        clearInvocations(publisher);

        first.tryEmitError(new IllegalStateException("failure after reset"));

        assertFalse(player.getCurrentPlayerState().isLoading());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        verify(api, never()).getPlayableMusic("b");
        verifyNoInteractions(publisher);
    }

    @Test
    void resetInvalidatesLateSuccess() {
        player.playerLoop();
        player.resetSystem();
        first.tryEmitValue(playable("a"));
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertFalse(player.getCurrentPlayerState().isLoading());
    }
}

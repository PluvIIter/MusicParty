package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServicePauseCleanupTest {

    private static final long SAVED_POSITION = 42_000L;
    private static final PlayableMusic TRACK = new PlayableMusic(
            "song-1", "Paused song", List.of("Artist"), 180_000L, "netease",
            "https://example.invalid/song.mp3", null, false);
    private static final UserSummary MEMBER = new UserSummary("token-1", "session-1", "Listener", false);

    private UserService users;
    private LiveStreamService streams;
    private MusicQueueManager queue;
    private ApplicationEventPublisher events;
    private NeteaseMusicApiService api;
    private MusicPlayerService player;
    private List<MusicQueueItem> queuedSongs;
    private long pausedAt;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        streams = mock(LiveStreamService.class);
        queue = mock(MusicQueueManager.class);
        events = mock(ApplicationEventPublisher.class);
        api = mock(NeteaseMusicApiService.class);
        when(api.getPlatformName()).thenReturn("netease");
        player = PlayerTestFactory.create(List.of(api), users, mock(LocalCacheService.class), streams,
                queue, events, new AppProperties(), api, mock(PrivateDjService.class));

        queuedSongs = List.of(new MusicQueueItem("queue-1",
                new Music("song-2", "Queued song", List.of("Artist"), 180_000L, "netease", null),
                MEMBER, QueueItemStatus.READY));
        when(queue.getQueueSnapshot()).thenReturn(queuedSongs);
        when(users.getOnlineUserSummaries()).thenReturn(List.of());
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of());
        currentMusic().set(TRACK);
        player.setPausedForTest(true);
        player.setPositionForTest(SAVED_POSITION);
        setPauseAge(Duration.ofMinutes(35));
        clearInvocations(api);
    }

    @Test
    void connectedMemberKeepsLongManualPauseAndSavedPosition() {
        when(users.getOnlineUserSummaries()).thenReturn(List.of(MEMBER));

        for (int i = 0; i < 30; i++) {
            player.cleanupIdlePlayer();
            player.playerLoop();
        }

        assertRetainedPause();
        assertEquals(queuedSongs, player.getCurrentPlayerState().queue());
        verifyNoInteractions(events);
        verify(api, never()).getPlayableMusic(anyString());
        verify(api, never()).getFmDjSongUrl(anyString());
        verify(queue, never()).pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet());
    }

    @Test
    void streamConnectionKeepsLongPauseWithoutWebMembers() {
        // A registered stream connection counts even before its listener status event arrives.
        when(streams.getStreamConnectionCount()).thenReturn(1);

        player.cleanupIdlePlayer();

        assertRetainedPause();
        verifyNoInteractions(events);
    }

    @Test
    void emptyRoomKeepsRecentlyPausedMedia() {
        setPauseAge(Duration.ofMinutes(5));

        player.cleanupIdlePlayer();

        assertRetainedPause();
        verifyNoInteractions(events);
    }

    @Test
    void expiredEmptyRoomReleasesMediaButRemainsPaused() {
        player.cleanupIdlePlayer();

        PlayerState state = player.getCurrentPlayerState();
        assertTrue(state.isPaused());
        assertNull(state.nowPlaying());
        assertEquals(0L, anchor("positionAnchor").get());
        assertEquals(0L, anchor("timestampAnchor").get());
        assertEquals(queuedSongs, state.queue());
        ArgumentCaptor<PlayerStateEvent> broadcast = ArgumentCaptor.forClass(PlayerStateEvent.class);
        verify(events).publishEvent(broadcast.capture());
        assertTrue(broadcast.getValue().getState().isPaused());
        assertNull(broadcast.getValue().getState().nowPlaying());

        for (int i = 0; i < 30; i++) {
            player.cleanupIdlePlayer();
            player.playerLoop();
        }
        verifyNoMoreInteractions(events);
        verify(api, never()).getPlayableMusic(anyString());
        verify(api, never()).getFmDjSongUrl(anyString());
        verify(queue, never()).pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet());
    }

    @Test
    void returningMemberDoesNotAutomaticallyStartQueueAfterCleanup() {
        player.cleanupIdlePlayer();
        clearInvocations(events);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(MEMBER));

        for (int i = 0; i < 30; i++) {
            player.playerLoop();
        }

        assertTrue(player.getCurrentPlayerState().isPaused());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertEquals(queuedSongs, player.getCurrentPlayerState().queue());
        verifyNoInteractions(events);
        verify(api, never()).getPlayableMusic(anyString());
        verify(api, never()).getFmDjSongUrl(anyString());
        verify(queue, never()).pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet());
    }

    @Test
    void explicitResumeContinuesRetainedSongAtSavedPosition() {
        when(users.getOnlineUserSummaries()).thenReturn(List.of(MEMBER));
        when(users.getUser(MEMBER.sessionId())).thenReturn(Optional.of(
                new User(MEMBER.token(), MEMBER.sessionId(), MEMBER.name())));
        player.cleanupIdlePlayer();

        player.togglePause(MEMBER.sessionId());

        assertFalse(player.getCurrentPlayerState().isPaused());
        assertSame(TRACK, player.getCurrentPlayerState().nowPlaying().music());
        assertEquals(SAVED_POSITION, anchor("positionAnchor").get());
        assertTrue(anchor("timestampAnchor").get() > pausedAt);
        verify(queue, never()).pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet());
    }

    @Test
    void cleanupDoesNotChangeActivePlayback() {
        player.setPausedForTest(false);

        player.cleanupIdlePlayer();

        assertFalse(player.getCurrentPlayerState().isPaused());
        assertSame(TRACK, currentMusic().get());
        assertEquals(SAVED_POSITION, anchor("positionAnchor").get());
        assertEquals(pausedAt, anchor("timestampAnchor").get());
        verifyNoInteractions(events);
    }

    private void setPauseAge(Duration age) {
        // Advance the pause age without sleeping or changing the system clock.
        pausedAt = System.currentTimeMillis() - age.toMillis();
        anchor("timestampAnchor").set(pausedAt);
    }

    private void assertRetainedPause() {
        PlayerState state = player.getCurrentPlayerState();
        assertTrue(state.isPaused());
        assertNotNull(state.nowPlaying());
        assertSame(TRACK, state.nowPlaying().music());
        assertEquals(SAVED_POSITION, state.nowPlaying().currentPosition());
        assertEquals(pausedAt, anchor("timestampAnchor").get());
    }

    private AtomicLong anchor(String name) {
        return (AtomicLong) ReflectionTestUtils.getField(player, name);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<PlayableMusic> currentMusic() {
        return (AtomicReference<PlayableMusic>) ReflectionTestUtils.getField(player, "currentMusic");
    }
}

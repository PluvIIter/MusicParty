package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.event.UserCountChangeEvent;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceIdleWakeTest {
    private static final UserSummary MEMBER = new UserSummary("member", "session", "Listener", false);
    private static final Music SONG = new Music("song", "Song", List.of("Artist"), 180_000, "netease", null, null);
    private static final PlayableMusic PLAYABLE = new PlayableMusic("song", "Song", List.of("Artist"), 180_000,
            "netease", "https://audio.invalid/song", null, "SONG", null);

    private AppProperties properties;
    private UserService users;
    private MusicQueueManager queue;
    private PrivateDjService dj;
    private MusicPlayerService player;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        users = mock(UserService.class);
        queue = new MusicQueueManager(properties);
        dj = mock(PrivateDjService.class);
        player = PlayerTestFactory.create(List.of(), users, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), properties, dj);
        when(PlayerTestFactory.media(player).getPlayableMusic("netease", "song")).thenReturn(Mono.just(PLAYABLE));
        when(users.getOnlineUserSummaries()).thenReturn(List.of());
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of());
    }

    @Test
    void firstArrivalStartsQueuedSongAfterEmptyRoomCleanup() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        expirePausedMedia();
        player.playerLoop();
        assertTrue(player.getCurrentPlayerState().isPaused());
        assertNull(player.getCurrentPlayerState().nowPlaying());

        arrive(MEMBER);
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
        player.playerLoop();

        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertFalse(player.getCurrentPlayerState().isPaused());
        assertEquals("song", player.getCurrentPlayerState().nowPlaying().music().id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FM", "DJ"})
    void firstArrivalStartsConfiguredRecommendationAfterCleanup(String mode) {
        properties.getPrivateDj().setMode(mode);
        properties.getPrivateDj().setFillBlankEnabled(true);
        when(dj.authorizationScope()).thenReturn("recommendation-scope");
        PrivateDjSegment segment = "DJ".equals(mode)
                ? new PrivateDjSegment.Voice("https://audio.invalid/voice", "voice", 1000, null, null)
                : new PrivateDjSegment.Song("song", "Song", List.of("Artist"), 180_000, null);
        when(dj.nextSegment()).thenReturn(Mono.just(segment));
        when(dj.getPlayable(segment)).thenReturn(Mono.just(PLAYABLE));
        expirePausedMedia();

        arrive(MEMBER);
        player.playerLoop();

        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertFalse(player.getCurrentPlayerState().isPaused());
        assertEquals("DJ".equals(mode) ? "VOICE" : "SONG", player.getCurrentPlayerState().nowPlaying().music().kind());
        verify(dj).nextSegment();
    }

    @Test
    void randomJoinQueuePolicyCanWakeThroughItsFmMarker() {
        properties.getPrivateDj().setMode("DJ");
        properties.getPrivateDj().setJoinQueueEnabled(true);
        player.setPlayModeForTest(PlayMode.ROUND_ROBIN);
        when(dj.nextFmSegment()).thenReturn(Mono.never());
        expirePausedMedia();

        arrive(MEMBER);
        player.playerLoop();

        assertFalse(player.getCurrentPlayerState().isPaused());
        verify(dj).nextFmSegment();
        verify(dj, never()).nextSegment();
    }

    @Test
    void wakeRetainsRecommendationBackoffAndUsesScheduledRetry() {
        properties.getPrivateDj().setMode("FM");
        properties.getPrivateDj().setCustodyEnabled(true);
        when(dj.nextSegment()).thenReturn(Mono.never());
        expirePausedMedia();
        AtomicLong retryAt = (AtomicLong) ReflectionTestUtils.getField(player, "fmDjRetryAt");
        retryAt.set(System.currentTimeMillis() + 60_000);
        arrive(MEMBER);

        player.playerLoop();
        assertFalse(player.getCurrentPlayerState().isPaused());
        verify(dj, never()).nextSegment();
        retryAt.set(0);
        player.playerLoop();
        verify(dj).nextSegment();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "FM", "DJ"})
    void modeWithoutPlaybackPolicyDoesNotWakeEmptyRoom(String mode) {
        properties.getPrivateDj().setMode(mode);
        properties.getPrivateDj().setFillBlankEnabled(false);
        properties.getPrivateDj().setCustodyEnabled(false);
        properties.getPrivateDj().setJoinQueueEnabled(false);
        expirePausedMedia();
        arrive(MEMBER);

        player.playerLoop();

        assertTrue(player.getCurrentPlayerState().isPaused());
        verify(dj, never()).nextSegment();
        verify(dj, never()).nextFmSegment();
    }

    @Test
    void unavailableRecommendationSourceDoesNotWakeOrEnableIt() {
        properties.getPrivateDj().setMode("DJ");
        properties.getPrivateDj().setCustodyEnabled(true);
        when(dj.supportsMode("DJ")).thenReturn(false);
        expirePausedMedia();
        arrive(MEMBER);

        player.playerLoop();

        assertTrue(player.getCurrentPlayerState().isPaused());
        verify(dj, never()).nextSegment();
    }

    @Test
    void disabledQueuedSourceStaysPendingWithoutMediaRequest() {
        AppProperties.SourceConfig source = new AppProperties.SourceConfig();
        source.setEnabled(false);
        properties.getSources().put("netease", source);
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        expirePausedMedia();
        arrive(MEMBER);

        player.playerLoop();

        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertEquals(QueueItemStatus.PENDING, player.getCurrentPlayerState().queue().getFirst().status());
        assertFalse(source.getEnabled());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
    }

    @Test
    void queueSelectionKeepsOnlinePreferenceInsteadOfTakingOfflineHead() {
        var offline = new Music("offline", "Offline song", List.of(), 180_000, "netease", null, null);
        queue.add(offline, new UserSummary("offline", null, "Offline", false), QueueItemStatus.READY);
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        player.setPlayModeForTest(PlayMode.ROUND_ROBIN);
        expirePausedMedia();
        arrive(MEMBER);

        player.playerLoop();

        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertEquals("song", player.getCurrentPlayerState().nowPlaying().music().id());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic("netease", "offline");
    }

    @Test
    void guestAndDuplicateArrivalEventsDoNotDuplicatePendingLoad() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        Sinks.One<PlayableMusic> pending = Sinks.one();
        when(PlayerTestFactory.media(player).getPlayableMusic("netease", "song")).thenReturn(pending.asMono());
        expirePausedMedia();
        arrive(new UserSummary("guest", "guest-session", "游客", true));
        player.playerLoop();
        verify(PlayerTestFactory.media(player), timeout(2000)).getPlayableMusic("netease", "song");

        player.onUserCountChanged(new UserCountChangeEvent(this, 1));
        player.onUserCountChanged(new UserCountChangeEvent(this, 2));
        player.playerLoop();
        verify(PlayerTestFactory.media(player), times(1)).getPlayableMusic("netease", "song");
        pending.tryEmitValue(PLAYABLE);
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
    }

    @Test
    void arrivalWhileLoadingRemainsPendingUntilLoadingFinishes() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        expirePausedMedia();
        var loading = (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(player, "isLoading");
        loading.set(true);
        arrive(MEMBER);

        player.playerLoop();
        assertTrue(player.getCurrentPlayerState().isPaused());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
        loading.set(false);
        player.playerLoop();

        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        verify(PlayerTestFactory.media(player)).getPlayableMusic("netease", "song");
    }

    @Test
    void retainedManualPauseSurvivesFirstArrivalAndQuickReconnect() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        player.applyFmDjSegmentForTest(PLAYABLE, new PrivateDjSegment.Song("song", "Song", List.of(), 180_000, null));
        player.setPausedForTest(true);
        player.setPositionForTest(42_000);
        arrive(MEMBER);
        player.playerLoop();
        player.onUserCountChanged(new UserCountChangeEvent(this, 0));
        arrive(MEMBER);
        player.playerLoop();

        assertTrue(player.getCurrentPlayerState().isPaused());
        assertEquals(42_000, player.getCurrentPlayerState().nowPlaying().currentPosition());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
    }

    @Test
    void arrivalConsumedForRetainedPauseCannotWakeAfterLaterMediaCleanup() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        player.applyFmDjSegmentForTest(PLAYABLE, new PrivateDjSegment.Song("song", "Song", List.of(), 180_000, null));
        player.setPausedForTest(true);
        arrive(MEMBER);
        player.playerLoop();
        when(users.getOnlineUserSummaries()).thenReturn(List.of());
        ((AtomicLong) ReflectionTestUtils.getField(player, "timestampAnchor"))
                .set(System.currentTimeMillis() - Duration.ofMinutes(35).toMillis());
        player.cleanupIdlePlayer();
        when(users.getOnlineUserSummaries()).thenReturn(List.of(MEMBER));

        player.playerLoop();

        assertTrue(player.getCurrentPlayerState().isPaused());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
    }

    @Test
    void additionalMemberDoesNotWakeAnAlreadyOccupiedPausedRoom() {
        arrive(MEMBER);
        player.playerLoop();
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        player.setPausedForTest(true);
        player.onUserCountChanged(new UserCountChangeEvent(this, 2));

        player.playerLoop();

        assertTrue(player.getCurrentPlayerState().isPaused());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
    }

    @Test
    void newArrivalDuringStalePresenceReadSurvivesForTheNextTick() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        expirePausedMedia();
        player.onUserCountChanged(new UserCountChangeEvent(this, 1));
        var firstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(users.getOnlineUserSummaries()).thenAnswer(invocation -> {
            if (firstRead.getAndSet(false)) {
                player.onUserCountChanged(new UserCountChangeEvent(this, 0));
                player.onUserCountChanged(new UserCountChangeEvent(this, 1));
                return List.of();
            }
            return List.of(MEMBER);
        });
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(MEMBER.token()));

        player.playerLoop();
        assertTrue(player.getCurrentPlayerState().isPaused());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
        player.playerLoop();

        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertFalse(player.getCurrentPlayerState().isPaused());
    }

    @Test
    void departedArrivalCannotWakeWithoutActualPresence() {
        queue.add(SONG, MEMBER, QueueItemStatus.READY);
        expirePausedMedia();
        player.onUserCountChanged(new UserCountChangeEvent(this, 1));
        player.onUserCountChanged(new UserCountChangeEvent(this, 0));

        player.playerLoop();

        assertTrue(player.getCurrentPlayerState().isPaused());
        verify(PlayerTestFactory.media(player), never()).getPlayableMusic(anyString(), anyString());
    }

    private void arrive(UserSummary listener) {
        when(users.getOnlineUserSummaries()).thenReturn(List.of(listener));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(listener.token()));
        player.onUserCountChanged(new UserCountChangeEvent(this, 1));
    }

    private void expirePausedMedia() {
        player.applyFmDjSegmentForTest(PLAYABLE, new PrivateDjSegment.Song("song", "Song", List.of(), 180_000, null));
        player.setPausedForTest(true);
        ((AtomicLong) ReflectionTestUtils.getField(player, "timestampAnchor"))
                .set(System.currentTimeMillis() - Duration.ofMinutes(35).toMillis());
        player.cleanupIdlePlayer();
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertTrue(player.getCurrentPlayerState().isPaused());
    }
}

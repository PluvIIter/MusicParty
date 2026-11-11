package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MusicPlayerServiceSeekTest {
    private MusicPlayerService player;
    private MusicQueueManager queue;
    private ApplicationEventPublisher publisher;
    private AppProperties properties;
    private final UserSummary owner = new UserSummary("member", "session", "Alice", false);

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getPlayer().setAllowSeek(true);
        properties.getPrivateDj().setMode("OFF");
        queue = mock(MusicQueueManager.class);
        UserService users = mock(UserService.class);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(owner));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(owner.token()));
        NeteaseMusicApiService api = mock(NeteaseMusicApiService.class);
        when(api.getPlatformName()).thenReturn("netease");
        when(api.getPlayableMusic("a")).thenReturn(Mono.just(music("a")));
        publisher = mock(ApplicationEventPublisher.class);
        player = PlayerTestFactory.create(List.of(api), users, mock(LocalCacheService.class),
                mock(LiveStreamService.class), queue, publisher, properties, api, mock(PrivateDjService.class));
        start("a");
    }

    private PlayableMusic music(String id) {
        return new PlayableMusic(id, id, List.of("Artist"), 180_000L, "netease", "https://example.invalid/" + id, null, false);
    }

    private MusicQueueItem item(String id) {
        return new MusicQueueItem(id, new Music(id, id, List.of("Artist"), 180_000L, "netease", null), owner, QueueItemStatus.READY);
    }

    private void start(String id) {
        ReflectionTestUtils.invokeMethod(player, "applyNewSong", music(id), item(id));
    }

    private void clearCooldown() {
        ((AtomicLong) ReflectionTestUtils.getField(player, "lastControlTimestamp")).set(0);
    }

    @Test
    void matchingSeekBroadcastsIdentityAndClampsWithoutChangingInstance() {
        String id = player.getCurrentPlayerState().playbackId();
        player.setPausedForTest(true);
        clearInvocations(publisher);

        player.seekTo("session", Long.MAX_VALUE, id);

        PlayerState state = player.getCurrentPlayerState();
        assertEquals(id, state.playbackId());
        assertEquals(179_500, state.nowPlaying().currentPosition());
        assertEquals(1, state.seekNonce());
        assertTrue(state.isPaused());
        verify(publisher).publishEvent(argThat((org.springframework.context.ApplicationEvent event) ->
                event instanceof PlayerStateEvent update && id.equals(update.getState().playbackId())));
    }

    @Test
    void staleSeekCannotChangeNewSongOrConsumeCooldown() {
        String oldId = player.getCurrentPlayerState().playbackId();
        start("b");
        player.setPausedForTest(true);
        String newId = player.getCurrentPlayerState().playbackId();
        clearInvocations(publisher);

        player.seekTo("session", 80_000, oldId);

        assertEquals(0, player.getCurrentPlayerState().nowPlaying().currentPosition());
        assertEquals(0, player.getCurrentPlayerState().seekNonce());
        verifyNoInteractions(publisher);
        player.seekTo("session", 20_000, newId);
        assertEquals(20_000, player.getCurrentPlayerState().nowPlaying().currentPosition());
    }

    @Test
    void missingIdentityIsRejectedEvenForLegacySystemEntryPoint() {
        player.setPausedForTest(true);
        clearInvocations(publisher);
        player.seekTo("session", 80_000);
        player.seekTo("SYSTEM", 80_000);
        player.seekTo("SYSTEM", 80_000, null);
        player.seekTo("session", 80_000, "");
        assertEquals(0, player.getCurrentPlayerState().nowPlaying().currentPosition());
        assertEquals(0, player.getCurrentPlayerState().seekNonce());
        verifyNoInteractions(publisher);
    }

    @Test
    void repeatedSongHasNewIdentityAndRejectsPreviousPassSeek() {
        String oldId = player.getCurrentPlayerState().playbackId();
        player.setPlayModeForTest(PlayMode.REPEAT_ONE);
        player.setPositionForTest(180_000);

        player.playerLoop();

        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        PlayerState repeated = player.getCurrentPlayerState();
        assertNotEquals(oldId, repeated.playbackId());
        assertEquals(0, repeated.seekNonce());
        assertTrue(repeated.nowPlaying().currentPosition() < 1000);
        player.seekTo("session", 80_000, oldId);
        assertEquals(0, player.getCurrentPlayerState().seekNonce());
        player.seekTo("session", 20_000, repeated.playbackId());
        assertEquals(1, player.getCurrentPlayerState().seekNonce());
    }

    @Test
    void retainedSameSongAndFmSegmentsEachGetNewIdentity() {
        String initial = player.getCurrentPlayerState().playbackId();
        when(queue.pollNext(any(), anyBoolean(), anyBoolean(), anyMap(), anySet())).thenReturn(item("a"));
        player.setPositionForTest(180_000);
        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        String retained = player.getCurrentPlayerState().playbackId();
        assertNotEquals(initial, retained);
        assertEquals("a", player.getCurrentPlayerState().nowPlaying().music().id());

        PrivateDjSegment.Song segment = new PrivateDjSegment.Song("a", "a", List.of("Artist"), 180_000L, null);
        player.applyFmDjSegmentForTest(music("a"), segment);
        String fm = player.getCurrentPlayerState().playbackId();
        player.applyFmDjSegmentForTest(music("a"), segment);
        assertNotEquals(retained, fm);
        assertNotEquals(fm, player.getCurrentPlayerState().playbackId());
    }

    @Test
    void pauseSeekResumePreservesInstanceAndNewAnchor() {
        String id = player.getCurrentPlayerState().playbackId();
        player.togglePause("SYSTEM");
        player.seekTo("SYSTEM", 20_000, id);
        assertTrue(player.getCurrentPlayerState().isPaused());
        assertEquals(20_000, player.getCurrentPlayerState().nowPlaying().currentPosition());
        clearCooldown();
        player.togglePause("SYSTEM");
        PlayerState state = player.getCurrentPlayerState();
        assertFalse(state.isPaused());
        assertEquals(id, state.playbackId());
        assertTrue(state.nowPlaying().currentPosition() >= 20_000);
        assertTrue(state.nowPlaying().currentPosition() < 21_000);
    }

    @Test
    void seekPermissionStillAppliesButSkipLockDoesNotBlockSeek() {
        String id = player.getCurrentPlayerState().playbackId();
        player.setPausedForTest(true);
        properties.getPlayer().setAllowSeek(false);
        player.seekTo("session", 20_000, id);
        assertEquals(0, player.getCurrentPlayerState().seekNonce());
        properties.getPlayer().setAllowSeek(true);
        player.setLock("SKIP", true);
        player.seekTo("session", 20_000, id);
        assertEquals(20_000, player.getCurrentPlayerState().nowPlaying().currentPosition());
    }

    @Test
    void seekWaitingBehindSongReplacementCannotWriteNewSongAnchor() throws Exception {
        String oldId = player.getCurrentPlayerState().playbackId();
        CountDownLatch started = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> seek;
            synchronized (player) {
                seek = executor.submit(() -> {
                    started.countDown();
                    player.seekTo("session", 80_000, oldId);
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> seek.get(100, TimeUnit.MILLISECONDS));
                start("b");
                player.setPausedForTest(true);
            }
            seek.get(2, TimeUnit.SECONDS);
            assertEquals("b", player.getCurrentPlayerState().nowPlaying().music().id());
            assertEquals(0, player.getCurrentPlayerState().nowPlaying().currentPosition());
            assertEquals(0, player.getCurrentPlayerState().seekNonce());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void skipAndResetInvalidateIdentityEvenWithoutNextSong() {
        String oldId = player.getCurrentPlayerState().playbackId();
        player.skipToNext("SYSTEM");
        assertNull(player.getCurrentPlayerState().playbackId());
        player.seekTo("session", 80_000, oldId);
        start("b");
        player.resetSystem();
        assertNull(player.getCurrentPlayerState().playbackId());
        assertNull(player.getCurrentPlayerState().nowPlaying());
    }
}

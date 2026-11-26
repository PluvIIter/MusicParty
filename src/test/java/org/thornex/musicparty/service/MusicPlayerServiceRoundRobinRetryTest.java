package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceRoundRobinRetryTest {
    private final UserSummary alice = new UserSummary("a", "alice-session", "Alice", false);
    private final UserSummary bob = new UserSummary("b", "bob-session", "Bob", false);
    private AppProperties properties;
    private MusicQueueManager queue;
    private MusicPlayerService player;
    private MediaDeliveryService media;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getPrivateDj().setMode("OFF");
        queue = new MusicQueueManager(properties);
        queue.setMemberShuffle(alice.token(), false);
        queue.setMemberShuffle(bob.token(), false);
        UserService users = mock(UserService.class);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(alice, bob));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(alice.token(), bob.token()));
        player = PlayerTestFactory.create(List.of(), users, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), properties, mock(PrivateDjService.class));
        player.setPlayModeForTest(PlayMode.ROUND_ROBIN);
        media = PlayerTestFactory.media(player);
    }

    @Test
    void multipleFailuresStayWithTheMemberAndSuccessEndsTheirTurn() {
        add("a1", alice);
        add("b1", bob);
        add("a2", alice);
        add("a3", alice);
        add("a4", alice);
        when(media.getPlayableMusic("netease", "a1")).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        when(media.getPlayableMusic("netease", "a2")).thenReturn(Mono.empty());
        canPlay("a3");
        canPlay("b1");

        player.playerLoop();
        awaitPlaying("a3");
        var order = inOrder(media);
        order.verify(media).getPlayableMusic("netease", "a1");
        order.verify(media).getPlayableMusic("netease", "a2");
        order.verify(media).getPlayableMusic("netease", "a3");
        verify(media, never()).getPlayableMusic("netease", "b1");

        player.setPositionForTest(180_000L);
        player.playerLoop();
        awaitPlaying("b1");
        verify(media, never()).getPlayableMusic("netease", "a4");
    }

    @Test
    void failedPrefetchStillTriesSameMembersPendingSong() {
        MusicQueueItem failed = add("a1", alice);
        add("b1", bob);
        MusicQueueItem pending = add("a2", alice);
        when(media.status(failed.music())).thenReturn(QueueItemStatus.FAILED);
        when(media.status(pending.music())).thenReturn(QueueItemStatus.PENDING);
        canPlay("a2");

        player.playerLoop();
        awaitPlaying("a2");
        verify(media, never()).getPlayableMusic("netease", "a1");
        verify(media, never()).getPlayableMusic("netease", "b1");
    }

    @Test
    void exhaustedMemberYieldsWithoutRepeatingRetainedFailures() {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem a1 = add("a1", alice);
        MusicQueueItem a2 = add("a2", alice);
        add("b1", bob);
        when(media.getPlayableMusic("netease", "a1")).thenThrow(new IllegalStateException("media preparation failed"));
        when(media.getPlayableMusic("netease", "a2")).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        canPlay("b1");

        player.playerLoop();
        awaitPlaying("b1");
        var order = inOrder(media);
        order.verify(media).getPlayableMusic("netease", "a1");
        order.verify(media).getPlayableMusic("netease", "a2");
        order.verify(media).getPlayableMusic("netease", "b1");
        verify(media, times(1)).getPlayableMusic("netease", "a1");
        verify(media, times(1)).getPlayableMusic("netease", "a2");
        assertTrue(queue.getItem(a1.queueId()).isPresent());
        assertTrue(queue.getItem(a2.queueId()).isPresent());
    }

    @Test
    void rejectedAuthorizationRetriesTheSameMembersOtherSource() {
        add("a1", alice);
        add("b1", bob);
        queue.add(new Music("a2", "a2", List.of("Artist"), 180_000L, "qqmusic", null, null), alice, QueueItemStatus.READY);
        var response = Sinks.<PlayableMusic>one();
        when(media.getPlayableMusic("netease", "a1")).thenReturn(response.asMono());
        when(media.getPlayableMusic("qqmusic", "a2")).thenReturn(Mono.just(playable("a2", "qqmusic")));

        player.playerLoop();
        verify(media, timeout(5000)).getPlayableMusic("netease", "a1");
        when(media.authorizationScope("netease")).thenReturn("new-scope");
        response.tryEmitValue(playable("a1", "netease"));
        awaitPlaying("a2");
        verify(media, never()).getPlayableMusic("netease", "b1");
    }

    @Test
    void retryHonorsPersonalPinBeforeOtherMembers() {
        add("a1", alice);
        add("a2", alice);
        MusicQueueItem pinned = add("a3", alice);
        add("b1", bob);
        var response = Sinks.<PlayableMusic>one();
        when(media.getPlayableMusic("netease", "a1")).thenReturn(response.asMono());
        canPlay("a3");

        player.playerLoop();
        verify(media, timeout(5000)).getPlayableMusic("netease", "a1");
        queue.top(pinned.queueId(), PlayMode.ROUND_ROBIN);
        response.tryEmitError(new IllegalStateException("unavailable"));
        awaitPlaying("a3");
        verify(media, never()).getPlayableMusic("netease", "a2");
        verify(media, never()).getPlayableMusic("netease", "b1");
    }

    @Test
    void exhaustingAllCandidatesDoesNotImmediatelyRetryTheSameTrackFromHistory() {
        MusicQueueItem track = add("a1", alice);
        queue.addToHistory(track.music());
        when(media.getPlayableMusic("netease", "a1")).thenReturn(Mono.error(new IllegalStateException("unavailable")));

        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        verify(media, times(1)).getPlayableMusic("netease", "a1");
    }

    @Test
    void manualSkipDiscardsLateFailureAndAdvancesToNextMember() {
        add("a1", alice);
        add("a2", alice);
        add("b1", bob);
        var response = Sinks.<PlayableMusic>one();
        when(media.getPlayableMusic("netease", "a1")).thenReturn(response.asMono());
        canPlay("b1");

        player.playerLoop();
        verify(media, timeout(5000)).getPlayableMusic("netease", "a1");
        player.skipToNext("SYSTEM");
        awaitPlaying("b1");
        response.tryEmitError(new IllegalStateException("late failure"));
        verify(media, never()).getPlayableMusic("netease", "a2");
    }

    @Test
    void stopsRetryingAfterThreeFailuresAndMovesToNextMember() {
        add("a1", alice);
        add("a2", alice);
        add("a3", alice);
        add("a4", alice);
        add("a5", alice);
        add("b1", bob);
        for (String id : List.of("a1", "a2", "a3", "a4")) {
            when(media.getPlayableMusic("netease", id)).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        }
        canPlay("b1");

        player.playerLoop();
        awaitPlaying("b1");

        var order = inOrder(media);
        order.verify(media).getPlayableMusic("netease", "a1");
        order.verify(media).getPlayableMusic("netease", "a2");
        order.verify(media).getPlayableMusic("netease", "a3");
        order.verify(media).getPlayableMusic("netease", "a4");
        order.verify(media).getPlayableMusic("netease", "b1");
        verify(media, never()).getPlayableMusic("netease", "a5");
    }
    private MusicQueueItem add(String id, UserSummary owner) {
        return queue.add(new Music(id, id, List.of("Artist"), 180_000L, "netease", null, null), owner, QueueItemStatus.READY);
    }

    private void canPlay(String id) {
        when(media.getPlayableMusic("netease", id)).thenReturn(Mono.just(playable(id, "netease")));
    }

    private PlayableMusic playable(String id, String source) {
        return new PlayableMusic(id, id, List.of("Artist"), 180_000L, source, "https://example.invalid/" + id, null, "SONG", null);
    }

    private void awaitPlaying(String id) {
        PlayerTestFactory.await(() -> {
            var current = player.getCurrentPlayerState().nowPlaying();
            return current != null && id.equals(current.music().id());
        });
    }
}

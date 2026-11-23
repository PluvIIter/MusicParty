package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicPlayerServiceFmCircuitBreakerTest {
    private static final UserSummary MEMBER = new UserSummary("member", "session", "Listener", false);
    private static final PlayableMusic FM_SONG = new PlayableMusic("fm-song", "FM Song", List.of("Artist"), 180_000,
            "netease", "https://audio.invalid/fm", null, "SONG", null);
    private static final PlayableMusic QUEUE_SONG = new PlayableMusic("queue-song", "Queue Song", List.of("Artist"), 180_000,
            "netease", "https://audio.invalid/queue", null, "SONG", null);

    private AppProperties properties;
    private UserService users;
    private MusicQueueManager queue;
    private PrivateDjService dj;
    private MusicPlayerService player;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getPrivateDj().setMode("FM");
        properties.getPrivateDj().setFillBlankEnabled(true);
        properties.getPrivateDj().setSource("netease");
        users = mock(UserService.class);
        queue = new MusicQueueManager(properties);
        dj = mock(PrivateDjService.class);
        when(dj.sourceId()).thenReturn("netease");
        when(dj.isAvailable()).thenReturn(true);
        when(dj.supportsMode("FM")).thenReturn(true);
        player = PlayerTestFactory.create(List.of(), users, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), properties, dj);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(MEMBER));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(MEMBER.token()));
    }

    @Test
    void fmFailureStopsAutomaticRetriesUntilManualPlay() {
        when(dj.nextSegment()).thenReturn(Mono.error(new ApiRequestException("MEDIA_FORBIDDEN: 网易云返回无可用播放地址")));

        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        verify(dj, timeout(2000)).nextSegment();

        // 熔断后即使播放循环继续运行，也不再请求上游
        for (int i = 0; i < 5; i++) player.playerLoop();
        verify(dj, times(1)).nextSegment();

        // 用户手动点击播放：解除熔断并重试一次
        PrivateDjSegment segment = new PrivateDjSegment.Song("fm-song", "FM Song", List.of("Artist"), 180_000, null);
        when(dj.authorizationScope()).thenReturn("recommendation-scope");
        when(dj.nextSegment()).thenReturn(Mono.just(segment));
        when(dj.getPlayable(segment)).thenReturn(Mono.just(FM_SONG));

        player.togglePause("session");
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertEquals("fm-song", player.getCurrentPlayerState().nowPlaying().music().id());
        verify(dj, times(2)).nextSegment();
    }

    @Test
    void fmMarkerIsRemovedWhileTheSourceIsBlockedAndStopsSpinning() {
        properties.getPrivateDj().setFillBlankEnabled(false);
        properties.getPrivateDj().setJoinQueueEnabled(true);
        properties.getPrivateDj().setCustodyEnabled(false);
        player.setPlayModeForTest(PlayMode.SHUFFLE);
        when(dj.nextFmSegment()).thenReturn(Mono.error(new ApiRequestException("MEDIA_FORBIDDEN: 网易云返回无可用播放地址")));

        player.syncFmMarkerForTest();
        assertTrue(hasFmMarker(), "加入队列模式下应先放入 FM 标记");

        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        verify(dj, timeout(2000)).nextFmSegment();

        // 熔断后标记不应被反复补回，也不应每秒重试上游
        for (int i = 0; i < 5; i++) player.playerLoop();
        verify(dj, times(1)).nextFmSegment();
        assertFalse(hasFmMarker(), "熔断期间不应保留 FM 标记");
    }

    private boolean hasFmMarker() {
        return queue.getQueueSnapshot().stream()
                .anyMatch(item -> MusicQueueManager.FM_MARKER_ID.equals(item.music().platform()));
    }

    @Test
    void playbackFromTheBlockedSourceClearsThatSourceOnly() {
        when(dj.nextSegment()).thenReturn(Mono.error(new ApiRequestException("MEDIA_FORBIDDEN: 网易云返回无可用播放地址")));
        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        verify(dj, timeout(2000)).nextSegment();

        // 队列里有一首同源歌曲：仍能正常播放（熔断只影响推荐），并在起播时解除该音源的熔断
        queue.add(new Music("queue-song", "Queue Song", List.of("Artist"), 180_000, "netease", null, null),
                MEMBER, QueueItemStatus.READY);
        when(PlayerTestFactory.media(player).getPlayableMusic("netease", "queue-song")).thenReturn(Mono.just(QUEUE_SONG));
        player.playerLoop();
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertEquals("queue-song", player.getCurrentPlayerState().nowPlaying().music().id());

        // 同源歌曲播放结束后，推荐可以重新尝试
        when(dj.nextSegment()).thenReturn(Mono.never());
        player.setPositionForTest(180_000L);
        player.playerLoop();
        verify(dj, timeout(2000).times(2)).nextSegment();
    }

    @Test
    void playbackFromAnotherSourceKeepsTheBlock() {
        when(dj.nextSegment()).thenReturn(Mono.error(new ApiRequestException("MEDIA_FORBIDDEN: 网易云返回无可用播放地址")));
        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        verify(dj, timeout(2000)).nextSegment();

        PlayableMusic otherSource = new PlayableMusic("other", "Other", List.of("Artist"), 180_000,
                "qqmusic", "https://audio.invalid/other", null, "SONG", null);
        queue.add(new Music("other", "Other", List.of("Artist"), 180_000, "qqmusic", null, null),
                MEMBER, QueueItemStatus.READY);
        when(PlayerTestFactory.media(player).getPlayableMusic("qqmusic", "other")).thenReturn(Mono.just(otherSource));
        player.playerLoop();
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);

        player.setPositionForTest(180_000L);
        player.playerLoop();
        verify(dj, timeout(1000).times(1)).nextSegment();
        verify(dj, never()).nextFmSegment();
    }
}

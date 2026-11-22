package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicPlayerServiceFmCircuitBreakerTest {
    private static final UserSummary MEMBER = new UserSummary("member", "session", "Listener", false);
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
        properties.getPrivateDj().setMode("FM");
        properties.getPrivateDj().setFillBlankEnabled(true);
        users = mock(UserService.class);
        queue = new MusicQueueManager(properties);
        dj = mock(PrivateDjService.class);
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
        PrivateDjSegment segment = new PrivateDjSegment.Song("song", "Song", List.of("Artist"), 180_000, null);
        when(dj.authorizationScope()).thenReturn("recommendation-scope");
        when(dj.nextSegment()).thenReturn(Mono.just(segment));
        when(dj.getPlayable(segment)).thenReturn(Mono.just(PLAYABLE));

        player.togglePause("session");
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertEquals("song", player.getCurrentPlayerState().nowPlaying().music().id());
        assertNotNull(player.getCurrentPlayerState().nowPlaying());
        verify(dj, times(2)).nextSegment();
    }
}

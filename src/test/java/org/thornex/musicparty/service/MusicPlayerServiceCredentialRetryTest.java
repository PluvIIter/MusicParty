package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.SourceCredentialAutoRefresh;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicPlayerServiceCredentialRetryTest {
    private static final UserSummary MEMBER = new UserSummary("member", "session", "Listener", false);
    private static final PlayableMusic PLAYABLE = new PlayableMusic("q1", "Song", List.of("Artist"), 180_000,
            "qqmusic", "https://audio.invalid/q1", null, "SONG", null);

    private UserService users;
    private MusicQueueManager queue;
    private SourceCredentialAutoRefresh autoRefresh;
    private MusicPlayerService player;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        users = mock(UserService.class);
        queue = new MusicQueueManager(properties);
        autoRefresh = mock(SourceCredentialAutoRefresh.class);
        player = PlayerTestFactory.create(List.of(), users, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), properties, mock(PrivateDjService.class), autoRefresh);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(MEMBER));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of(MEMBER.token()));
    }

    @Test
    void authFailureRefreshesTheCredentialAndRetriesTheSameSong() {
        when(autoRefresh.refresh("qqmusic")).thenReturn(true);
        queue.add(new Music("q1", "Song", List.of("Artist"), 180_000, "qqmusic", null, null), MEMBER, QueueItemStatus.READY);
        when(PlayerTestFactory.media(player).getPlayableMusic("qqmusic", "q1"))
                .thenReturn(Mono.error(new ApiRequestException("SOURCE_AUTH_REQUIRED: QQ音乐 Cookie 无效")),
                        Mono.just(PLAYABLE));

        player.playerLoop();

        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertEquals("q1", player.getCurrentPlayerState().nowPlaying().music().id());
        verify(autoRefresh, timeout(2000)).refresh("qqmusic");
    }

    @Test
    void failedRefreshKeepsTheNormalFailurePath() {
        when(autoRefresh.refresh("qqmusic")).thenReturn(false);
        queue.add(new Music("q1", "Song", List.of("Artist"), 180_000, "qqmusic", null, null), MEMBER, QueueItemStatus.READY);
        when(PlayerTestFactory.media(player).getPlayableMusic("qqmusic", "q1"))
                .thenReturn(Mono.error(new ApiRequestException("SOURCE_AUTH_REQUIRED: QQ音乐 Cookie 无效")));

        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());

        verify(autoRefresh, timeout(2000)).refresh("qqmusic");
        verify(PlayerTestFactory.media(player), times(1)).getPlayableMusic("qqmusic", "q1");
    }

    @Test
    void nonCredentialFailureDoesNotRefresh() {
        queue.add(new Music("q1", "Song", List.of("Artist"), 180_000, "qqmusic", null, null), MEMBER, QueueItemStatus.READY);
        when(PlayerTestFactory.media(player).getPlayableMusic("qqmusic", "q1"))
                .thenReturn(Mono.error(new ApiRequestException("MEDIA_FORBIDDEN: QQ音乐没有返回播放地址")));

        player.playerLoop();
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());

        verify(autoRefresh, never()).refresh("qqmusic");
    }
}

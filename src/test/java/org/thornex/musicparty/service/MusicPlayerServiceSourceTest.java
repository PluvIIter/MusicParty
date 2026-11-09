package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class MusicPlayerServiceSourceTest {

    @Test
    void allRetainedUrlsFailOnceThenCoolDownWithoutRecursiveLoop() {
        AppProperties config = new AppProperties();
        config.getQueue().setRetainPlaylist(true);
        MusicQueueManager queue = new MusicQueueManager(config);
        var member = new org.thornex.musicparty.dto.UserSummary("A", "s", "Alice", false);
        for (String id : List.of("1", "2")) queue.add(new org.thornex.musicparty.dto.Music(
                id, id, List.of(), 1000, "netease", ""), member, org.thornex.musicparty.enums.QueueItemStatus.READY);
        UserService users = mock(UserService.class);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(member));
        when(users.getRecentlyActiveUserTokens()).thenReturn(java.util.Set.of("A"));
        NeteaseMusicApiService netease = mock(NeteaseMusicApiService.class);
        when(netease.getPlatformName()).thenReturn("netease");
        when(netease.getPlayableMusic(anyString())).thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("test unavailable")));
        MusicPlayerService player = new MusicPlayerService(List.of(netease), users, mock(LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), config, netease, mock(PrivateDjService.class));
        player.playerLoop();
        verify(netease, times(2)).getPlayableMusic(anyString());
        assertEquals(2, queue.getQueueSnapshot().size());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertFalse(player.getCurrentPlayerState().isLoading());
    }

    private AppProperties props;
    private MusicQueueManager queueManager;
    private NeteaseMusicApiService api;
    private MusicPlayerService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        queueManager = mock(MusicQueueManager.class);
        api = mock(NeteaseMusicApiService.class);
        when(api.getPlatformName()).thenReturn("netease"); // 构造器 toMap 需要非空 key
        service = new MusicPlayerService(
                List.of(api),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                queueManager,
                mock(ApplicationEventPublisher.class),
                props,
                api,
                mock(PrivateDjService.class)
        );
    }

    @Test
    void modeOffNeverPlaysPrivateFmDj() {
        props.getPrivateDj().setMode("OFF");
        props.getPrivateDj().setCustodyEnabled(true);
        assertFalse(service.shouldPlayPrivateFmDj());
    }

    @Test
    void custodyOverridesQueue() {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setCustodyEnabled(true);
        when(queueManager.hasPlayableItems(anyMap())).thenReturn(true);
        assertTrue(service.shouldPlayPrivateFmDj(), "托管开启应无视队列有歌");
    }

    @Test
    void fillBlankOnlyWhenNoPlayableItem() {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setFillBlankEnabled(true);
        when(queueManager.hasPlayableItems(anyMap())).thenReturn(false);
        assertTrue(service.shouldPlayPrivateFmDj());
        when(queueManager.hasPlayableItems(anyMap())).thenReturn(true);
        assertFalse(service.shouldPlayPrivateFmDj(), "队列有有效歌曲时应走队列");
    }
}

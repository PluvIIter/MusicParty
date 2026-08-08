package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceStateTest {

    private MusicPlayerService build(AppProperties props, NeteaseMusicApiService api) {
        return build(props, api, mock(ApplicationEventPublisher.class));
    }

    private MusicPlayerService build(AppProperties props, NeteaseMusicApiService api, ApplicationEventPublisher publisher) {
        return new MusicPlayerService(
                List.of(),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                mock(MusicQueueManager.class),
                publisher,
                props,
                api,
                mock(PrivateDjService.class)
        );
    }

    @Test
    void configSummaryExposesPrivateDjAndCookieState() {
        AppProperties props = new AppProperties();
        props.getPrivateDj().setMode("DJ");
        props.getPrivateDj().setCustodyEnabled(true);
        NeteaseMusicApiService api = mock(NeteaseMusicApiService.class);
        when(api.isCookieConfigured()).thenReturn(true);

        PlayerState state = build(props, api).getCurrentPlayerState();

        assertTrue(state.config().neteaseCookieConfigured());
        assertEquals("DJ", state.config().privateDj().mode());
        assertTrue(state.config().privateDj().custodyEnabled());
    }

    @Test
    void syncHeartbeatBroadcastsWhileMusicPlaying() {
        AppProperties props = new AppProperties();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MusicPlayerService service = build(props, mock(NeteaseMusicApiService.class), publisher);

        // 模拟一首正在播放的歌曲
        service.applyFmDjSegmentForTest(
                new PlayableMusic("1", "Song", List.of("Artist"), 180_000L, "netease", "http://x/1.mp3", "http://x/1.jpg", false),
                new PrivateDjSegment.Song("1", "Song", List.of("Artist"), 180_000L, "http://x/1.jpg"));

        clearInvocations(publisher);

        service.broadcastSyncHeartbeat();

        verify(publisher, times(1)).publishEvent(any(PlayerStateEvent.class));
    }

    @Test
    void syncHeartbeatSkipsWhenIdleAndPaused() {
        AppProperties props = new AppProperties();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MusicPlayerService service = build(props, mock(NeteaseMusicApiService.class), publisher);

        service.setPausedForTest(true);
        clearInvocations(publisher);

        service.broadcastSyncHeartbeat();

        verify(publisher, never()).publishEvent(any());
    }
}

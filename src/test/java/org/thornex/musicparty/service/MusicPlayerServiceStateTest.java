package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceStateTest {

    private MusicPlayerService build(AppProperties props, NeteaseMusicApiService api) {
        return new MusicPlayerService(
                List.of(),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                mock(MusicQueueManager.class),
                mock(ApplicationEventPublisher.class),
                props,
                api,
                mock(PrivateDjService.class)
        );
    }

    @Test
    void configSummaryExposesPrivateDjAndCookieState() {
        AppProperties props = new AppProperties();
        props.getPrivateDj().setMasterEnabled(true);
        props.getPrivateDj().setMode("DJ");
        props.getPrivateDj().setCustodyEnabled(true);
        NeteaseMusicApiService api = mock(NeteaseMusicApiService.class);
        when(api.isCookieConfigured()).thenReturn(true);

        PlayerState state = build(props, api).getCurrentPlayerState();

        assertTrue(state.config().neteaseCookieConfigured());
        assertTrue(state.config().privateDj().masterEnabled());
        assertEquals("DJ", state.config().privateDj().mode());
        assertTrue(state.config().privateDj().custodyEnabled());
    }
}

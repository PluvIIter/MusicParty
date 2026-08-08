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

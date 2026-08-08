package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MusicPlayerServiceJoinQueueTest {

    private AppProperties props;
    private MusicQueueManager queueManager;
    private PrivateDjService djService;
    private MusicPlayerService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        queueManager = mock(MusicQueueManager.class);
        djService = mock(PrivateDjService.class);
        service = new MusicPlayerService(
                List.of(),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                queueManager,
                mock(ApplicationEventPublisher.class),
                props,
                mock(NeteaseMusicApiService.class),
                djService
        );
    }

    @Test
    void fmMarkerOnlyWhenShuffleModeOnAndJoinQueue() {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        service.setPlayModeForTest(PlayMode.SHUFFLE);
        service.syncFmMarkerForTest();
        verify(queueManager).ensureFmMarker();
        verify(queueManager, never()).removeFmMarker();

        service.setPlayModeForTest(PlayMode.SEQUENTIAL);
        service.syncFmMarkerForTest();
        verify(queueManager, times(1)).ensureFmMarker(); // 非随机不再添加
        verify(queueManager).removeFmMarker(); // 退出随机后移除陈旧标记
    }

    @Test
    void custodySuppressesFmMarker() {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        props.getPrivateDj().setCustodyEnabled(true);
        service.setPlayModeForTest(PlayMode.SHUFFLE);
        service.syncFmMarkerForTest();
        verify(queueManager, never()).ensureFmMarker(); // 托管忽略队列
        verify(queueManager).removeFmMarker(); // 托管时标记不应存在
    }

    @Test
    void modeOffRemovesFmMarker() {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        service.setPlayModeForTest(PlayMode.SHUFFLE);
        service.syncFmMarkerForTest();
        verify(queueManager).ensureFmMarker();

        props.getPrivateDj().setMode("OFF");
        service.syncFmMarkerForTest();
        verify(queueManager).removeFmMarker(); // 切到"关闭"后清除陈旧标记
        verify(queueManager, times(1)).ensureFmMarker(); // 不再添加
    }

    @Test
    void fmMarkerReplenishedImmediatelyWhenSelected() {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        service.setPlayModeForTest(PlayMode.SHUFFLE);
        when(djService.nextFmSegment()).thenReturn(Mono.empty());

        Music fmMusic = new Music(MusicQueueManager.FM_MARKER_ID, "私人FM", List.of("私人FM"), 0L,
                MusicQueueManager.FM_MARKER_ID, null);
        MusicQueueItem marker = new MusicQueueItem("qid", fmMusic,
                new UserSummary(MusicQueueManager.FM_MARKER_USER_TOKEN, MusicQueueManager.FM_MARKER_USER_TOKEN, "私人FM", false),
                QueueItemStatus.READY);
        service.playFmMarkerNextForTest(marker);

        verify(queueManager).ensureFmMarker(); // 选中即补回，播放期间队列常驻"私人FM"
    }

    @Test
    void voiceSegmentNeverEntersHistory() {
        PlayableMusic voice = new PlayableMusic(
                "voice1", "AI DJ", List.of("私人DJ"), 1000L,
                "netease", "http://x.mp3", null, false);
        service.applyFmDjSegmentForTest(voice,
                new PrivateDjSegment.Voice("http://x.mp3", "voice1", 1000L, "1", null));
        service.setPositionForTest(1000L);
        service.playerLoop();
        verify(queueManager, never()).addToHistory(any()); // DJ 语音段不进历史记录
    }
}

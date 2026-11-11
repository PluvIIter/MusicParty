package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
        service = PlayerTestFactory.create(
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

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void fmMarkerOnlyWhenRandomFamilyModeOnAndJoinQueue(PlayMode mode) {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        service.setPlayModeForTest(mode);
        service.syncFmMarkerForTest();
        verify(queueManager).ensureFmMarker();
        verify(queueManager, never()).removeFmMarker();

        service.setPlayModeForTest(PlayMode.SEQUENTIAL);
        service.syncFmMarkerForTest();
        verify(queueManager, times(1)).ensureFmMarker(); // 非随机不再添加
        verify(queueManager).removeFmMarker(); // 退出随机后移除陈旧标记
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void custodySuppressesFmMarker(PlayMode mode) {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        props.getPrivateDj().setCustodyEnabled(true);
        service.setPlayModeForTest(mode);
        service.syncFmMarkerForTest();
        verify(queueManager, never()).ensureFmMarker(); // 托管忽略队列
        verify(queueManager).removeFmMarker(); // 托管时标记不应存在
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void modeOffRemovesFmMarker(PlayMode mode) {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        service.setPlayModeForTest(mode);
        service.syncFmMarkerForTest();
        verify(queueManager).ensureFmMarker();

        props.getPrivateDj().setMode("OFF");
        service.syncFmMarkerForTest();
        verify(queueManager).removeFmMarker(); // 切到"关闭"后清除陈旧标记
        verify(queueManager, times(1)).ensureFmMarker(); // 不再添加
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void fmMarkerReplenishedImmediatelyWhenSelected(PlayMode mode) {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setJoinQueueEnabled(true);
        service.setPlayModeForTest(mode);
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
    void markerCapturesRecommendationRequestBeforeSchedulingWorker() {
        props.getPrivateDj().setMode("DJ");
        var captured = new java.util.concurrent.atomic.AtomicBoolean();
        Thread selector = Thread.currentThread();
        when(djService.nextFmSegment()).thenAnswer(invocation -> {
            assertSame(selector, Thread.currentThread());
            assertTrue(Thread.holdsLock(service));
            captured.set(true);
            return Mono.empty();
        });
        synchronized (service) { service.playFmMarkerNextForTest(null); }
        assertTrue(captured.get());
    }

    @Test
    void restoredUnsupportedDjDoesNotSilentlyInstallFmMarker() {
        props.getPrivateDj().setMode("DJ");
        props.getPrivateDj().setJoinQueueEnabled(true);
        when(djService.supportsMode("FM")).thenReturn(true);
        when(djService.supportsMode("DJ")).thenReturn(false);
        service.setPlayModeForTest(PlayMode.SHUFFLE);
        service.syncFmMarkerForTest();
        verify(queueManager, never()).ensureFmMarker();
        verify(queueManager).removeFmMarker();
    }

    @Test
    void modeChangeRejectsPlayableAlreadyWaitingForRoomCommit() throws Exception {
        props.getPrivateDj().setMode("DJ");
        when(djService.isAvailable()).thenReturn(true);
        when(djService.authorizationScope()).thenReturn("netease:DJ:1");
        var segment = new PrivateDjSegment.Voice("https://audio.invalid/voice", "voice", 1000, null, null);
        var playable = new PlayableMusic("voice", "DJ", List.of(), 1000, "netease", "https://audio.invalid/voice", null, false, "VOICE", null);
        var response = reactor.core.publisher.Sinks.<PlayableMusic>one();
        var callbackEntered = new java.util.concurrent.CountDownLatch(1);
        when(djService.getPlayable(segment)).thenReturn(response.asMono().doOnNext(value -> callbackEntered.countDown()));
        var load = MusicPlayerService.class.getDeclaredMethod("loadFmDjPlayable", PrivateDjSegment.class, long.class, boolean.class);
        load.setAccessible(true);
        java.util.concurrent.CompletableFuture<reactor.core.publisher.Sinks.EmitResult> emitter;
        synchronized (service) {
            load.invoke(service, segment, 0L, false);
            verify(djService, timeout(2000)).getPlayable(segment);
            emitter = java.util.concurrent.CompletableFuture.supplyAsync(() -> response.tryEmitValue(playable));
            assertTrue(callbackEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            props.getPrivateDj().setMode("OFF");
            when(djService.authorizationScope()).thenReturn("netease:OFF:2");
        }
        emitter.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNull(service.getCurrentPlayerState().nowPlaying());
        verify(queueManager, never()).addToHistory(any());
    }

    @Test
    void recommendationAuthorizationFailureInsideCommitClearsLoading() throws Exception {
        props.getPrivateDj().setMode("DJ");
        when(djService.isAvailable()).thenReturn(true);
        when(djService.authorizationScope()).thenReturn("scope")
                .thenThrow(new org.thornex.musicparty.exception.ApiRequestException("SOURCE_DISABLED"));
        var segment = new PrivateDjSegment.Song("song", "Song", List.of(), 1000, null);
        var response = reactor.core.publisher.Sinks.<PlayableMusic>one();
        when(djService.getPlayable(segment)).thenReturn(response.asMono());
        var loading = (java.util.concurrent.atomic.AtomicBoolean)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "isLoading");
        loading.set(true);
        var load = MusicPlayerService.class.getDeclaredMethod("loadFmDjPlayable", PrivateDjSegment.class, long.class, boolean.class);
        load.setAccessible(true);
        load.invoke(service, segment, 0L, false);
        verify(djService, timeout(2000)).getPlayable(segment);
        response.tryEmitValue(new PlayableMusic("song", "Song", List.of(), 1000, "netease", "https://audio.invalid/song", null, false));
        PlayerTestFactory.await(() -> !loading.get());
        assertNull(service.getCurrentPlayerState().nowPlaying());
    }

    @Test
    void fmSongPreservesIssuedMediaInstanceForRadioTimeline() {
        var song = new PlayableMusic("song", "Song", List.of(), 1000, "netease", "https://audio.invalid/song", null, false);
        service.applyFmDjSegmentForTest(song, new PrivateDjSegment.Song("song", "Song", List.of(), 1000, null));
        assertSame(song, service.getCurrentPlayerState().nowPlaying().music());
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

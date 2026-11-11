package org.thornex.musicparty.service.stream;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.source.ResourceKey;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveStreamMediaInputTest {
    @Test void mediaPreparationSubscribesOutsideRadioMonitor() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), new AppProperties());
        var music = new PlayableMusic("same", "Track", List.of(), 10000, "third", "https://cdn.test/old", "", false);
        when(delivery.sourceAvailable("third")).thenReturn(true);
        ReflectionTestUtils.setField(service, "currentMusic", music); ReflectionTestUtils.setField(service, "isPaused", false);
        CountDownLatch subscribed = new CountDownLatch(1); AtomicBoolean locked = new AtomicBoolean(true);
        when(delivery.serverInput(music)).thenReturn(Mono.defer(() -> {
            locked.set(Thread.holdsLock(service)); subscribed.countDown(); return Mono.empty();
        }));
        service.setEnabled(true);
        assertTrue(subscribed.await(3, TimeUnit.SECONDS)); assertFalse(locked.get());
        verify(delivery).serverInput(music);
        service.cleanup();
    }

    @Test void abandonedCompletionCannotBlockReplayOfSameTrack() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), new AppProperties());
        var music = new PlayableMusic("A", "A", List.of(), 180000, "third", "old", "", false);
        reactor.core.publisher.Sinks.One<org.thornex.musicparty.media.ServerInput> pending = reactor.core.publisher.Sinks.one();
        CountDownLatch subscribed = new CountDownLatch(1), emitted = new CountDownLatch(1), replay = new CountDownLatch(1);
        when(delivery.serverInput(music)).thenReturn(pending.asMono().doOnSubscribe(value -> subscribed.countDown()).doAfterTerminate(emitted::countDown),
                Mono.defer(() -> { replay.countDown(); return Mono.empty(); }));
        ReflectionTestUtils.setField(service, "currentMusic", music); ReflectionTestUtils.setField(service, "isPaused", false);
        service.setEnabled(true); assertTrue(subscribed.await(3, TimeUnit.SECONDS));
        ReflectionTestUtils.setField(service, "currentMusic", null); ReflectionTestUtils.invokeMethod(service, "checkState");
        pending.tryEmitValue(new org.thornex.musicparty.media.ServerInput("https://93.184.216.34/A", Map.of(), "A:full"));
        assertTrue(emitted.await(3, TimeUnit.SECONDS));
        ReflectionTestUtils.setField(service, "currentMusic", music); service.transcodeWatchdog();
        assertTrue(replay.await(3, TimeUnit.SECONDS)); verify(delivery, times(2)).serverInput(music);
        service.cleanup();
    }

    @Test void staleRequestCannotClearNewerRequestAndEmptyTerminalCanRetry() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), new AppProperties());
        var first = new PlayableMusic("A", "A", List.of(), 180000, "third", "old", "", false);
        var next = new PlayableMusic("B", "B", List.of(), 180000, "third", "old", "", false);
        var a = reactor.core.publisher.Sinks.<org.thornex.musicparty.media.ServerInput>one();
        var b = reactor.core.publisher.Sinks.<org.thornex.musicparty.media.ServerInput>one();
        CountDownLatch subscribedA = new CountDownLatch(1), subscribedB = new CountDownLatch(1), finishedA = new CountDownLatch(1), finishedB = new CountDownLatch(1);
        when(delivery.serverInput(first)).thenReturn(a.asMono().doOnSubscribe(value -> subscribedA.countDown()).doAfterTerminate(finishedA::countDown));
        when(delivery.serverInput(next)).thenReturn(b.asMono().doOnSubscribe(value -> subscribedB.countDown()).doAfterTerminate(finishedB::countDown));
        ReflectionTestUtils.setField(service, "currentMusic", first); ReflectionTestUtils.setField(service, "isPaused", false);
        service.setEnabled(true); assertTrue(subscribedA.await(3, TimeUnit.SECONDS));
        ReflectionTestUtils.setField(service, "currentMusic", next); ReflectionTestUtils.invokeMethod(service, "checkState");
        assertTrue(subscribedB.await(3, TimeUnit.SECONDS));
        a.tryEmitEmpty(); assertTrue(finishedA.await(3, TimeUnit.SECONDS));
        assertEquals(ResourceKey.encode("third", "B") + ":auth:scope:timeline:null", ReflectionTestUtils.getField(service, "resolvingResource"));
        b.tryEmitEmpty(); assertTrue(finishedB.await(3, TimeUnit.SECONDS));
        assertNull(ReflectionTestUtils.getField(service, "resolvingResource"));
        service.cleanup();
    }

    @Test void changedTimelineSameSourceIdAndAuthorizationRequestsNewRadioInput() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), new AppProperties());
        var full = new PlayableMusic("A", "A", List.of(), 180000, "third", "full", "", false);
        var trial = new PlayableMusic("A", "A", List.of(), 180000, "third", "trial", "", false);
        when(delivery.timelineIdentity(full)).thenReturn("full:180000"); when(delivery.timelineIdentity(trial)).thenReturn("trial:30000");
        ReflectionTestUtils.setField(service, "currentMusic", trial); ReflectionTestUtils.setField(service, "isPaused", false);
        ReflectionTestUtils.setField(service, "preparedResource", ResourceKey.encode("third", "A") + ":auth:scope:timeline:full:180000");
        Class<?> type = Class.forName(LiveStreamService.class.getName() + "$TranscodeTarget");
        var constructor = type.getDeclaredConstructor(String.class, Map.class, String.class); constructor.setAccessible(true);
        ReflectionTestUtils.setField(service, "preparedTarget", constructor.newInstance("old-full", Map.of(), "full"));
        CountDownLatch requested = new CountDownLatch(1);
        when(delivery.serverInput(trial)).thenReturn(Mono.defer(() -> { requested.countDown(); return Mono.empty(); }));
        service.setEnabled(true); assertTrue(requested.await(3, TimeUnit.SECONDS));
        verify(delivery).serverInput(trial); assertNull(ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
        service.cleanup();
    }

    @Test void alreadyPreparedNewTimelineRestartsOldProcessEvenWithSameTargetKey() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        var music = new PlayableMusic("A", "A", List.of(), 180000, "third", "trial", "", false);
        when(delivery.timelineIdentity(music)).thenReturn("trial");
        AppProperties properties = new AppProperties(); properties.setFfmpegPath("/nonexistent/musicparty-test-ffmpeg");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), properties);
        ReflectionTestUtils.setField(service, "currentMusic", music); ReflectionTestUtils.setField(service, "isPaused", false);
        ((AtomicBoolean) ReflectionTestUtils.getField(service, "isEnabled")).set(true);
        String prefix = ResourceKey.encode("third", "A") + ":auth:scope:timeline:";
        ReflectionTestUtils.setField(service, "preparedResource", prefix + "trial");
        ReflectionTestUtils.setField(service, "runningInputIdentity", prefix + "full");
        ReflectionTestUtils.setField(service, "runningSourceKey", "same-codec");
        Class<?> type = Class.forName(LiveStreamService.class.getName() + "$TranscodeTarget");
        var constructor = type.getDeclaredConstructor(String.class, Map.class, String.class); constructor.setAccessible(true);
        ReflectionTestUtils.setField(service, "preparedTarget", constructor.newInstance("https://93.184.216.34/trial", Map.of(), "same-codec"));
        Process old = mock(Process.class); when(old.isAlive()).thenReturn(true);
        ReflectionTestUtils.setField(service, "transcoderProcess", old); when(delivery.pinInput(any())).thenReturn(() -> {});
        ReflectionTestUtils.invokeMethod(service, "startTranscodingIfNeeded");
        verify(old).destroy(); verify(delivery).pinInput(any());
        assertEquals(prefix + "trial", ReflectionTestUtils.getField(service, "runningInputIdentity"));
        service.cleanup();
    }

    @Test void firstCrashRetriesImmediatelyAndRepeatedCrashBacksOffWithSilence() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        AppProperties properties = new AppProperties(); properties.setFfmpegPath("/nonexistent/musicparty-test-ffmpeg");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), properties);
        var music = new PlayableMusic("A", "A", List.of(), 180000, "third", "old", "", false);
        ReflectionTestUtils.setField(service, "currentMusic", music); ReflectionTestUtils.setField(service, "isPaused", false);
        ((AtomicBoolean) ReflectionTestUtils.getField(service, "isEnabled")).set(true);
        ((AtomicBoolean) ReflectionTestUtils.getField(service, "songActive")).set(true);
        ReflectionTestUtils.setField(service, "preparedResource", ResourceKey.encode("third", "A") + ":auth:scope:timeline:null");
        Class<?> type = Class.forName(LiveStreamService.class.getName() + "$TranscodeTarget");
        var constructor = type.getDeclaredConstructor(String.class, Map.class, String.class); constructor.setAccessible(true);
        ReflectionTestUtils.setField(service, "preparedTarget", constructor.newInstance("old", Map.of(), "A:full"));
        ReflectionTestUtils.setField(service, "runningSourceKey", "A:full");
        Process dead = mock(Process.class); when(dead.isAlive()).thenReturn(false); when(dead.exitValue()).thenReturn(1);
        ReflectionTestUtils.setField(service, "transcoderProcess", dead);
        CountDownLatch retried = new CountDownLatch(1);
        when(delivery.pinInput(any())).thenAnswer(call -> { retried.countDown(); return (AutoCloseable) () -> {}; });
        when(delivery.serverInput(music)).thenReturn(Mono.just(new org.thornex.musicparty.media.ServerInput("https://93.184.216.34/new", Map.of(), "A:full")));
        ReflectionTestUtils.invokeMethod(service, "handleTranscoderExit");
        assertTrue(retried.await(3, TimeUnit.SECONDS));
        assertFalse(((AtomicBoolean) ReflectionTestUtils.getField(service, "songActive")).get());
        synchronized (service) {
            ReflectionTestUtils.setField(service, "transcoderProcess", dead);
            ReflectionTestUtils.invokeMethod(service, "handleTranscoderExit");
        }
        verify(delivery, times(1)).serverInput(music);
        service.cleanup();
    }

    @Test void expiredOrUnassociatedVoiceStopsPreparedRadioTarget() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), new AppProperties());
        var voice = new PlayableMusic("voice:opaque", "DJ", List.of(), 10000, "third", "https://cdn.test/voice", "", false, "VOICE");
        ReflectionTestUtils.setField(service, "currentMusic", voice);
        Class<?> type = Class.forName(LiveStreamService.class.getName() + "$TranscodeTarget");
        var constructor = type.getDeclaredConstructor(String.class, Map.class, String.class); constructor.setAccessible(true);
        Object target = constructor.newInstance(voice.url(), Map.of(), "voice-representation");
        ReflectionTestUtils.setField(service, "preparedTarget", target);
        ReflectionTestUtils.setField(service, "preparedResource", ResourceKey.encode("third", voice.id()) + ":auth:scope:timeline:null");
        when(delivery.inputAvailable(voice)).thenReturn(true);
        assertSame(target, ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
        when(delivery.inputAvailable(voice)).thenReturn(false);
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
    }

    @Test void preparedTargetIsIsolatedBySourceAndTrackIdentity() throws Exception {
        var delivery = mock(MediaDeliveryService.class);
        when(delivery.sourceAvailable("third")).thenReturn(true); when(delivery.authorizationScope("third")).thenReturn("scope");
        var service = new LiveStreamService(delivery, mock(ApplicationEventPublisher.class), new AppProperties());
        var music = new PlayableMusic("same", "Track", List.of(), 10000, "third", "old", "", false);
        ReflectionTestUtils.setField(service, "currentMusic", music);
        Class<?> targetClass = Class.forName(LiveStreamService.class.getName() + "$TranscodeTarget");
        var constructor = targetClass.getDeclaredConstructor(String.class, Map.class, String.class); constructor.setAccessible(true);
        Object target = constructor.newInstance("https://cdn.test/audio", Map.of("Referer", "https://source.test/"), "representation");
        ReflectionTestUtils.setField(service, "preparedTarget", target);
        ReflectionTestUtils.setField(service, "preparedResource", ResourceKey.encode("another", "same"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
        ReflectionTestUtils.setField(service, "preparedResource", ResourceKey.encode("third", "same") + ":auth:scope:timeline:null");
        assertSame(target, ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
        when(delivery.authorizationScope("third")).thenReturn("updated-cookie-or-epoch");
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
    }
}

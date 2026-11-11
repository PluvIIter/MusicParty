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
        ReflectionTestUtils.setField(service, "preparedResource", ResourceKey.encode("third", "same") + ":auth:scope");
        assertSame(target, ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
        when(delivery.authorizationScope("third")).thenReturn("updated-cookie-or-epoch");
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveTarget"));
    }
}

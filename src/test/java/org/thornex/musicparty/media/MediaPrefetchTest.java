package org.thornex.musicparty.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.DeliveryMode;
import org.thornex.musicparty.source.ResourceKey;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaPrefetchTest {
    @TempDir Path directory;
    SourceRegistry registry;
    IMusicApiService adapter;
    LocalCacheService cache;
    MediaDeliveryService service;
    AtomicLong revision = new AtomicLong();
    AtomicLong epoch = new AtomicLong();
    boolean failFallback;
    CopyOnWriteArrayList<QueueItemStatus> observedStatuses = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
    Sinks.One<ClientResponse> primaryResponse = Sinks.one();
    Music music = new Music("track", "Track", List.of(), 120000, "stub", "", null);

    @BeforeEach void setup() {
        registry = mock(SourceRegistry.class);
        adapter = mock(IMusicApiService.class);
        when(registry.getRequired("stub")).thenReturn(adapter);
        when(registry.isEnabled("stub")).thenReturn(true);
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(registry.authorizationEpoch("stub")).thenAnswer(call -> epoch.get());
        when(adapter.credentialRevision()).thenAnswer(call -> revision.get());
        when(adapter.getMusic("track")).thenReturn(Mono.just(music));
        when(adapter.resolveMedia("track", MediaConsumer.SERVER)).thenAnswer(call -> Mono.just(media("primary")));
        when(adapter.resolveFallbackMedia("track")).thenAnswer(call -> Mono.just(media("fallback")));
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            String path = request.url().getPath();
            requests.add(path);
            if (path.equals("/primary")) return primaryResponse.asMono();
            return Mono.just(failFallback ? ClientResponse.create(HttpStatus.BAD_GATEWAY).build() : audio());
        }).build();
        cache = new LocalCacheService(client, event -> {
            assertTrue(List.of(ResourceKey.encode("stub", "track"), ResourceKey.encode("stub", "queued"))
                    .contains(((org.thornex.musicparty.event.DownloadStatusEvent) event).getMusicId()));
            if (service != null) observedStatuses.add(service.status(music));
        }, new AppProperties());
        ReflectionTestUtils.setField(cache, "root", directory.toAbsolutePath());
        cache.init();
        service = new MediaDeliveryService(registry, cache);
    }

    @AfterEach void cleanup() { cache.cleanup(); }

    ResolvedMedia media(String representation) {
        return new ResolvedMedia(new ResourceKey("stub", "track"), representation,
                "https://93.184.216.34/" + representation, "audio/mpeg", ".mp3",
                Instant.now().plusSeconds(600), Map.of(), false, "full");
    }

    ClientResponse audio() {
        byte[] bytes = new byte[64]; bytes[0] = 'I'; bytes[1] = 'D'; bytes[2] = '3';
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "audio/mpeg")
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))).build();
    }

    void await(BooleanSupplier ready) {
        Flux.interval(Duration.ZERO, Duration.ofMillis(10)).filter(tick -> ready.getAsBoolean())
                .next().block(Duration.ofSeconds(12));
    }

    @Test void ordinaryPrefetchObservesAsyncFailureAndSharesOneFallbackWithPlayback() {
        service.prefetch(music);
        await(() -> requests.contains("/primary"));
        var playback = service.getPlayableMusic("stub", "track").toFuture();
        for (int i = 0; i < 10; i++) service.prefetch(music);
        assertEquals(QueueItemStatus.DOWNLOADING, service.status(music));
        primaryResponse.tryEmitValue(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
        await(() -> service.status(music) == QueueItemStatus.READY);
        var playable = playback.join();
        assertTrue(playable.url().startsWith("/media/"));
        assertNotNull(cache.localPath(service.ticket(playable.url().substring(7)).assetKey()));
        assertEquals(List.of("/primary", "/fallback"), requests);
        await(() -> !observedStatuses.isEmpty() && observedStatuses.getLast() == QueueItemStatus.READY);
        assertFalse(observedStatuses.contains(QueueItemStatus.FAILED));
        verify(adapter).resolveFallbackMedia("track");
        verify(adapter, never()).resolveMedia(anyString(), eq(MediaConsumer.BROWSER));
    }

    @Test void completedPrefetchFallbackIsRevalidatedAndReusedByLaterPlayback() {
        service.prefetch(music);
        await(() -> requests.size() == 1);
        primaryResponse.tryEmitValue(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
        await(() -> service.status(music) == QueueItemStatus.READY && downloads().isEmpty());
        String ready = aliases().values().iterator().next();
        primaryResponse = Sinks.one();
        var playback = service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(2));
        assertEquals(ready, service.ticket(playback.url().substring(7)).assetKey());
        assertEquals(List.of("/primary", "/fallback"), requests);
        verify(adapter).resolveFallbackMedia("track");

        Map<String, Mono<ResolvedMedia>> fallbackResolutions = (Map<String, Mono<ResolvedMedia>>)
                ReflectionTestUtils.getField(service, "fallbackResolutions");
        fallbackResolutions.clear();
        ResolvedMedia changed = new ResolvedMedia(media("fallback").resource(), "fallback", media("fallback").url(),
                "audio/mpeg", ".mp3", Instant.now().plusSeconds(600), Map.of(), false, "trial");
        when(adapter.resolveFallbackMedia("track")).thenReturn(Mono.just(changed));
        var next = service.getPlayableMusic("stub", "track").toFuture();
        await(() -> requests.size() == 3);
        primaryResponse.tryEmitValue(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
        var trial = next.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
        assertNotEquals(ready, service.ticket(trial.url().substring(7)).assetKey());
        assertEquals("trial", service.timelineIdentity(trial));
        assertEquals(List.of("/primary", "/fallback", "/primary", "/fallback"), requests);
    }

    @Test void cleanupTerminatesInFlightAndQueuedMediaWaiters() {
        when(adapter.resolveFallbackMedia("queued")).thenReturn(Mono.error(
                new org.thornex.musicparty.exception.ApiRequestException("CAPABILITY_UNAVAILABLE: none")));
        when(adapter.resolveMedia("queued", MediaConsumer.SERVER)).thenReturn(Mono.just(new ResolvedMedia(
                new ResourceKey("stub", "queued"), "primary", media("primary").url(), "audio/mpeg", ".mp3",
                Instant.now().plusSeconds(600), Map.of(), false, "full")));
        Music queued = new Music("queued", "Queued", List.of(), 120000, "stub", "", null);
        service.prefetch(music);
        await(() -> requests.size() == 1);
        service.prefetch(queued);
        await(() -> aliases().size() == 2);
        assertEquals(QueueItemStatus.PENDING, service.status(queued));
        cache.cleanup();
        await(() -> downloads().isEmpty());
        assertEquals(QueueItemStatus.FAILED, service.status(music));
        assertEquals(QueueItemStatus.FAILED, service.status(queued));
        assertEquals(1, requests.size());
    }

    @SuppressWarnings("unchecked")
    Map<String, Mono<String>> downloads() {
        return (Map<String, Mono<String>>) ReflectionTestUtils.getField(service, "downloads");
    }

    @Test void fallbackDownloadFailureSettlesFailedAndManualRetryQueuesWork() {
        failFallback = true;
        service.prefetch(music);
        await(() -> requests.size() == 1);
        primaryResponse.tryEmitValue(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
        await(() -> service.status(music) == QueueItemStatus.FAILED);
        assertEquals(2, requests.size());
        verify(adapter).resolveFallbackMedia("track");
        service.retry(music);
        await(() -> requests.size() >= 3);
        await(() -> service.status(music) == QueueItemStatus.FAILED);
        assertEquals(List.of("/primary", "/fallback", "/primary", "/fallback"), requests);
        await(() -> !observedStatuses.isEmpty() && observedStatuses.getLast() == QueueItemStatus.FAILED);
        verify(adapter, never()).resolveMedia(anyString(), eq(MediaConsumer.BROWSER));
    }

    @Test void rotationDuringDownloadDiscardsBytesWithoutFallbackInNewScope() {
        service.prefetch(music);
        await(() -> requests.size() == 1);
        revision.incrementAndGet();
        primaryResponse.tryEmitValue(audio());
        await(() -> cache.getCacheEntry(aliases().values().iterator().next()).getStatus()
                == org.thornex.musicparty.enums.CacheStatus.FAILED);
        assertEquals(QueueItemStatus.PENDING, service.status(music));
        assertNull(cache.localPath(aliases().values().iterator().next()));
        verify(adapter, never()).resolveFallbackMedia(anyString());
    }

    @SuppressWarnings("unchecked")
    Map<String, String> aliases() {
        return (Map<String, String>) ReflectionTestUtils.getField(service, "downloadAliases");
    }
}

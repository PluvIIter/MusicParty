package org.thornex.musicparty.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.DeliveryMode;
import org.thornex.musicparty.source.ResourceKey;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaAuthorizationTest {
    SourceRegistry registry;
    IMusicApiService adapter;
    LocalCacheService cache;
    MediaDeliveryService service;
    AtomicLong revision = new AtomicLong();
    AtomicLong epoch = new AtomicLong();
    Music music = new Music("track", "Track", List.of(), 120000, "stub", "", null);

    @BeforeEach void setup() {
        registry = mock(SourceRegistry.class);
        adapter = mock(IMusicApiService.class);
        cache = mock(LocalCacheService.class);
        when(registry.getRequired("stub")).thenReturn(adapter);
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(registry.authorizationEpoch("stub")).thenAnswer(call -> epoch.get());
        when(adapter.credentialRevision()).thenAnswer(call -> revision.get());
        when(adapter.getMusic("track")).thenReturn(Mono.just(music));
        when(adapter.resolveMedia("track", MediaConsumer.SERVER)).thenReturn(Mono.error(new IllegalStateException("primary failed")));
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        service = new MediaDeliveryService(registry, cache);
    }

    ResolvedMedia media(ResourceKey resource, String url, Instant expiry, Map<String, String> headers) {
        return new ResolvedMedia(resource, "fallback", url, "audio/mpeg", ".mp3", expiry, headers, false, "full");
    }

    ResolvedMedia valid() {
        return media(new ResourceKey("stub", "track"), "https://93.184.216.34/audio",
                Instant.now().plusSeconds(100), Map.of());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void latePrimaryAndFallbackCannotMoveAcrossRevisionOrEpoch(boolean fallback, boolean rotateEpoch) throws Exception {
        Sinks.One<ResolvedMedia> late = Sinks.one();
        if (fallback) when(adapter.resolveFallbackMedia("track")).thenReturn(late.asMono());
        else when(adapter.resolveMedia("track", MediaConsumer.SERVER)).thenReturn(late.asMono());
        var result = service.getPlayableMusic("stub", "track").toFuture();
        if (fallback) verify(adapter, timeout(3000)).resolveFallbackMedia("track");
        else verify(adapter, timeout(3000)).resolveMedia("track", MediaConsumer.SERVER);
        if (rotateEpoch) epoch.incrementAndGet(); else revision.incrementAndGet();
        late.tryEmitValue(valid());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        verify(cache, never()).submitDownload(anyString(), any(), anyMap(), any());
        if (!fallback) verify(adapter, never()).resolveFallbackMedia(anyString());
    }

    @Test void fallbackRejectsWrongResourceExpiryUnsafeUrlHeadersAndEmptyResult() {
        for (Mono<ResolvedMedia> candidate : List.of(
                Mono.just(media(new ResourceKey("other", "track"), valid().url(), valid().expiresAt(), Map.of())),
                Mono.just(media(new ResourceKey("stub", "other"), valid().url(), valid().expiresAt(), Map.of())),
                Mono.just(media(valid().resource(), valid().url(), Instant.now().minusSeconds(1), Map.of())),
                Mono.just(media(valid().resource(), "http://127.0.0.1/audio", valid().expiresAt(), Map.of())),
                Mono.just(media(valid().resource(), valid().url(), valid().expiresAt(), Map.of("Cookie", "a\r\nb"))),
                Mono.<ResolvedMedia>empty())) {
            when(adapter.resolveFallbackMedia("track")).thenReturn(candidate);
            MediaDeliveryService isolated = new MediaDeliveryService(registry, cache);
            assertThrows(RuntimeException.class, () -> isolated.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3)));
        }
        verify(cache, never()).submitDownload(anyString(), any(), anyMap(), any());
        verify(adapter, never()).resolveMedia(anyString(), eq(MediaConsumer.BROWSER));
    }

    @Test void cachedFallbackIsRevalidatedForExpiry() {
        when(adapter.resolveFallbackMedia("track")).thenReturn(Mono.just(valid()));
        assertNotNull(service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3)));
        Map<String, Mono<ResolvedMedia>> cached = (Map<String, Mono<ResolvedMedia>>)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "fallbackResolutions");
        ResolvedMedia expired = media(valid().resource(), valid().url(), Instant.now().minusSeconds(1), Map.of());
        cached.replaceAll((key, value) -> Mono.just(expired).cache(Duration.ofSeconds(30)));
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3)));
        verify(adapter).resolveFallbackMedia("track");
        verify(cache).submitDownload(anyString(), any(), anyMap(), any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void bothRepresentationsKeepOriginalScopeThroughSourceAndCommitCallbacks(boolean fallback) {
        if (fallback) when(adapter.resolveFallbackMedia("track")).thenReturn(Mono.just(valid()));
        else when(adapter.resolveMedia("track", MediaConsumer.SERVER)).thenReturn(Mono.just(valid()));
        doAnswer(call -> {
            Mono<LocalCacheService.DownloadSource> source = call.getArgument(1);
            Runnable beforeCommit = call.getArgument(3);
            epoch.incrementAndGet();
            assertThrows(RuntimeException.class, source::block);
            assertThrows(RuntimeException.class, beforeCommit::run);
            return null;
        }).when(cache).submitDownload(anyString(), any(), anyMap(), any());
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3)));
        if (fallback) verify(adapter).resolveFallbackMedia("track");
        else verify(adapter, never()).resolveFallbackMedia(anyString());
    }

    @Test void pendingQueueDoesNotUseWorkerFiveMinuteDeadlineAndMissingEntryFails() {
        when(adapter.resolveMedia("track", MediaConsumer.SERVER)).thenReturn(Mono.just(valid()));
        var status = new java.util.concurrent.atomic.AtomicReference<>(CacheStatus.PENDING);
        when(cache.getStatus(anyString())).thenAnswer(call -> status.get());
        reactor.core.scheduler.Scheduler immediateTimer = new reactor.core.scheduler.Scheduler() {
            @Override public reactor.core.Disposable schedule(Runnable task) {
                task.run(); return reactor.core.Disposables.disposed();
            }
            @Override public reactor.core.Disposable schedule(Runnable task, long delay, TimeUnit unit) {
                throw new AssertionError("queue wait must not schedule an overall deadline");
            }
            @Override public reactor.core.Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
                status.set(CacheStatus.COMPLETED);
                task.run();
                return reactor.core.Disposables.disposed();
            }
            @Override public Worker createWorker() {
                return new Worker() {
                    @Override public reactor.core.Disposable schedule(Runnable task) { return immediate(task); }
                    private reactor.core.Disposable immediate(Runnable task) {
                        task.run(); return reactor.core.Disposables.disposed();
                    }
                    @Override public reactor.core.Disposable schedule(Runnable task, long delay, TimeUnit unit) {
                        throw new AssertionError("queue wait must not schedule an overall deadline");
                    }
                    @Override public reactor.core.Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
                        task.run();
                        status.set(CacheStatus.COMPLETED);
                        return immediate(task);
                    }
                    @Override public void dispose() {}
                };
            }
            @Override public void dispose() {}
        };
        var snapshot = reactor.core.scheduler.Schedulers.setFactoryWithSnapshot(new reactor.core.scheduler.Schedulers.Factory() {
            @Override public reactor.core.scheduler.Scheduler newParallel(int parallelism, java.util.concurrent.ThreadFactory factory) {
                return immediateTimer;
            }
        });
        try {
            String scope = service.authorizationScope("stub");
            Mono<String> awaiting = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    service, "awaitDownload", "stub", "queued", scope);
            assertEquals("queued", awaiting.block(Duration.ofSeconds(1)));
        } finally { reactor.core.scheduler.Schedulers.resetFrom(snapshot); }
        when(cache.getStatus(anyString())).thenReturn(null);
        when(adapter.resolveFallbackMedia("track")).thenReturn(Mono.error(new IllegalStateException("fallback failed")));
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3)));
    }

    @Test void synchronousFailureCanImmediatelyStartAnotherPreparation() {
        when(adapter.resolveFallbackMedia("track")).thenReturn(Mono.error(new IllegalStateException("fallback failed")));
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "track")
                .onErrorResume(error -> service.getPlayableMusic("stub", "track"))
                .block(Duration.ofSeconds(3)));
        verify(cache, times(2)).publishStatus(anyString());
        Map<?, ?> pending = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "downloads");
        assertTrue(pending.isEmpty());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void newScopeResolvesFallbackAgainAndInvalidatesPriorTicket(boolean rotateEpoch) {
        when(adapter.resolveFallbackMedia("track")).thenAnswer(call -> Mono.just(valid()));
        var first = service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3));
        var firstTicket = service.ticket(first.url().substring(7));
        if (rotateEpoch) epoch.incrementAndGet(); else revision.incrementAndGet();
        var second = service.getPlayableMusic("stub", "track").block(Duration.ofSeconds(3));
        var secondTicket = service.ticket(second.url().substring(7));
        assertNotEquals(firstTicket.assetKey(), secondTicket.assetKey());
        assertThrows(RuntimeException.class, () -> service.ticket(first.url().substring(7)));
        assertEquals(revision.get(), secondTicket.revision());
        assertEquals(epoch.get(), secondTicket.epoch());
        verify(adapter, times(2)).resolveFallbackMedia("track");
    }
}

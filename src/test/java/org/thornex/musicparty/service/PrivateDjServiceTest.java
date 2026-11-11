package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateDjServiceTest {
    private AppProperties properties;
    private SourceRegistry sources;
    private RecommendationProvider provider;
    private PrivateDjService service;
    private final PrivateDjSegment.Song first = new PrivateDjSegment.Song("1", "First", List.of(), 1000, null);
    private final PrivateDjSegment.Song second = new PrivateDjSegment.Song("2", "Second", List.of(), 1000, null);

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getPrivateDj().setMode("FM");
        properties.getPrivateDj().setSource("public-feed");
        sources = mock(SourceRegistry.class);
        provider = mock(RecommendationProvider.class);
        when(provider.sourceId()).thenReturn("public-feed");
        when(provider.supportedModes()).thenReturn(Set.of("FM", "DJ"));
        when(sources.isEnabled("public-feed")).thenReturn(true);
        when(sources.descriptor("public-feed")).thenReturn(new SourceDescriptor("public-feed", "Feed", "music",
                new SourceCapabilities(false, false, false, false, false, true, false, false),
                Set.of(DeliveryMode.DIRECT_URL), List.of()));
        when(provider.nextSegments(anyString())).thenReturn(Mono.just(List.of(first, second)));
        service = new PrivateDjService(List.of(provider), sources, properties);
    }

    @Test
    void publicProviderYieldsBatchInOrderAndRefills() {
        assertTrue(service.isAvailable());
        assertEquals(first, service.nextSegment().block());
        assertEquals(second, service.nextSegment().block());
        assertEquals(first, service.nextSegment().block());
        verify(provider, times(2)).nextSegments("FM");
    }

    @Test
    void modeChangeAndInvalidationDiscardCachedBatch() {
        service.nextSegment().block();
        properties.getPrivateDj().setMode("DJ");
        assertEquals(first, service.nextSegment().block());
        verify(provider).nextSegments("DJ");
        service.invalidate();
        assertEquals(first, service.nextFmSegment().block());
        verify(provider, times(2)).nextSegments("FM");
    }

    @Test
    void noPublicProviderIsExplicitlyUnavailableWithoutAccountFallback() {
        service = new PrivateDjService(List.of(), sources, properties);
        assertFalse(service.isAvailable());
        ApiRequestException error = assertThrows(ApiRequestException.class, () -> service.nextSegment().block());
        assertTrue(error.getMessage().contains("CAPABILITY_UNAVAILABLE"));
        verify(provider, never()).nextSegments(anyString());
        verify(provider, never()).getPlayable(any());
    }

    @Test
    void disabledProviderCannotServeEvenCachedContent() {
        service.nextSegment().block();
        when(sources.isEnabled("public-feed")).thenReturn(false);
        assertFalse(service.isAvailable());
        assertThrows(ApiRequestException.class, () -> service.nextSegment().block());
        verify(provider).nextSegments("FM");
    }

    @Test
    void invalidationRejectsStaleBatchAndDoesNotLeakIt() {
        Sinks.One<List<PrivateDjSegment>> response = Sinks.one();
        when(provider.nextSegments("FM")).thenReturn(response.asMono());
        var result = service.nextSegment().toFuture();
        service.invalidate();
        response.tryEmitValue(List.of(second));
        assertTrue(result.isCompletedExceptionally());
        when(provider.nextSegments("FM")).thenReturn(Mono.just(List.of(first)));
        assertEquals(first, service.nextSegment().block());
    }

    @Test
    void djIsOptionalExtensionOfConfiguredFmProvider() {
        when(provider.supportedModes()).thenReturn(Set.of("FM"));
        assertTrue(service.supportsMode("FM"));
        assertFalse(service.supportsMode("DJ"));
        assertThrows(ApiRequestException.class, () -> service.nextDjSegment().block());
        verify(provider, never()).nextSegments("DJ");
        assertEquals(first, service.nextFmSegment().block());
    }

    @Test
    void djOnlyProviderCannotRegisterWithoutBaseFmCapability() {
        when(provider.supportedModes()).thenReturn(Set.of("DJ"));
        assertThrows(IllegalArgumentException.class, () -> new PrivateDjService(List.of(provider), sources, properties));
    }

    @Test
    void configuredSourceNeverFallsBackToAnotherAvailableProvider() {
        properties.getPrivateDj().setSource("missing");
        assertFalse(service.isAvailable());
        assertThrows(ApiRequestException.class, () -> service.nextSegment().block());
        verify(provider, never()).nextSegments(anyString());
    }

    @Test
    void credentialChangeDiscardsBatchAndRejectsHandedOutSegment() {
        var old = service.nextSegment().block();
        when(provider.credentialRevision()).thenReturn(1L);
        assertThrows(ApiRequestException.class, () -> service.getPlayable(old).block());
        assertEquals(first, service.nextSegment().block());
        verify(provider, times(2)).nextSegments("FM");
    }

    @Test
    void selectionChangesWhileFetchingCannotCommitOldBatch() {
        Sinks.One<List<PrivateDjSegment>> response = Sinks.one();
        when(provider.nextSegments("FM")).thenReturn(response.asMono());
        var future = service.nextSegment().toFuture();
        properties.getPrivateDj().setSource("missing");
        response.tryEmitValue(List.of(first));
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void requestCreatedBeforeDisableCannotBecomeNewGenerationOnSubscribe() {
        var request = service.nextSegment();
        properties.getPrivateDj().setMode("OFF");
        service.invalidate();
        assertThrows(ApiRequestException.class, request::block);
        verify(provider, never()).nextSegments(anyString());
    }

    @Test
    void disableDuringAuthorizationSnapshotRejectsOldRequestBeforeBatchCreation() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(provider.credentialRevision()).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return 0L;
        });
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.nextSegment().block());
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            properties.getPrivateDj().setMode("OFF");
            service.invalidate();
        } finally {
            release.countDown();
        }
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        verify(provider, never()).nextSegments(anyString());
    }

    @Test
    void forcedFmMarkerStillRejectsRequestAfterConfiguredModeChanges() {
        properties.getPrivateDj().setMode("DJ");
        var request = service.nextFmSegment();
        properties.getPrivateDj().setMode("FM");
        service.invalidate();
        assertThrows(ApiRequestException.class, request::block);
    }

    @Test
    void concurrentRequestsShareOneFetchButReceiveDifferentSegments() {
        Sinks.One<List<PrivateDjSegment>> response = Sinks.one();
        when(provider.nextSegments("FM")).thenReturn(response.asMono());
        var one = service.nextSegment().toFuture();
        var two = service.nextSegment().toFuture();
        response.tryEmitValue(List.of(first, second));
        assertNotEquals(one.join(), two.join());
        verify(provider).nextSegments("FM");
    }

    @Test
    void playableResolutionUsesProviderRatherThanInventingTrackSource() {
        service.nextSegment().block();
        var playable = new PlayableMusic("1", "First", List.of(), 1000, "public-feed", "https://audio.invalid", null, false);
        when(provider.getPlayable(first)).thenReturn(Mono.just(playable));
        assertSame(playable, service.getPlayable(first).block());
        verify(sources, atLeastOnce()).getRequired("public-feed");
    }
}

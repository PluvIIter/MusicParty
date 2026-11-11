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
        sources = mock(SourceRegistry.class);
        provider = mock(RecommendationProvider.class);
        when(provider.sourceId()).thenReturn("public-feed");
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
        verifyNoInteractions(provider);
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
    void playableResolutionUsesProviderRatherThanInventingTrackSource() {
        service.nextSegment().block();
        var playable = new PlayableMusic("1", "First", List.of(), 1000, "public-feed", "https://audio.invalid", null, false);
        when(provider.getPlayable(first)).thenReturn(Mono.just(playable));
        assertSame(playable, service.getPlayable(first).block());
        verify(sources, times(2)).getRequired("public-feed");
    }
}

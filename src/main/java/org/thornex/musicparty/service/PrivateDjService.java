package org.thornex.musicparty.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PrivateDjService {
    private final List<RecommendationProvider> providers;
    private final SourceRegistry sources;
    private final AppProperties appProperties;
    private final Deque<PrivateDjSegment> batch = new ArrayDeque<>();
    private RecommendationProvider activeProvider;
    private String activeMode;
    private long generation;

    public boolean isAvailable() {
        return providers.stream().anyMatch(provider -> sources.isEnabled(provider.sourceId())
                && sources.descriptor(provider.sourceId()).capabilities().recommendations());
    }

    public Mono<PrivateDjSegment> nextSegment() {
        return next(appProperties.getPrivateDj().getMode());
    }

    public Mono<PrivateDjSegment> nextFmSegment() {
        return next("FM");
    }

    public Mono<PrivateDjSegment> nextDjSegment() {
        return next("DJ");
    }

    public synchronized void invalidate() {
        batch.clear();
        activeProvider = null;
        activeMode = null;
        generation++;
    }

    private Mono<PrivateDjSegment> next(String mode) {
        return Mono.defer(() -> {
            RecommendationProvider provider = providers.stream()
                    .filter(candidate -> sources.isEnabled(candidate.sourceId()))
                    .filter(candidate -> sources.descriptor(candidate.sourceId()).capabilities().recommendations())
                    .findFirst().orElseThrow(() -> new ApiRequestException("CAPABILITY_UNAVAILABLE: recommendations"));
            long version;
            synchronized (this) {
                if (provider != activeProvider || !mode.equals(activeMode)) {
                    invalidate();
                    activeProvider = provider;
                    activeMode = mode;
                }
                if (!batch.isEmpty()) return Mono.just(batch.removeFirst());
                version = generation;
            }
            return provider.nextSegments(mode).flatMap(segments -> {
                synchronized (this) {
                    if (generation != version) return Mono.error(new ApiRequestException("Recommendations changed"));
                    sources.getRequired(provider.sourceId());
                    batch.addAll(segments);
                    if (batch.isEmpty()) return Mono.error(new ApiRequestException("Recommendations returned no content"));
                    return Mono.just(batch.removeFirst());
                }
            });
        });
    }

    public Mono<PlayableMusic> getPlayable(PrivateDjSegment segment) {
        return Mono.defer(() -> {
            RecommendationProvider provider;
            synchronized (this) { provider = activeProvider; }
            if (provider == null) return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: recommendations"));
            sources.getRequired(provider.sourceId());
            return provider.getPlayable(segment);
        });
    }
}

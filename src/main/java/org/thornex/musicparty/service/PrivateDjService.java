package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PrivateDjService {
    private final Map<String, RecommendationProvider> providers;
    private final SourceRegistry sources;
    private final AppProperties properties;
    private final Deque<PrivateDjSegment> batch = new ArrayDeque<>();
    private final Map<PrivateDjSegment, Origin> origins = new java.util.IdentityHashMap<>();
    private String activeScope;
    private String activeMode;
    private long generation;
    private Mono<List<PrivateDjSegment>> pending;

    private record Origin(RecommendationProvider provider, String scope, long generation) {}

    public PrivateDjService(List<RecommendationProvider> providers, SourceRegistry sources, AppProperties properties) {
        var registered = new HashMap<String, RecommendationProvider>();
        for (var provider : providers) {
            var modes = provider.supportedModes();
            if (modes == null || !modes.contains("FM") || !java.util.Set.of("FM", "DJ").containsAll(modes)) {
                throw new IllegalArgumentException("Recommendation provider must support FM; DJ is an optional extension");
            }
            if (registered.putIfAbsent(provider.sourceId(), provider) != null) {
                throw new IllegalArgumentException("Duplicate recommendation provider: " + provider.sourceId());
            }
        }
        this.providers = Map.copyOf(registered);
        this.sources = sources;
        this.properties = properties;
    }

    public boolean isAvailable() { return supportsMode("FM"); }

    public boolean supportsMode(String mode) {
        try { return selected().supportedModes().contains(mode); }
        catch (ApiRequestException error) { return false; }
    }

    public String sourceId() {
        return properties.getPrivateDj().getSource();
    }

    public synchronized String authorizationScope() {
        return scope(selected()) + ":" + properties.getPrivateDj().getMode() + ":" + generation;
    }

    public Mono<PrivateDjSegment> nextSegment() { return next(properties.getPrivateDj().getMode()); }
    public Mono<PrivateDjSegment> nextFmSegment() { return next("FM"); }
    public Mono<PrivateDjSegment> nextDjSegment() { return next("DJ"); }

    public synchronized void invalidate() {
        batch.clear();
        origins.clear();
        activeScope = null;
        activeMode = null;
        pending = null;
        generation++;
    }

    private RecommendationProvider selected() {
        String source = sourceId();
        var provider = providers.get(source);
        if (source == null || source.isBlank() || provider == null) {
            throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 配置的私人FM/DJ音源没有推荐实现");
        }
        if (!sources.isEnabled(source)) throw new ApiRequestException("SOURCE_DISABLED: 私人FM/DJ音源已禁用");
        if (!sources.descriptor(source).capabilities().recommendations()) {
            throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 配置的音源不支持私人FM/DJ");
        }
        sources.getRequired(source);
        return provider;
    }

    private String scope(RecommendationProvider provider) {
        return provider.sourceId() + ":" + provider.credentialRevision() + ":" + sources.authorizationEpoch(provider.sourceId());
    }

    private Mono<PrivateDjSegment> next(String mode) {
        return Mono.defer(() -> {
            if (!"FM".equals(mode) && !"DJ".equals(mode)) {
                return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 私人FM/DJ已关闭"));
            }
            var provider = selected();
            if (!provider.supportedModes().contains(mode)) {
                return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 配置的私人FM音源不支持私人DJ"));
            }
            String expected = scope(provider);
            long version;
            Mono<List<PrivateDjSegment>> fetch;
            synchronized (this) {
                if (!expected.equals(activeScope) || !mode.equals(activeMode)) {
                    invalidate();
                    activeScope = expected;
                    activeMode = mode;
                }
                if (!batch.isEmpty()) return Mono.just(remember(batch.removeFirst(), provider, expected));
                version = generation;
                if (pending == null) {
                    pending = Mono.defer(() -> provider.nextSegments(mode)).timeout(Duration.ofSeconds(15)).cache();
                }
                fetch = pending;
            }
            return fetch.flatMap(segments -> {
                synchronized (this) {
                    if (generation != version || selected() != provider || !expected.equals(scope(provider))) {
                        return Mono.error(new ApiRequestException("RECOMMENDATIONS_CHANGED: 推荐音源或凭据已变化"));
                    }
                    if (pending == fetch) {
                        pending = null;
                        batch.addAll(segments);
                    }
                    if (batch.isEmpty()) return Mono.error(new ApiRequestException("RECOMMENDATIONS_EMPTY: 推荐没有返回可播放内容"));
                    return Mono.just(remember(batch.removeFirst(), provider, expected));
                }
            }).doOnError(error -> {
                synchronized (this) { if (pending == fetch) pending = null; }
            });
        });
    }

    private PrivateDjSegment remember(PrivateDjSegment segment, RecommendationProvider provider, String expected) {
        if (origins.size() >= 256) origins.clear();
        origins.put(segment, new Origin(provider, expected, generation));
        return segment;
    }

    public Mono<PlayableMusic> getPlayable(PrivateDjSegment segment) {
        return Mono.defer(() -> {
            Origin origin;
            synchronized (this) { origin = origins.get(segment); }
            if (origin == null || selected() != origin.provider() || !origin.scope().equals(scope(origin.provider()))) {
                throw new ApiRequestException("RECOMMENDATIONS_CHANGED: 推荐内容已失效，请重新获取");
            }
            return origin.provider().getPlayable(segment).map(playable -> {
                synchronized (this) {
                    if (origin.generation() != generation || selected() != origin.provider()
                            || !origin.scope().equals(scope(origin.provider()))) {
                        throw new ApiRequestException("RECOMMENDATIONS_CHANGED: 推荐音源或凭据已变化");
                    }
                }
                if (!origin.provider().sourceId().equals(playable.platform())) {
                    throw new ApiRequestException("MEDIA_CONTENT_CHANGED: 推荐内容音源不匹配");
                }
                return playable;
            });
        });
    }
}

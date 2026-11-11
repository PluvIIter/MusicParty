package org.thornex.musicparty.media;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.source.DeliveryMode;
import org.thornex.musicparty.source.ResourceKey;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaDeliveryService {
    private final String authorizationSession = UUID.randomUUID().toString();
    private final SourceRegistry registry;
    private final LocalCacheService cache;
    private final Map<String, Mono<ResolvedMedia>> resolutions = new ConcurrentHashMap<>();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, String> downloadAliases = new ConcurrentHashMap<>();
    private final java.util.Set<String> failedDownloads = ConcurrentHashMap.newKeySet();
    private PlayableMusic activePlayback;
    private final Map<PlaybackIdentity, String> playbackTimelines = new java.util.HashMap<>();
    private final Map<PlaybackIdentity, String> playbackAssets = new java.util.HashMap<>();
    private final java.lang.ref.ReferenceQueue<PlayableMusic> playbackReferences = new java.lang.ref.ReferenceQueue<>();

    private static final class PlaybackIdentity extends java.lang.ref.WeakReference<PlayableMusic> {
        private final int hash;
        PlaybackIdentity(PlayableMusic music, java.lang.ref.ReferenceQueue<PlayableMusic> queue) {
            super(music, queue);
            hash = System.identityHashCode(music);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof PlaybackIdentity identity && get() != null && get() == identity.get();
        }
    }
    private final Map<String, String> assetTimelines = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 4096;
        }
    });
    private AutoCloseable playbackPin;
    private String playbackAsset;
    private final Map<String, VoiceAsset> voices = new ConcurrentHashMap<>();
    private static final Duration VOICE_LIFETIME = Duration.ofMinutes(30);
    private final Map<VoiceRequest, VoicePreparation> voicePreparations = new ConcurrentHashMap<>();
    private record VoiceRequest(String sourceId, PrivateDjSegment.Voice segment, String authorizationScope) {}
    private record VoicePreparation(Instant expiresAt, Mono<PlayableMusic> result) {}

    private record VoiceAsset(String sourceId, String id, String authorizationScope, ResolvedMedia media,
                              String assetKey, String deliveredUrl) {}

    record Ticket(String sourceId, String assetKey, long revision, long epoch, Instant expiresAt) {}

    public MediaDeliveryService(SourceRegistry registry, LocalCacheService cache) {
        this.registry = registry;
        this.cache = cache;
    }

    public Mono<Music> getMetadata(String sourceId, String id) {
        return Mono.defer(() -> registry.getRequired(sourceId).getMusic(id)
                .map(music -> {
                    var adapter = registry.getRequired(sourceId);
                    if (!sourceId.equals(music.platform()) || !id.equals(music.id())) throw new ApiRequestException("MEDIA_CONTENT_CHANGED");
                    return new Music(music.id(), music.name(), music.artists(), music.duration(), music.platform(), music.coverUrl(), adapter.externalUrl(id));
                }))
                .timeout(Duration.ofSeconds(15));
    }

    private Mono<ResolvedMedia> resolve(String sourceId, String id, MediaConsumer consumer) {
        return Mono.defer(() -> {
            var adapter = registry.getRequired(sourceId);
            if (resolutions.size() > 1024) resolutions.clear();
            long revision = adapter.credentialRevision();
            long epoch = registry.authorizationEpoch(sourceId);
            String key = ResourceKey.encode(sourceId, id) + ":" + revision + ":" + epoch + ":" + consumer;
            return resolutions.computeIfAbsent(key, ignored -> Mono.defer(() -> {
                        registry.getRequired(sourceId);
                        return adapter.resolveMedia(id, consumer);
                    }).timeout(Duration.ofSeconds(20)).flatMap(MediaUrlPolicy::validate)
                    .map(media -> {
                        if (!media.resource().equals(new ResourceKey(sourceId, id))) throw new ApiRequestException("MEDIA_CONTENT_CHANGED");
                        if (registry.getRequired(sourceId).credentialRevision() != revision || registry.authorizationEpoch(sourceId) != epoch) throw new ApiRequestException("媒体凭据已更新，请重试");
                        if (media.expiresAt() != null && !media.expiresAt().isAfter(Instant.now())) throw new ApiRequestException("媒体地址已过期");
                        if (consumer == MediaConsumer.BROWSER && (!media.browserSafe() || !media.headers().isEmpty())) {
                            throw new ApiRequestException("此媒体无法直接交付浏览器");
                        }
                        return media;
                    }).cache(Duration.ofSeconds(5)));
        });
    }

    public Mono<PlayableMusic> getPlayableMusic(String sourceId, String id) {
        return Mono.defer(() -> {
            registry.getRequired(sourceId);
            return getMetadata(sourceId, id).flatMap(music -> {
                if (registry.deliveryMode(sourceId) == DeliveryMode.DIRECT_URL) {
                    return resolve(sourceId, id, MediaConsumer.BROWSER).map(media -> {
                        PlayableMusic prepared = playable(music, media.url());
                        rememberTimeline(prepared, media.timelineKey());
                        return prepared;
                    });
                }
                return prepareDownload(music).flatMap(key -> awaitDownload(music.platform(), key)).map(key -> {
                    PlayableMusic prepared = playable(music, issueTicket(sourceId, key));
                    String timeline = cachedTimeline(key);
                    if (timeline == null) throw new ApiRequestException("MEDIA_CONTENT_CHANGED");
                    rememberTimeline(prepared, timeline);
                    synchronized (this) { playbackAssets.put(new PlaybackIdentity(prepared, playbackReferences), key); }
                    return prepared;
                });
            });
        });
    }

    public Mono<PlayableMusic> prepareVoice(String sourceId, PrivateDjSegment.Voice voice) {
        return Mono.defer(() -> {
            String scope = authorizationScope(sourceId);
            if (voice.durationMs() <= 0 || voice.durationMs() > 600000) return Mono.error(new ApiRequestException("VOICE_DURATION_INVALID"));
            Instant now = Instant.now();
            voicePreparations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            VoiceRequest request = new VoiceRequest(sourceId, voice, scope);
            VoicePreparation preparation = voicePreparations.computeIfAbsent(request, ignored -> {
                if (voicePreparations.size() >= 1024) throw new ApiRequestException("VOICE_MEDIA_CAPACITY_EXCEEDED");
                String id = "voice:" + UUID.randomUUID();
                Mono<PlayableMusic> result = prepareVoiceAsset(sourceId, voice, scope, id)
                        .cache(value -> VOICE_LIFETIME, error -> Duration.ZERO, () -> Duration.ZERO);
                return new VoicePreparation(now.plus(VOICE_LIFETIME), result);
            });
            return preparation.result().map(music -> {
                voiceAsset(music);
                return music;
            });
        });
    }

    private Mono<PlayableMusic> prepareVoiceAsset(String sourceId, PrivateDjSegment.Voice voice, String scope, String id) {
        return Mono.defer(() -> {
            requireAuthorization(sourceId, scope);
            Instant expiresAt = Instant.now().plus(VOICE_LIFETIME);
            ResolvedMedia media = new ResolvedMedia(new ResourceKey(sourceId, id), "voice:" + id,
                    voice.voiceUrl(), "audio/mpeg", ".mp3", expiresAt, Map.of(), true, "voice:" + id);
            return MediaUrlPolicy.validate(media).flatMap(validated -> {
                requireAuthorization(sourceId, scope);
                Mono<String> delivered;
                String key = assetKey(sourceId, id);
                if (registry.deliveryMode(sourceId) == DeliveryMode.SERVER_DOWNLOAD) {
                    cache.submitVoiceDownload(key, Mono.fromCallable(() -> {
                                requireAuthorization(sourceId, scope);
                                if (!expiresAt.isAfter(Instant.now())) throw new ApiRequestException("VOICE_MEDIA_EXPIRED");
                                return new LocalCacheService.DownloadSource(validated.url(), validated.extension(), validated.headers(),
                                        validated.mimeType(), validated.representationKey());
                            }), Map.of(), () -> requireAuthorization(sourceId, scope));
                    delivered = awaitVoice(sourceId, key, scope, expiresAt).map(asset -> issueTicket(sourceId, asset, expiresAt));
                } else {
                    delivered = Mono.just(validated.url());
                }
                return delivered.map(url -> {
                    requireAuthorization(sourceId, scope);
                    voices.entrySet().removeIf(entry -> !entry.getValue().media().expiresAt().isAfter(Instant.now()));
                    if (voices.size() >= 1024) throw new ApiRequestException("VOICE_MEDIA_CAPACITY_EXCEEDED");
                    voices.put(ResourceKey.encode(sourceId, id), new VoiceAsset(sourceId, id, scope, validated, key, url));
                    return new PlayableMusic(id, "DJ 语音", java.util.List.of("DJ"), Math.max(0, voice.durationMs()),
                            sourceId, url, voice.relatedCoverUrl(), "VOICE", null);
                });
            });
        });
    }

    private Mono<String> awaitVoice(String sourceId, String key, String scope, Instant expiresAt) {
        return Flux.interval(Duration.ZERO, Duration.ofMillis(100)).map(tick -> {
            requireAuthorization(sourceId, scope);
            if (!expiresAt.isAfter(Instant.now())) throw new ApiRequestException("VOICE_MEDIA_EXPIRED");
            CacheStatus status = cache.getStatus(key);
            if (status == CacheStatus.FAILED) throw new ApiRequestException("VOICE_MEDIA_PREPARATION_FAILED");
            return status == CacheStatus.COMPLETED ? key : "";
        }).filter(value -> !value.isEmpty()).next().timeout(Duration.ofMinutes(5));
    }

    private void requireAuthorization(String sourceId, String scope) {
        if (!scope.equals(authorizationScope(sourceId))) throw new ApiRequestException("媒体凭据已更新，请重新准备");
    }

    private VoiceAsset voiceAsset(PlayableMusic music) {
        VoiceAsset asset = voices.get(ResourceKey.encode(music.platform(), music.id()));
        if (asset == null || !asset.deliveredUrl().equals(music.url())) throw new ApiRequestException("VOICE_MEDIA_UNAVAILABLE");
        requireAuthorization(music.platform(), asset.authorizationScope());
        if (!asset.media().expiresAt().isAfter(Instant.now())) throw new ApiRequestException("VOICE_MEDIA_EXPIRED");
        return asset;
    }

    public void prefetch(Music music) {
        registry.getRequired(music.platform());
        if (registry.deliveryMode(music.platform()) != DeliveryMode.SERVER_DOWNLOAD) return;
        String logical = assetKey(music.platform(), music.id());
        prepareDownload(music).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(key -> failedDownloads.remove(logical), error -> failedDownloads.add(logical));
    }

    private Mono<String> prepareDownload(Music music) {
        return Mono.defer(() -> {
            String scope = authorizationScope(music.platform());
            String logical = assetKey(music.platform(), music.id());
            return resolve(music.platform(), music.id(), MediaConsumer.SERVER).map(media -> {
                requireAuthorization(music.platform(), scope);
                int separator = logical.lastIndexOf(":credentials:");
                String version = fingerprint(media.timelineKey().length() + ":" + media.timelineKey() + media.representationKey());
                String key = logical.substring(0, separator) + ":variant:" + version + logical.substring(separator);
                assetTimelines.put(key, media.timelineKey());
                downloadAliases.put(logical, key);
                failedDownloads.remove(logical);
                cache.submitDownload(key, Mono.just(new LocalCacheService.DownloadSource(media.url(), media.extension(), media.headers(),
                                media.mimeType(), media.representationKey() + ":timeline:" + media.timelineKey())), Map.of(),
                        () -> requireAuthorization(music.platform(), scope));
                return key;
            });
        });
    }

    private static String fingerprint(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private Mono<String> awaitDownload(String sourceId, String key) {
        String scope = authorizationScope(sourceId);
        return Flux.interval(Duration.ZERO, Duration.ofMillis(100)).map(tick -> {
            requireAuthorization(sourceId, scope);
            CacheStatus status = cache.getStatus(key);
            if (status == CacheStatus.FAILED) throw new ApiRequestException("媒体准备失败");
            return status == CacheStatus.COMPLETED ? key : "";
        }).filter(value -> !value.isEmpty()).next().timeout(Duration.ofMinutes(5));
    }

    private String assetKey(String sourceId, String id) {
        String resource = ResourceKey.encode(sourceId, id);
        return resource + ":credentials:" + registry.getRequired(sourceId).credentialRevision() + ":epoch:" + registry.authorizationEpoch(sourceId) + ":session:" + authorizationSession;
    }

    public QueueItemStatus status(Music music) {
        if (!registry.isEnabled(music.platform())) return QueueItemStatus.FAILED;
        if (registry.deliveryMode(music.platform()) == DeliveryMode.DIRECT_URL) return QueueItemStatus.READY;
        String logical = assetKey(music.platform(), music.id());
        if (failedDownloads.contains(logical)) return QueueItemStatus.FAILED;
        String alias = downloadAliases.get(logical);
        CacheStatus status = alias == null ? null : cache.getStatus(alias);
        if (status == null || status == CacheStatus.PENDING) return QueueItemStatus.PENDING;
        return switch (status) {
            case COMPLETED -> QueueItemStatus.READY;
            case DOWNLOADING -> QueueItemStatus.DOWNLOADING;
            case FAILED -> QueueItemStatus.FAILED;
            default -> QueueItemStatus.PENDING;
        };
    }

    public Mono<String> refreshSongUrl(PlayableMusic current) {
        return Mono.defer(() -> {
            if (!"SONG".equals(current.kind())) return Mono.error(new ApiRequestException("MEDIA_KIND_UNAVAILABLE"));
            String source = current.platform();
            String scope = authorizationScope(source);
            return playbackTimeline(current).flatMap(timeline -> {
                if (registry.deliveryMode(source) == DeliveryMode.SERVER_DOWNLOAD) {
                    return getMetadata(source, current.id()).flatMap(metadata -> {
                        return prepareDownload(metadata).flatMap(key -> awaitDownload(source, key)).map(key -> {
                            requireAuthorization(source, scope);
                            requireTimeline(timeline, cachedTimeline(key));
                            return issueTicket(source, key);
                        });
                    });
                }
                return resolve(source, current.id(), MediaConsumer.BROWSER).map(media -> {
                    requireAuthorization(source, scope);
                    requireTimeline(timeline, media.timelineKey());
                    return media.url();
                });
            });
        });
    }

    public Mono<ServerInput> serverInput(PlayableMusic music) {
        return Mono.defer(() -> {
            requireRadio(music.platform());
            if ("VOICE".equals(music.kind())) {
                VoiceAsset asset = voiceAsset(music);
                if (registry.deliveryMode(music.platform()) == DeliveryMode.SERVER_DOWNLOAD) {
                    var path = cache.localPath(asset.assetKey());
                    if (path == null) return Mono.error(new ApiRequestException("VOICE_MEDIA_UNAVAILABLE"));
                    return Mono.just(new ServerInput(path.toString(), Map.of(), asset.assetKey()));
                }
                return MediaUrlPolicy.validate(asset.media()).map(media -> {
                    requireRadio(music.platform());
                    voiceAsset(music);
                    return new ServerInput(media.url(), media.headers(), ResourceKey.encode(music.platform(), music.id()) + ":" + media.representationKey() + ":timeline:" + media.timelineKey());
                });
            }
            if (!"SONG".equals(music.kind())) return Mono.error(new ApiRequestException("MEDIA_KIND_UNAVAILABLE"));
            String scope = authorizationScope(music.platform());
            return playbackTimeline(music).flatMap(timeline -> {
                requireRadio(music.platform());
                requireAuthorization(music.platform(), scope);
                if (registry.deliveryMode(music.platform()) == DeliveryMode.SERVER_DOWNLOAD) {
                    return getMetadata(music.platform(), music.id()).flatMap(metadata -> {
                        return prepareDownload(metadata).flatMap(asset -> awaitDownload(music.platform(), asset)).map(asset -> {
                            requireRadio(music.platform());
                            requireAuthorization(music.platform(), scope);
                            requireTimeline(timeline, cachedTimeline(asset));
                            var readyPath = cache.localPath(asset);
                            if (readyPath == null) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                            return new ServerInput(readyPath.toString(), Map.of(), asset);
                        });
                    });
                }
                return resolve(music.platform(), music.id(), MediaConsumer.SERVER).map(media -> {
                    requireRadio(music.platform());
                    requireAuthorization(music.platform(), scope);
                    requireTimeline(timeline, media.timelineKey());
                    return new ServerInput(media.url(), media.headers(), ResourceKey.encode(music.platform(), music.id()) + ":" + media.representationKey() + ":timeline:" + media.timelineKey());
                });
            });
        });
    }

    private synchronized void prunePlaybackTimelines() {
        java.lang.ref.Reference<? extends PlayableMusic> expired;
        while ((expired = playbackReferences.poll()) != null) {
            playbackTimelines.remove(expired);
            playbackAssets.remove(expired);
        }
    }

    private synchronized void rememberTimeline(PlayableMusic music, String timeline) {
        prunePlaybackTimelines();
        playbackTimelines.put(new PlaybackIdentity(music, playbackReferences), timeline);
    }

    private synchronized Mono<String> playbackTimeline(PlayableMusic music) {
        prunePlaybackTimelines();
        String timeline = playbackTimelines.get(new PlaybackIdentity(music, null));
        return timeline == null ? Mono.error(new ApiRequestException("MEDIA_CONTENT_CHANGED")) : Mono.just(timeline);
    }

    public synchronized String timelineIdentity(PlayableMusic music) {
        if ("VOICE".equals(music.kind())) return voiceAsset(music).media().timelineKey();
        prunePlaybackTimelines();
        String timeline = playbackTimelines.get(new PlaybackIdentity(music, null));
        if (timeline == null) throw new ApiRequestException("MEDIA_CONTENT_CHANGED");
        return timeline;
    }

    private String cachedTimeline(String key) {
        String timeline = assetTimelines.get(key);
        if (timeline == null) {
            var entry = cache.getCacheEntry(key);
            timeline = entry == null ? null : entry.getRepresentationKey();
            int marker = timeline == null ? -1 : timeline.indexOf(":timeline:");
            timeline = marker >= 0 ? timeline.substring(marker + ":timeline:".length()) : null;
        }
        return timeline;
    }

    private void requireTimeline(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) throw new ApiRequestException("MEDIA_CONTENT_CHANGED");
    }

    private void requireRadio(String sourceId) {
        registry.getRequired(sourceId);
        if (!registry.descriptor(sourceId).capabilities().radioInput()) throw new ApiRequestException("CAPABILITY_UNAVAILABLE: radioInput");
    }

    public String authorizationScope(String sourceId) {
        return authorizationSession + ":" + registry.getRequired(sourceId).credentialRevision() + ":" + registry.authorizationEpoch(sourceId);
    }

    public boolean inputAvailable(PlayableMusic music) {
        if (!sourceAvailable(music.platform())) return false;
        if (!"VOICE".equals(music.kind())) return true;
        try { voiceAsset(music); return true; }
        catch (RuntimeException error) { return false; }
    }

    public boolean sourceAvailable(String sourceId) {
        return registry.isEnabled(sourceId) && registry.descriptor(sourceId).capabilities().radioInput();
    }

    public AutoCloseable pinInput(ServerInput input) {
        return input.location().startsWith("http") ? () -> {} : cache.pin(input.key());
    }

    @org.springframework.context.event.EventListener
    public synchronized void onPlayerState(org.thornex.musicparty.event.PlayerStateEvent event) {
        var now = event.getState().nowPlaying();
        activePlayback = now == null ? null : now.music();
        prunePlaybackTimelines();
        String next = null;
        if (now != null && registry.isEnabled(now.music().platform())) {
            try {
                next = "VOICE".equals(now.music().kind()) ? voiceAsset(now.music()).assetKey()
                        : playbackAssets.get(new PlaybackIdentity(now.music(), null));
            } catch (ApiRequestException ignored) { }
        }
        if (java.util.Objects.equals(next, playbackAsset)) return;
        if (playbackPin != null) {
            try { playbackPin.close(); } catch (Exception ignored) { }
            playbackPin = null;
        }
        playbackAsset = next;
        if (next != null && cache.localPath(next) != null) {
            try { playbackPin = cache.pin(next); } catch (IllegalStateException ignored) { }
        }
    }

    private String issueTicket(String source, String assetKey) {
        return issueTicket(source, assetKey, Instant.now().plusSeconds(1800));
    }

    private String issueTicket(String source, String assetKey, Instant expiresAt) {
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new ApiRequestException("媒体访问已过期");
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        long revision = registry.getRequired(source).credentialRevision();
        long epoch = registry.authorizationEpoch(source);
        String scopeSuffix = ":credentials:" + revision + ":epoch:" + epoch + ":session:" + authorizationSession;
        int namespaceEnd = assetKey.lastIndexOf(":credentials:");
        if (namespaceEnd < 0 || !assetKey.substring(namespaceEnd).equals(scopeSuffix)) throw new ApiRequestException("媒体凭据已更新，请重试");
        String token = UUID.randomUUID().toString();
        tickets.put(token, new Ticket(source, assetKey, revision, epoch, expiresAt));
        return "/media/" + token;
    }

    Ticket ticket(String token) {
        Ticket ticket = tickets.get(token);
        if (ticket == null || !ticket.expiresAt().isAfter(Instant.now())
                || registry.getRequired(ticket.sourceId()).credentialRevision() != ticket.revision()
                || registry.authorizationEpoch(ticket.sourceId()) != ticket.epoch()) {
            throw new ApiRequestException("媒体访问已过期");
        }
        return ticket;
    }

    private PlayableMusic playable(Music music, String url) {
        return new PlayableMusic(music.id(), music.name(), music.artists(), music.duration(), music.platform(), url, music.coverUrl(), "SONG", music.externalUrl());
    }
}

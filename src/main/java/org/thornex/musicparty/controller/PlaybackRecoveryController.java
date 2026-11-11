package org.thornex.musicparty.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

@RestController
public class PlaybackRecoveryController {
    private final MusicPlayerService player;
    private final AuthController auth;
    private final AppProperties properties;
    private final MediaDeliveryService delivery;
    private final SourceRegistry registry;
    private String cachedPlayback;
    private String cachedSource;
    private String cachedAuthorizationScope;
    private long cachedAt;
    private Mono<PlayableMusic> inFlight;

    public PlaybackRecoveryController(MusicPlayerService player, AuthController auth, AppProperties properties,
                                      MediaDeliveryService delivery, SourceRegistry registry) {
        this.player = player;
        this.auth = auth;
        this.properties = properties;
        this.delivery = delivery;
        this.registry = registry;
    }

    @GetMapping("/api/music/refresh/{sourceId}/{musicId}")
    public Mono<ResponseEntity<PlayableMusic>> refreshSource(@PathVariable String sourceId, @PathVariable String musicId,
            @RequestParam String playbackId, @RequestHeader(value = "X-Room-Password", required = false) String password) {
        return refreshPlayback(sourceId, musicId, playbackId, password);
    }

    @PostMapping("/api/v2/playbacks/{playbackId}/media/refresh")
    public Mono<ResponseEntity<PlayableMusic>> refreshCurrent(@PathVariable String playbackId,
            @RequestHeader(value = "X-Room-Password", required = false) String password) {
        return refreshPlayback(null, null, playbackId, password);
    }

    @GetMapping("/api/v2/playbacks/{playbackId}/media")
    public Mono<ResponseEntity<PlayableMusic>> currentMedia(@PathVariable String playbackId,
            @RequestHeader(value = "X-Room-Password", required = false) String password) {
        return refreshPlayback(null, null, playbackId, password);
    }

    private Mono<ResponseEntity<PlayableMusic>> refreshPlayback(String sourceId, String id, String playbackId, String password) {
        return Mono.defer(() -> {
            if (!authorized(password)) return Mono.just(ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build());
            PlayerState state = player.getCurrentPlayerState();
            PlayableMusic track = state.nowPlaying() == null ? null : state.nowPlaying().music();
            if (track == null || !Objects.equals(playbackId, state.playbackId()) || playbackId == null
                    || (sourceId != null && (!sourceId.equals(track.platform()) || !id.equals(track.id())))
                    || !registry.isEnabled(track.platform()) || !"SONG".equals(track.kind())) {
                return Mono.just(ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build());
            }
            return sharedRefresh(playbackId, track).flatMap(updated -> {
                var current = player.getCurrentPlayerState();
                if (!Objects.equals(playbackId, current.playbackId()) || current.nowPlaying() == null
                        || !track.id().equals(current.nowPlaying().music().id())
                        || !track.platform().equals(current.nowPlaying().music().platform()) || !registry.isEnabled(track.platform())) {
                    return Mono.just(ResponseEntity.status(409).cacheControl(CacheControl.noStore()).<PlayableMusic>build());
                }
                return Mono.just(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(updated));
            });
        });
    }

    private synchronized Mono<PlayableMusic> sharedRefresh(String playbackId, PlayableMusic track) {
        String authorizationScope = delivery.authorizationScope(track.platform());
        if (inFlight != null && Objects.equals(cachedPlayback, playbackId) && Objects.equals(cachedSource, track.platform())
                && Objects.equals(cachedAuthorizationScope, authorizationScope) && System.currentTimeMillis() - cachedAt < 5000) return inFlight;
        cachedPlayback = playbackId; cachedSource = track.platform(); cachedAuthorizationScope = authorizationScope; cachedAt = System.currentTimeMillis();
        inFlight = delivery.refreshSongUrl(track.platform(), track.id()).timeout(Duration.ofSeconds(20))
                .map(url -> {
                    if (!Objects.equals(delivery.authorizationScope(track.platform()), authorizationScope)) throw new ApiRequestException("媒体凭据已更新，请重试");
                    return new PlayableMusic(track.id(), track.name(), track.artists(), track.duration(), track.platform(), url, track.coverUrl(), false, track.kind(), track.externalUrl());
                }).onErrorMap(error -> new ApiRequestException("MEDIA_CONTENT_CHANGED".equals(error.getMessage())
                        ? "MEDIA_CONTENT_CHANGED" : "播放地址刷新失败，请稍后重试")).cache(Duration.ofSeconds(5));
        return inFlight;
    }

    private boolean authorized(String password) {
        String room = auth.getRawPassword();
        String admin = properties.getAdminPassword();
        return (admin != null && !admin.isBlank() && admin.equals(password))
                || (room != null && (room.isEmpty() || room.equals(password)));
    }
}

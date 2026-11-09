package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.MusicPlayerService;
import reactor.core.publisher.Mono;
import java.time.Duration;

/** Refresh an expired link for the current track without seeking or changing the shared player. */
@RestController
public class PlaybackRecoveryController {
    private final MusicPlayerService player;
    private final AuthController auth;
    private final AppProperties properties;
    private final NeteaseMusicApiService musicApi;
    private String cachedCookie;
    private PlayableMusic cachedTrack;
    private long cachedAt;
    private Mono<PlayableMusic> inFlight;

    public PlaybackRecoveryController(MusicPlayerService player, AuthController auth, AppProperties properties, NeteaseMusicApiService musicApi) {
        this.player = player;
        this.auth = auth;
        this.properties = properties;
        this.musicApi = musicApi;
    }

    @GetMapping("/api/music/refresh/netease/{musicId}")
    public synchronized Mono<ResponseEntity<PlayableMusic>> refresh(@PathVariable("musicId") String musicId,
            @RequestHeader(value = "X-Room-Password", required = false) String password) {
        String room = auth.getRawPassword();
        String admin = properties.getAdminPassword();
        boolean adminAccess = admin != null && !admin.isBlank() && admin.equals(password);
        if (!adminAccess && (room == null || (!room.isEmpty() && !room.equals(password)))) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build());
        }
        var state = player.getCurrentPlayerState();
        PlayableMusic track = state.nowPlaying() == null ? null : state.nowPlaying().music();
        if (!properties.getNetease().isEnabled() || track == null || !"netease".equals(track.platform()) || !track.id().equals(musicId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).build());
        }
        if (java.util.Objects.equals(cachedCookie, properties.getNetease().getCookie()) && track == cachedTrack && inFlight != null && System.currentTimeMillis() - cachedAt < 5000) return inFlight.map(this::response);
        cachedTrack = track;
        cachedCookie = properties.getNetease().getCookie();
        cachedAt = System.currentTimeMillis();
        inFlight = musicApi.refreshSongUrl(musicId)
                .timeout(Duration.ofSeconds(12))
                .map(url -> {
                    var current = player.getCurrentPlayerState();
                    if (current.nowPlaying() == null || current.nowPlaying().music() != track) {
                        throw new ApiRequestException("曲目已切换，请等待同步");
                    }
                    return new PlayableMusic(track.id(), track.name(), track.artists(), track.duration(), track.platform(), url, track.coverUrl(), track.needsProxy());
                })
                .onErrorMap(error -> new ApiRequestException("播放地址刷新失败，请稍后重试"))
                .cache(Duration.ofSeconds(5));
        return inFlight.map(this::response);
    }

    private ResponseEntity<PlayableMusic> response(PlayableMusic music) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(music);
    }

}

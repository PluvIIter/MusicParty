package org.thornex.musicparty.controller;

import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
@RestController
@RequestMapping("/api")
public class ApiController {

    private final Map<String, IMusicApiService> apiServiceMap;
    private final AppProperties appProperties;

    public ApiController(List<IMusicApiService> apiServices, AppProperties appProperties) {
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.appProperties = appProperties;
    }

    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of(
                "authorName", appProperties.getAuthorName(),
                "backWords", appProperties.getBackWords()
        );
    }

    private IMusicApiService getService(String platform) {
        IMusicApiService service = apiServiceMap.get(platform);
        if (service == null) {
            throw new ApiRequestException("Platform not supported: " + platform);
        }
        return service;
    }

    @GetMapping("/search/{platform}/{keyword}")
    public Mono<List<Music>> searchMusic(@PathVariable String platform, @PathVariable String keyword) {
        return getService(platform).searchMusic(keyword);
    }

    @GetMapping("/user/playlists/{platform}/{userId}")
    public Mono<List<Playlist>> getUserPlaylists(@PathVariable String platform, @PathVariable String userId) {
        return getService(platform).getUserPlaylists(userId);
    }

    @GetMapping("/playlist/songs/{platform}/{playlistId}")
    public Mono<List<Music>> getPlaylistSongs(@PathVariable String platform,
                                              @PathVariable String playlistId,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "20") int limit) {
        return getService(platform).getPlaylistMusics(playlistId, offset, limit);
    }

    @GetMapping("/user/search/{platform}/{keyword}")
    public Mono<List<UserSearchResult>> searchUsers(@PathVariable String platform, @PathVariable String keyword) {
        return getService(platform).searchUsers(keyword);
    }

    @GetMapping("/music/lyric/{platform}/{musicId}")
    public Mono<String> getLyric(@PathVariable String platform, @PathVariable String musicId) {
        return getService(platform).getLyric(musicId);
    }
}
package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AlbumInfo;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.resource.ResourceAccess;
import org.thornex.musicparty.resource.ResourceService;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ResourceService resources;
    private final AppProperties appProperties;
    private final AuthController auth;

    public ApiController(ResourceService resources, AppProperties appProperties, AuthController auth) {
        this.resources = resources;
        this.appProperties = appProperties;
        this.auth = auth;
    }

    @ModelAttribute
    public void authorize(HttpServletRequest request) {
        if ("/api/config".equals(request.getRequestURI())) return;
        ResourceAccess.authorize(request.getHeader("X-Room-Password"), auth, appProperties);
    }

    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of("authorName", appProperties.getAuthorName(), "backWords", appProperties.getBackWords());
    }

    @GetMapping("/search/{platform}/{keyword}")
    public Mono<List<Music>> searchMusic(@PathVariable String platform, @PathVariable String keyword) {
        return resources.search(platform, keyword);
    }

    @GetMapping("/album/{platform}/by-song/{musicId}")
    public Mono<AlbumInfo> getSongAlbum(@PathVariable String platform, @PathVariable String musicId) {
        return resources.albumInfo(platform, musicId, true);
    }

    @GetMapping("/user/playlists/{platform}/{userId}")
    public Mono<List<Playlist>> getUserPlaylists(@PathVariable String platform, @PathVariable String userId) {
        return resources.legacyCollections(platform, userId);
    }

    @GetMapping("/playlist/songs/{platform}/{playlistId}")
    public Mono<List<Music>> getPlaylistSongs(@PathVariable String platform, @PathVariable String playlistId,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
        return resources.legacyTracks(platform, playlistId, offset, limit);
    }

    @GetMapping("/user/search/{platform}/{keyword}")
    public Mono<List<UserSearchResult>> searchUsers(@PathVariable String platform, @PathVariable String keyword) {
        return resources.users(platform, keyword);
    }

    @GetMapping("/music/lyric/{platform}/{musicId}")
    public Mono<String> getLyric(@PathVariable String platform, @PathVariable String musicId) {
        return resources.lyrics(platform, musicId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failure(Exception error) {
        return ResourceAccess.failure(error);
    }
}

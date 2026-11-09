package org.thornex.musicparty.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.api.SavedAlbumsService;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
public class SavedAlbumsController {
    private final AuthController auth;
    private final AppProperties properties;
    private final SavedAlbumsService savedAlbums;
    private final NeteaseMusicApiService musicApi;
    public SavedAlbumsController(AuthController auth, AppProperties properties, SavedAlbumsService savedAlbums, NeteaseMusicApiService musicApi) {
        this.auth = auth; this.properties = properties; this.savedAlbums = savedAlbums; this.musicApi = musicApi;
    }
    private boolean allowed(String password) {
        String admin = properties.getAdminPassword();
        if (admin != null && !admin.isBlank() && admin.equals(password)) return true;
        String room = auth.getRawPassword();
        return room != null && (room.isEmpty() || room.equals(password));
    }
    private ResponseEntity<Object> reply(int status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
    private ResponseEntity<Object> error(int status, String message) { return reply(status, Map.of("message", message)); }

    @GetMapping("/api/user/albums/netease")
    public Mono<ResponseEntity<Object>> saved(@RequestHeader(value = "X-Room-Password", required = false) String password,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        if (!allowed(password)) return Mono.just(error(403, "请先进入房间"));
        return savedAlbums.getSaved(offset, limit).map(page -> reply(200, page))
                .onErrorResume(ResponseStatusException.class, e -> Mono.just(error(e.getStatusCode().value(), e.getReason())))
                .onErrorReturn(error(502, "收藏专辑读取失败，请稍后重试"));
    }

    @GetMapping("/api/album/netease/detail/{albumId}")
    public Mono<ResponseEntity<Object>> detail(@PathVariable("albumId") String albumId,
            @RequestHeader(value = "X-Room-Password", required = false) String password) {
        if (!allowed(password)) return Mono.just(error(403, "请先进入房间"));
        if (albumId == null || !albumId.matches("[1-9][0-9]{0,19}")) return Mono.just(error(400, "无效的专辑 ID"));
        if (!properties.getNetease().isEnabled()) return Mono.just(error(409, "网易云音乐源已被禁用"));
        return Mono.defer(() -> musicApi.getAlbum(albumId)).map(album -> reply(200, album))
                .onErrorReturn(error(502, "专辑读取失败，请检查账号和音乐接口后重试"));
    }
}

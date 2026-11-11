package org.thornex.musicparty.controller;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.resource.ResourceAccess;
import org.thornex.musicparty.resource.ResourceService;
import reactor.core.publisher.Mono;

@RestController
public class SavedAlbumsController {
    private final AuthController auth;
    private final AppProperties properties;
    private final ResourceService resources;

    public SavedAlbumsController(AuthController auth, AppProperties properties, ResourceService resources) {
        this.auth = auth;
        this.properties = properties;
        this.resources = resources;
    }

    @GetMapping("/api/user/albums/netease")
    public Mono<ResponseEntity<Object>> saved(@RequestHeader(value = "X-Room-Password", required = false) String password,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return Mono.fromSupplier(() -> {
            ResourceAccess.authorize(password, auth, properties);
            return ResponseEntity.status(410).cacheControl(CacheControl.noStore()).body((Object) Map.of(
                    "code", "CAPABILITY_UNAVAILABLE", "message", "不再提供配置 Cookie 账号的收藏；仅支持绑定用户的公开收藏"));
        }).onErrorResume(error -> Mono.just(reply(error)));
    }

    @GetMapping("/api/album/netease/detail/{albumId}")
    public Mono<ResponseEntity<Object>> detail(@PathVariable("albumId") String albumId,
            @RequestHeader(value = "X-Room-Password", required = false) String password) {
        return Mono.defer(() -> {
            ResourceAccess.authorize(password, auth, properties);
            return resources.albumInfo("netease", albumId, false)
                    .map(album -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body((Object) album));
        }).onErrorResume(error -> Mono.just(reply(error)));
    }

    private ResponseEntity<Object> reply(Throwable error) {
        var response = ResourceAccess.failure(error instanceof Exception exception ? exception : new RuntimeException(error));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(response.getBody());
    }
}

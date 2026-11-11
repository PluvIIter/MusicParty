package org.thornex.musicparty.resource;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.source.CollectionCategory;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2")
public class SourceApiController {
    private final SourceRegistry registry;
    private final ResourceService resources;
    private final AuthController auth;
    private final AppProperties properties;

    public SourceApiController(SourceRegistry registry, ResourceService resources,
            AuthController auth, AppProperties properties) {
        this.registry = registry;
        this.resources = resources;
        this.auth = auth;
        this.properties = properties;
    }

    @ModelAttribute
    public void authorize(HttpServletRequest request) {
        String room = auth.getRawPassword();
        String supplied = request.getHeader("X-Room-Password");
        String admin = properties.getAdminPassword();
        if (admin != null && !admin.isBlank() && admin.equals(supplied)) return;
        if (room == null || (!room.isEmpty() && !room.equals(supplied))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先进入房间");
        }
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        return registry.catalog();
    }

    @GetMapping("/sources/{source}/tracks/search")
    public Mono<ResourcePage<Music>> search(@PathVariable String source, @RequestParam String q) {
        validateQuery(q);
        if (!registry.descriptor(source).capabilities().search()) throw unsupported();
        return registry.getRequired(source).searchMusic(q).timeout(Duration.ofSeconds(12))
                .map(items -> new ResourcePage<>(items.stream().map(resources::withExternalUrl).toList(), null, null));
    }

    @GetMapping("/sources/{source}/users/search")
    public Mono<ResourcePage<UserSearchResult>> users(@PathVariable String source, @RequestParam String q) {
        validateQuery(q);
        if (!registry.descriptor(source).capabilities().userSearch()) throw unsupported();
        return registry.getRequired(source).searchUsers(q).timeout(Duration.ofSeconds(12))
                .map(items -> new ResourcePage<>(items, null, null));
    }

    @GetMapping("/sources/{source}/collection-categories")
    public List<CollectionCategory> categories(@PathVariable String source) {
        return resources.categories(source);
    }

    @GetMapping("/sources/{source}/collections/{category}")
    public Mono<ResourcePage<?>> collections(@PathVariable String source,
            @PathVariable String category, @RequestParam String userId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return resources.collections(source, category, userId, cursor, limit);
    }

    @GetMapping("/sources/{source}/containers/{type}/{id}")
    public Mono<ContainerResource> container(@PathVariable String source, @PathVariable String type,
            @PathVariable String id) {
        return resources.container(source, type, id).timeout(Duration.ofSeconds(12));
    }

    @GetMapping("/sources/{source}/containers/{type}/{id}/tracks")
    public Mono<ResourcePage<Music>> tracks(@PathVariable String source, @PathVariable String type,
            @PathVariable String id, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return resources.tracks(source, type, id, cursor, limit);
    }

    @GetMapping("/sources/{source}/tracks/{id}/album")
    public Mono<ContainerResource> album(@PathVariable String source, @PathVariable String id) {
        return resources.albumForTrack(source, id).timeout(Duration.ofSeconds(12));
    }

    @GetMapping("/sources/{source}/tracks/{id}/lyrics")
    public Mono<String> lyrics(@PathVariable String source, @PathVariable String id) {
        ResourceService.validateId(id);
        if (!registry.descriptor(source).capabilities().lyrics()) throw unsupported();
        return registry.getRequired(source).getLyric(id).timeout(Duration.ofSeconds(12));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failure(Exception error) {
        String message = error instanceof ApiRequestException ? error.getMessage() : "上游暂时不可用，请稍后重试";
        String code = "UPSTREAM_UNAVAILABLE";
        int status = 502;
        if (error instanceof ResponseStatusException response) {
            status = response.getStatusCode().value();
            code = status == 403 ? "ROOM_ACCESS_REQUIRED" : "INVALID_REQUEST";
            message = response.getReason();
        } else if (message != null && message.matches("^[A-Z_]+:.*")) {
            int separator = message.indexOf(':');
            code = message.substring(0, separator);
            message = message.substring(separator + 1).trim();
            status = switch (code) {
                case "SOURCE_NOT_FOUND" -> 404;
                case "SOURCE_DISABLED" -> 409;
                case "INVALID_RESOURCE", "INVALID_CURSOR", "INVALID_LIMIT" -> 400;
                case "COLLECTION_NOT_PUBLIC" -> 403;
                case "CAPABILITY_UNAVAILABLE" -> 422;
                default -> 502;
            };
        }
        if (message == null || message.isBlank()) message = "请求失败，请稍后重试";
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message, "retryable", status >= 500,
                        "requestId", UUID.randomUUID().toString()));
    }

    private void validateQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 200) {
            throw new ApiRequestException("INVALID_RESOURCE: 搜索词须为1到200个字符");
        }
    }

    private ApiRequestException unsupported() {
        return new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持该功能");
    }
}

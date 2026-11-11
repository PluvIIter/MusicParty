package org.thornex.musicparty.resource;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.UserSearchResult;
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
        ResourceAccess.authorize(request.getHeader("X-Room-Password"), auth, properties);
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        return registry.catalog();
    }

    @GetMapping("/sources/{source}/tracks/search")
    public Mono<ResourcePage<Music>> search(@PathVariable String source, @RequestParam String q) {
        return resources.search(source, q).map(items -> new ResourcePage<>(items, null, null));
    }

    @GetMapping("/sources/{source}/users/search")
    public Mono<ResourcePage<UserSearchResult>> users(@PathVariable String source, @RequestParam String q) {
        return resources.users(source, q).map(items -> new ResourcePage<>(items, null, null));
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
        return resources.container(source, type, id);
    }

    @GetMapping("/sources/{source}/containers/{type}/{id}/tracks")
    public Mono<ResourcePage<Music>> tracks(@PathVariable String source, @PathVariable String type,
            @PathVariable String id, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return resources.tracks(source, type, id, cursor, limit);
    }

    @GetMapping("/sources/{source}/tracks/{id}/album")
    public Mono<ContainerResource> album(@PathVariable String source, @PathVariable String id) {
        return resources.albumForTrack(source, id);
    }

    @GetMapping("/sources/{source}/tracks/{id}/lyrics")
    public Mono<String> lyrics(@PathVariable String source, @PathVariable String id) {
        return resources.lyrics(source, id);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failure(Exception error) {
        return ResourceAccess.failure(error);
    }
}

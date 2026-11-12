package org.thornex.musicparty.resource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.source.CollectionCategory;
import org.thornex.musicparty.source.SourcePage;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

@Service
public class ResourceService {
    private final SourceRegistry registry;
    private final ResourceCursor cursors;

    public ResourceService(SourceRegistry registry, ResourceCursor cursors) {
        this.registry = registry;
        this.cursors = cursors;
    }

    public Mono<List<Music>> search(String source, String query) {
        validateQuery(query);
        var adapter = registry.getRequired(source);
        if (!registry.descriptor(source).capabilities().search()) throw unsupported("此音源不支持搜索");
        return Mono.defer(() -> adapter.searchMusic(query)).timeout(Duration.ofSeconds(12))
                .map(items -> items.stream().map(this::withExternalUrl).toList());
    }

    public Mono<List<org.thornex.musicparty.dto.UserSearchResult>> users(String source, String query) {
        validateQuery(query);
        var adapter = registry.getRequired(source);
        if (!registry.descriptor(source).capabilities().userSearch()) throw unsupported("此音源不支持用户搜索");
        return Mono.defer(() -> adapter.searchUsers(query)).timeout(Duration.ofSeconds(12));
    }

    public Mono<String> lyrics(String source, String id) {
        validateId(id);
        var adapter = registry.getRequired(source);
        if (!registry.descriptor(source).capabilities().lyrics()) throw unsupported("此音源不支持歌词");
        return Mono.defer(() -> adapter.getLyric(id)).timeout(Duration.ofSeconds(12));
    }

    public Mono<org.thornex.musicparty.dto.AlbumInfo> albumInfo(String source, String id, boolean byTrack) {
        validateId(id);
        var adapter = registry.getRequired(source);
        if (!registry.descriptor(source).capabilities().album()) throw unsupported("此音源不支持专辑");
        return Mono.defer(() -> byTrack ? adapter.getAlbumForSong(id) : adapter.getAlbum(id))
                .timeout(Duration.ofSeconds(12));
    }

    public List<CollectionCategory> categories(String source) {
        registry.getRequired(source);
        return registry.descriptor(source).collectionCategories();
    }

    public Mono<ResourcePage<?>> collections(String source, String category,
            String userId, String cursor, int limit) {
        validateId(userId);
        validateLimit(limit);
        var descriptor = categories(source).stream().filter(item -> item.id().equals(category))
                .findFirst().orElseThrow(() -> unsupported("收藏类别不存在"));
        String binding = binding(source, "collections", category, userId, limit);
        int offset = cursors.read(cursor, binding);
        if ("TRACK".equals(descriptor.entryKind())) {
            return registry.getRequired(source).publicCollectionTracks(userId, category, offset, limit)
                    .timeout(Duration.ofSeconds(12)).map(page -> new ResourcePage<>(
                            page.items().stream().map(this::withExternalUrl).toList(),
                            next(page, binding, offset), page.total()));
        }
        return registry.getRequired(source).publicCollections(userId, category, offset, limit)
                .timeout(Duration.ofSeconds(12))
                .map(page -> new ResourcePage<>(page.items().stream()
                        .map(item -> container(item, descriptor.collectionType())).toList(),
                        next(page, binding, offset), page.total()));
    }

    public Mono<ContainerResource> container(String source, String type, String id) {
        validateId(id);
        var adapter = registry.getRequired(source);
        if ("album".equals(type)) {
            return albumInfo(source, id, false).map(album -> new ContainerResource(album.id(), album.name(), source,
                    "album", album.coverUrl(), album.trackCount()));
        }
        requireContainerType(source, type);
        return Mono.defer(() -> adapter.containerDetail(type, id)).timeout(Duration.ofSeconds(12))
                .map(item -> container(item, type));
    }

    public Mono<ContainerResource> albumForTrack(String source, String id) {
        return albumInfo(source, id, true).map(album -> new ContainerResource(
                album.id(), album.name(), source, "album", album.coverUrl(), album.trackCount()));
    }

    public Mono<ResourcePage<Music>> tracks(String source, String type, String id, String cursor, int limit) {
        validateId(id);
        validateLimit(limit);
        var adapter = registry.getRequired(source);
        String binding = binding(source, "tracks", type, id, limit);
        int offset = cursors.read(cursor, binding);
        if ("album".equals(type)) {
            if (!registry.descriptor(source).capabilities().album()) return Mono.error(unsupported("此音源不支持专辑"));
            return albumInfo(source, id, false).map(album -> {
                int start = Math.min(offset, album.songs().size());
                int end = Math.min(start + limit, album.songs().size());
                return new ResourcePage<>(album.songs().subList(start, end).stream().map(this::withExternalUrl).toList(),
                        end < album.songs().size() ? cursors.issue(binding, end) : null, (long) album.songs().size());
            });
        }
        requireContainerType(source, type);
        return Mono.defer(() -> adapter.containerPage(type, id, offset, limit)).timeout(Duration.ofSeconds(12))
                .map(page -> new ResourcePage<>(page.items().stream().map(this::withExternalUrl).toList(),
                        next(page, binding, offset), page.total()));
    }

    public Mono<List<Music>> importTracks(String source, String type, String id, int maximum) {
        if (maximum < 1 || maximum > 1000) return Mono.error(new ApiRequestException("INVALID_LIMIT: 无效导入上限"));
        return Mono.defer(() -> collect(source, type, id, null, maximum, 0, new ArrayList<>()))
                .timeout(Duration.ofSeconds(30));
    }

    private Mono<List<Music>> collect(String source, String type, String id, String cursor,
            int maximum, int pageCount, List<Music> output) {
        if (pageCount >= 100) return Mono.error(new ApiRequestException("IMPORT_LIMIT: 分页数量超出安全限制"));
        return tracks(source, type, id, cursor, 20).flatMap(page -> {
            for (Music music : page.items()) {
                if (output.size() >= maximum) break;
                output.add(music);
            }
            if (output.size() >= maximum || page.nextCursor() == null) return Mono.just(List.copyOf(output));
            if (page.nextCursor().equals(cursor)) return Mono.error(new ApiRequestException("INVALID_CURSOR: 上游分页未前进"));
            return collect(source, type, id, page.nextCursor(), maximum, pageCount + 1, output);
        });
    }

    private String next(SourcePage<?> page, String binding, int offset) {
        Integer next = page.nextOffset();
        if (next == null) return null;
        if (next <= offset) throw new ApiRequestException("INVALID_CURSOR: 上游分页未前进");
        return cursors.issue(binding, next);
    }

    private String binding(String source, String operation, String context, String target, int limit) {
        return List.of(source, operation, context, target, Integer.toString(limit),
                registry.catalog().get("catalogRevision").toString()).toString();
    }

    private void requireContainerType(String source, String type) {
        if (registry.descriptor(source).collectionCategories().stream()
                .noneMatch(item -> "CONTAINER".equals(item.entryKind()) && item.collectionType().equals(type))) throw unsupported("容器类型不支持");
    }

    private ContainerResource container(Playlist playlist, String type) {
        return new ContainerResource(playlist.id(), playlist.name(), playlist.platform(), type,
                playlist.coverImgUrl(), playlist.trackCount());
    }

    public Music withExternalUrl(Music music) {
        String external = registry.getRequired(music.platform()).externalUrl(music.id());
        return new Music(music.id(), music.name(), music.artists(), music.duration(), music.platform(), music.coverUrl(), external);
    }

    public static void validateId(String id) {
        if (id == null || id.isBlank() || id.length() > 512 || id.chars().anyMatch(Character::isISOControl)) {
            throw new ApiRequestException("INVALID_RESOURCE: 无效资源或用户标识");
        }
    }

    public static void validateQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 200 || query.chars().anyMatch(Character::isISOControl)) {
            throw new ApiRequestException("INVALID_RESOURCE: 搜索词须为1到200个字符");
        }
    }

    public static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new ApiRequestException("INVALID_LIMIT: 分页大小须为1到100");
    }

    private ApiRequestException unsupported(String message) {
        return new ApiRequestException("CAPABILITY_UNAVAILABLE: " + message);
    }
}

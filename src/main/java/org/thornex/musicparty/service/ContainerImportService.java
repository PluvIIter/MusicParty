package org.thornex.musicparty.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.resource.ResourceService;
import org.thornex.musicparty.source.ResourceKey;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ContainerImportService {
    private final ResourceService resources;
    private final SourceRegistry sources;
    private final MediaDeliveryService media;
    private final UserService users;
    private final MusicQueueManager queue;
    private final MusicPlayerService player;
    private final org.thornex.musicparty.config.AppProperties properties;
    private final Map<RequestKey, Pending> requests = new LinkedHashMap<>();

    public record Request(String platform, String collectionType, String containerId, String requestId) {}
    public record Result(String requestId, String containerId, String name, int total, int added,
                         int duplicates, int limited, String message, boolean error) {}
    private record RequestKey(String member, String requestId) {}
    private record Pending(Request request, long expiresAt, Mono<Result> result) {}

    public Mono<Result> enqueue(Request request, String sessionId) {
        return Mono.defer(() -> {
            if (request == null || request.requestId() == null || request.requestId().isBlank()
                    || request.requestId().length() > 80) return Mono.just(failure(request, "无效的导入请求"));
            var member = users.getUser(sessionId);
            if (member.isEmpty() || member.get().isGuest()) return Mono.just(failure(request, "请先设置昵称并加入房间"));
            RequestKey key = new RequestKey(member.get().getToken(), request.requestId());
            synchronized (requests) {
                requests.values().removeIf(value -> value.expiresAt() <= System.currentTimeMillis());
                Pending pending = requests.get(key);
                if (pending != null) return pending.request().equals(request) ? pending.result()
                        : Mono.just(failure(request, "请求标识已用于其他导入"));
                if (requests.size() >= 1000) return Mono.just(failure(request, "导入请求繁忙，请稍后重试"));
                Mono<Result> result = importContainer(request, sessionId, key.member())
                        .onErrorResume(error -> Mono.just(failure(request, error instanceof org.thornex.musicparty.exception.ApiRequestException
                                ? error.getMessage() : "导入失败，请稍后重试")))
                        .cache(Duration.ofMinutes(5));
                requests.put(key, new Pending(request, System.currentTimeMillis() + Duration.ofMinutes(5).toMillis(), result));
                return result;
            }
        });
    }

    private Mono<Result> importContainer(Request request, String sessionId, String token) {
        return Mono.defer(() -> {
            sources.getRequired(request.platform());
            int maximum = Math.max(1, Math.min(1000, properties.getPlayer().getMaxPlaylistImportSize()));
            return resources.container(request.platform(), request.collectionType(), request.containerId())
                    .zipWith(resources.importTracks(request.platform(), request.collectionType(), request.containerId(), maximum))
                    .map(response -> {
                        sources.getRequired(request.platform());
                        var member = users.getUser(sessionId);
                        if (member.isEmpty() || member.get().isGuest() || !token.equals(member.get().getToken())) {
                            return failure(request, "已离开房间，未导入歌曲");
                        }
                        User user = member.get();
                        UserSummary owner = new UserSummary(token, sessionId, user.getName(), false);
                        response.getT2().forEach(track -> {
                            if (!request.platform().equals(track.platform())) throw new IllegalArgumentException("Mismatched source");
                            ResourceKey.of(track);
                        });
                        List<Music> imported = new ArrayList<>();
                        int duplicates = 0;
                        int limited = Math.max(0, response.getT1().trackCount() - response.getT2().size());
                        synchronized (queue) {
                            Set<String> existing = new HashSet<>();
                            queue.getQueueSnapshot().forEach(item -> existing.add(ResourceKey.of(item.music()).encoded()));
                            for (Music track : response.getT2()) {
                                if (!request.platform().equals(track.platform())) throw new IllegalArgumentException("Mismatched source");
                                String identity = ResourceKey.of(track).encoded();
                                if (existing.contains(identity)) { duplicates++; continue; }
                                if (queue.add(track, owner, media.status(track)) == null) { limited++; continue; }
                                imported.add(track);
                                existing.add(identity);
                            }
                        }
                        imported.forEach(media::prefetch);
                        player.broadcastQueueUpdate();
                        String message = "已添加 " + imported.size() + " 首，重复跳过 " + duplicates + " 首，上限限制 " + limited + " 首";
                        return new Result(request.requestId(), request.containerId(), response.getT1().name(), response.getT1().trackCount(),
                                imported.size(), duplicates, limited, message, false);
                    });
        });
    }

    private Result failure(Request request, String message) {
        return new Result(request == null ? null : request.requestId(), request == null ? null : request.containerId(),
                null, 0, 0, 0, 0, message, true);
    }
}

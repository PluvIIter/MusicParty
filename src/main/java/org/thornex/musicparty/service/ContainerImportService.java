package org.thornex.musicparty.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
            long epoch;
            long credentialRevision;
            synchronized (sources) {
                credentialRevision = sources.getRequired(request.platform()).credentialRevision();
                epoch = sources.authorizationEpoch(request.platform());
            }
            int maximum = Math.max(1, Math.min(1000, properties.getPlayer().getMaxPlaylistImportSize()));
            return resources.container(request.platform(), request.collectionType(), request.containerId())
                    .zipWith(resources.importTracks(request.platform(), request.collectionType(), request.containerId(), maximum))
                    .timeout(Duration.ofSeconds(30))
                    .map(response -> {
                        response.getT2().forEach(track -> {
                            if (!request.platform().equals(track.platform())) throw new IllegalArgumentException("Mismatched source");
                            ResourceKey.of(track);
                        });
                        List<Music> imported = new ArrayList<>();
                        int duplicates = 0;
                        List<Music> retry = new ArrayList<>();
                        int limited = Math.max(0, response.getT1().trackCount() - response.getT2().size());
                        Map<String, org.thornex.musicparty.enums.QueueItemStatus> statuses = new HashMap<>();
                        response.getT2().forEach(track -> statuses.put(ResourceKey.of(track).encoded(), media.status(track)));
                        synchronized (users) {
                            synchronized (sources) {
                                synchronized (queue) {
                                    if (credentialRevision != sources.getRequired(request.platform()).credentialRevision()
                                            || epoch != sources.authorizationEpoch(request.platform())) {
                                        return failure(request, "音源凭据或配置已改变，未导入歌曲");
                                    }
                                    var member = users.getUser(sessionId);
                                    if (member.isEmpty() || member.get().isGuest() || !token.equals(member.get().getToken())
                                            || !sessionId.equals(member.get().getSessionId())) {
                                        return failure(request, "已离开房间，未导入歌曲");
                                    }
                                    UserSummary owner = new UserSummary(token, sessionId, member.get().getName(), false);
                                    Set<String> existing = new HashSet<>();
                                    queue.getQueueSnapshot().forEach(item -> existing.add(ResourceKey.of(item.music()).encoded()));
                                    for (Music track : response.getT2()) {
                                        String identity = ResourceKey.of(track).encoded();
                                        if (existing.contains(identity)) {
                                            duplicates++;
                                            // 重新导入同一容器时，顺带重试之前下载失败的曲目（限量，避免再次打满上游）
                                            if (retry.size() < 5 && statuses.get(identity)
                                                    == org.thornex.musicparty.enums.QueueItemStatus.FAILED) {
                                                queue.getQueueSnapshot().stream()
                                                        .filter(item -> ResourceKey.of(item.music()).encoded().equals(identity))
                                                        .findFirst().ifPresent(item -> retry.add(item.music()));
                                            }
                                            continue;
                                        }
                                        if (queue.add(track, owner, statuses.get(identity)) == null) { limited++; continue; }
                                        imported.add(track);
                                        existing.add(identity);
                                    }
                                }
                            }
                        }
                        retry.forEach(media::retry);
                        imported.forEach(track -> {
                            try { media.prefetch(track); }
                            catch (RuntimeException error) { log.debug("Post-import prefetch deferred for {}", ResourceKey.of(track)); }
                        });
                        try { player.broadcastQueueUpdate(); }
                        catch (RuntimeException error) { log.warn("Queue imported; update broadcast failed", error); }
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

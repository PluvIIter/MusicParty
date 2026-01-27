package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.event.*;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MusicPlayerService {

    private final Map<String, IMusicApiService> apiServiceMap;
    private final UserService userService;
    private final LocalCacheService localCacheService;
    private final ChatService chatService;

    // --- Refactored Dependencies ---
    private final MusicQueueManager queueManager;
    private final ApplicationEventPublisher eventPublisher;

    // --- Player State ---
    private final AtomicReference<NowPlayingInfo> nowPlaying = new AtomicReference<>(null);
    private final AtomicBoolean isShuffle = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicLong pauseStateChangeTime = new AtomicLong(0);
    private final AtomicLong totalPausedTimeMillis = new AtomicLong(0);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);

    private final Map<String, Object> likeLock = new HashMap<>();
    private Set<String> currentLikedUserIds;
    private List<Long> currentLikeMarkers;

    private final AtomicLong lastControlTimestamp = new AtomicLong(0);
    private static final long GLOBAL_COOLDOWN_MS = 1000;
    private static final int PLAYLIST_ADD_LIMIT = 100;
    private static final long IDLE_RESET_TIMEOUT_MS = Duration.ofHours(2).toMillis();

    private final AtomicLong playHeadVersion = new AtomicLong(0);

    public MusicPlayerService(List<IMusicApiService> apiServices, UserService userService,
                              LocalCacheService localCacheService,
                              ChatService chatService, MusicQueueManager queueManager,
                              ApplicationEventPublisher eventPublisher) {
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.userService = userService;
        this.localCacheService = localCacheService;
        this.chatService = chatService;
        this.queueManager = queueManager;
        this.eventPublisher = eventPublisher;
        this.currentLikedUserIds = ConcurrentHashMap.newKeySet();
        this.currentLikeMarkers = new CopyOnWriteArrayList<>();
    }

    @PostConstruct
    public void init() {
        log.info("MusicPlayerService initialized with {} API services: {}", apiServiceMap.size(), apiServiceMap.keySet());
    }

    @Scheduled(fixedRate = 1000)
    public void playerLoop() {
        if (isPaused.get()) {
            return;
        }

        NowPlayingInfo current = nowPlaying.get();

        if (current != null) {
            long elapsed = Instant.now().toEpochMilli() - current.startTimeMillis() - totalPausedTimeMillis.get();
            if (elapsed >= current.music().duration() && current.music().duration() > 0) {
                log.info("Song finished: {}", current.music().name());

                Music finishedMusic = new Music(
                        current.music().id(),
                        current.music().name(),
                        current.music().artists(),
                        current.music().duration(),
                        current.music().platform(),
                        current.music().coverUrl()
                );
                queueManager.addToHistory(finishedMusic);

                nowPlaying.set(null);
                playNextInQueue();
            }
        } else {
            if (userService.getOnlineUserSummaries().isEmpty()) {
                return;
            }
            if (!queueManager.getQueueSnapshot().isEmpty()) {
                playNextInQueue();
            }
        }
    }

    private synchronized void playNextInQueue() {
        if (nowPlaying.get() != null || isLoading.get()) {
            return;
        }

        Map<String, QueueItemStatus> statusMap = buildStatusMap();
        MusicQueueItem nextItem = queueManager.pollNext(isShuffle.get(), statusMap);

        if (nextItem == null) {
            // This is the key moment: no song could be found.
            // The player is now officially idle. Broadcast this final state ONCE.
            if (isLoading.get()) {
                isLoading.set(false); // If it was loading, cancel it.
            }
            broadcastFullPlayerState();
            return;
        }

        // Handle failed items
        if (nextItem.status() == QueueItemStatus.FAILED ||
                (statusMap.get(nextItem.music().id()) == QueueItemStatus.FAILED)) {
            log.warn("Skipping failed song: {}", nextItem.music().name());
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.ERROR_LOAD, "SYSTEM", "加载失败: " + nextItem.music().name()));
            playNextInQueue(); // Recursively try next
            return;
        }

        // 增加版本号，这表示"开始一次新的播放尝试"
        long currentVersion = playHeadVersion.incrementAndGet();
        isLoading.set(true);
        broadcastFullPlayerState();
        resetPauseState();

        log.info("Playing next: {}", nextItem.music().name());

        try {
            IMusicApiService service = getApiService(nextItem.music().platform());
            service.getPlayableMusic(nextItem.music().id())
                    .timeout(Duration.ofSeconds(10))
                    .subscribe(
                            playableMusic -> {
                                // 检查版本号是否匹配
                                // 如果在请求期间执行了 skip/stop，版本号会变，这里就应该丢弃结果
                                if (playHeadVersion.get() == currentVersion) {
                                    applyNewSong(playableMusic, nextItem);
                                } else {
                                    log.info("Discarded stale play result for {}", nextItem.music().name());
                                }
                            },
                            error -> {
                        log.error("Play failed for {}: {}", nextItem.music().name(), error.getMessage());
                        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.ERROR_LOAD, "SYSTEM", nextItem.music().name()));
                        isLoading.set(false);
                        broadcastFullPlayerState();
                        playNextInQueue();
                    });
        } catch (Exception e) {
            log.error("Unexpected error in playNextInQueue", e);
            isLoading.set(false);
            broadcastFullPlayerState();
        }
    }

    private void applyNewSong(PlayableMusic music, MusicQueueItem queueItem) {
        currentLikedUserIds.clear();
        currentLikeMarkers.clear();

        NowPlayingInfo newNowPlaying = new NowPlayingInfo(
                music,
                Instant.now().toEpochMilli(),
                queueItem.enqueuedBy().token(),
                queueItem.enqueuedBy().name(),
                currentLikedUserIds,
                currentLikeMarkers
        );

        if (nowPlaying.compareAndSet(null, newNowPlaying)) {
            log.info("Now playing: {}", music.name());
            isLoading.set(false);
            broadcastFullPlayerState();
            broadcastQueueUpdate();
        } else {
            isLoading.set(false);
        }
    }

    public PlayerState getCurrentPlayerState() {
        NowPlayingInfo current = nowPlaying.get();
        NowPlayingInfo infoToSend = null;

        if (current != null) {
            long effectiveStartTime = current.startTimeMillis() + totalPausedTimeMillis.get();
            infoToSend = new NowPlayingInfo(
                    current.music(),
                    effectiveStartTime,
                    current.enqueuedById(),
                    current.enqueuedByName(),
                    currentLikedUserIds,
                    currentLikeMarkers
            );
        }

        boolean effectiveIsLoading = (current == null) && isLoading.get();

        return new PlayerState(
                infoToSend,
                getQueueWithUpdatedStatus(),
                isShuffle.get(),
                userService.getOnlineUserSummaries(),
                isPaused.get(),
                isPaused.get() ? pauseStateChangeTime.get() : 0,
                System.currentTimeMillis(),
                effectiveIsLoading
        );
    }

    public void enqueue(EnqueueRequest request, String sessionId) {
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();

        IMusicApiService service = getApiService(request.platform());
        service.getPlayableMusic(request.musicId())
                .subscribe(playableMusic -> {
                            Music music = new Music(playableMusic.id(), playableMusic.name(), playableMusic.artists(), playableMusic.duration(), playableMusic.platform(), playableMusic.coverUrl());

                            QueueItemStatus initialStatus = "bilibili".equals(request.platform()) ? QueueItemStatus.PENDING : QueueItemStatus.READY;
                            if ("bilibili".equals(request.platform())) {
                                service.prefetchMusic(music.id());
                            }

                            MusicQueueItem newItem = queueManager.add(music, new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName()), initialStatus);

                            if (newItem != null) {
                                log.info("{} enqueued: {}", enqueuer.getName(), music.name());
                                broadcastQueueUpdate();
                                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.ADD, enqueuer.getToken(), music.name()));
                            }
                        },
                        error -> {
                            log.error("Enqueue failed for musicId: {}", request.musicId(), error);
                            String msg = error.getMessage().contains("Could not get Bilibili video info") ? "无效资源或API受限" : error.getMessage();
                            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.ERROR_LOAD, enqueuer.getToken(), "添加失败: " + msg));
                        });
    }

    // 点赞逻辑
    public void likeSong(String sessionId) {
        NowPlayingInfo current = nowPlaying.get();
        if (current == null) return; // 没歌放，不能点赞

        String token = getUserToken(sessionId);

        // 1. 检查去重 (单人单曲一次)
        if (currentLikedUserIds.contains(token)) return;

        // 2. 更新数据
        currentLikedUserIds.add(token);

        // 计算相对时间 (进度)
        long progress = 0;
        if (isPaused.get()) {
            progress = pauseStateChangeTime.get() - (current.startTimeMillis() + totalPausedTimeMillis.get());
        } else {
            progress = System.currentTimeMillis() - (current.startTimeMillis() + totalPausedTimeMillis.get());
        }
        currentLikeMarkers.add(progress);

        log.info("Like received from {}", getUserName(sessionId));

        // 3. 广播
        // 广播事件用于触发特效
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.LIKE, token, null));
        // 广播状态更新进度条打点和用户列表
        broadcastFullPlayerState();
    }

    public void enqueuePlaylist(EnqueuePlaylistRequest request, String sessionId) {
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();

        IMusicApiService service = getApiService(request.platform());
        service.getPlaylistMusics(request.playlistId(), 0, PLAYLIST_ADD_LIMIT)
                .subscribe(musics -> {
                    int count = 0;
                    QueueItemStatus initialStatus = "bilibili".equals(request.platform()) ? QueueItemStatus.PENDING : QueueItemStatus.READY;

                    for (Music music : musics) {
                        if ("bilibili".equals(request.platform())) {
                            service.prefetchMusic(music.id());
                        }
                        MusicQueueItem newItem = queueManager.add(music, new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName()), initialStatus);
                        if (newItem != null) {
                            count++;
                        }
                    }

                    log.info("{} enqueued {} songs from playlist", enqueuer.getName(), count);
                    broadcastQueueUpdate();
                    eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.IMPORT_PLAYLIST, enqueuer.getToken(), String.valueOf(count)));
                });
    }

    public synchronized void topSong(String queueId, String sessionId) {
        Optional<MusicQueueItem> itemToTop = queueManager.getQueueSnapshot().stream()
                .filter(item -> item.queueId().equals(queueId) || item.queueId().replace("TOP-", "").equals(queueId))
                .findFirst();

        if (itemToTop.isPresent() && queueManager.top(queueId)) {
            log.info("Song topped: {}", itemToTop.get().music().name());
            broadcastQueueUpdate();
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.TOP, getUserToken(sessionId), itemToTop.get().music().name()));
            if (nowPlaying.get() == null) {
                playNextInQueue();
            }
        }
    }

    public void removeSongFromQueue(String queueId, String sessionId) {
        Optional<MusicQueueItem> removedItem = queueManager.remove(queueId);
        if (removedItem.isPresent()) {
            log.info("Removed song from queue by {}", getUserName(sessionId));
            broadcastQueueUpdate();
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.REMOVE, getUserToken(sessionId), removedItem.get().music().name()));
        }
    }

    public void skipToNext(String sessionId) {
        if (isRateLimited(sessionId)) return;

        // 切歌时版本号自增，废弃之前的任何 pending 请求
        playHeadVersion.incrementAndGet();
        isLoading.set(false);

        nowPlaying.getAndSet(null);

        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.SKIP, getUserToken(sessionId), null));
        playNextInQueue();
    }

    public void togglePause(String sessionId) {
        if (nowPlaying.get() == null) {
            if (!queueManager.getQueueSnapshot().isEmpty()) {
                playNextInQueue();
            }
            return;
        }
        if (isRateLimited(sessionId)) return;

        long now = Instant.now().toEpochMilli();
        if (isPaused.compareAndSet(false, true)) {
            pauseStateChangeTime.set(now);
            log.info("Player paused by {}", getUserName(sessionId));
            broadcastFullPlayerState();
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.PAUSE, getUserToken(sessionId), null));
        } else if (isPaused.compareAndSet(true, false)) {
            long pausedDuration = now - pauseStateChangeTime.get();
            totalPausedTimeMillis.addAndGet(pausedDuration);
            pauseStateChangeTime.set(now);
            log.info("Player resumed by {}", getUserName(sessionId));
            broadcastFullPlayerState();
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.RESUME, getUserToken(sessionId), null));
        }
    }

    public void toggleShuffle(String sessionId) {
        if (isRateLimited(sessionId)) return;

        // 使用标准的 CAS 循环来原子性地翻转布尔值
        boolean current;
        boolean newState;
        do {
            current = isShuffle.get();
            newState = !current;
        } while (!isShuffle.compareAndSet(current, newState));

        log.info("Shuffle mode set to {} by {}", newState, getUserName(sessionId));
        broadcastFullPlayerState();
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                newState ? PlayerAction.SHUFFLE_ON : PlayerAction.SHUFFLE_OFF, getUserToken(sessionId), null));
    }

    public void resetSystem() {
        log.warn("!!!SYSTEM RESET INITIATED!!!");
        nowPlaying.set(null);
        queueManager.clearAll();
        resetPauseState();
        isShuffle.set(false);
        chatService.clearHistory();
        isLoading.set(false);

        broadcastFullPlayerState();
        broadcastQueueUpdate();
        log.warn("System reset complete.");
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.RESET, "SYSTEM", null));
    }

    public void clearQueue() {
        queueManager.clearPendingQueue();
        log.info("Queue cleared by Admin.");
        // 广播队列更新
        broadcastQueueUpdate();
        // 发送全员通知
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.REMOVE, "SYSTEM", "播放列表已由管理员清空"));
    }

    @EventListener
    public void handleDownloadEvent(DownloadStatusEvent event) {
        boolean existsInQueue = queueManager.getQueueSnapshot().stream()
                .anyMatch(item -> item.music().id().equals(event.getMusicId()));

        if (existsInQueue) {
            log.debug("Download status changed for {}, updating queue UI.", event.getMusicId());
            broadcastQueueUpdate();
            if (nowPlaying.get() == null) {
                playNextInQueue();
            }
        }
    }

    /**
     * 监听用户数量变化事件
     */
    @EventListener
    public void onUserCountChanged(UserCountChangeEvent event) {
        if (event.getOnlineUserCount() == 0) {
            enterIdleMode();
        }
    }

    /**
     * 进入空闲模式，停止播放
     */
    private void enterIdleMode() {
        log.info("Last user disconnected. Entering idle mode.");
        //nowPlaying.set(null); // 立即停止当前歌曲
        isLoading.set(false); // 取消加载状态
        //isPaused.set(true);   // 设置为暂停状态，防止 playerLoop 意外触发
        //resetPauseState();    // 重置暂停计时器
        if (nowPlaying.get() != null && isPaused.compareAndSet(false, true)) {
            pauseStateChangeTime.set(Instant.now().toEpochMilli());
            log.info("Player paused as all users have disconnected.");
            broadcastFullPlayerState();
        }
    }

    /**
     * 定时清理长时间暂停的播放器状态
     */
    @Scheduled(fixedRate = 600000) // 每10分钟检查一次
    public void cleanupIdlePlayer() {
        if (isPaused.get() && nowPlaying.get() != null) {
            long pausedDuration = Instant.now().toEpochMilli() - pauseStateChangeTime.get();
            if (pausedDuration > IDLE_RESET_TIMEOUT_MS) {
                log.info("Idle player timeout reached ({} hours). Resetting now playing.", Duration.ofMillis(IDLE_RESET_TIMEOUT_MS).toHours());
                nowPlaying.set(null);
                resetPauseState();
                broadcastFullPlayerState();
            }
        }
    }

    // --- Broadcasting and Helper Methods ---

    private void broadcastQueueUpdate() {
        eventPublisher.publishEvent(new QueueUpdateEvent(this, getQueueWithUpdatedStatus()));
    }

    public void broadcastFullPlayerState() {
        eventPublisher.publishEvent(new PlayerStateEvent(this, getCurrentPlayerState()));
    }

    public void broadcastOnlineUsers() {
        // This is triggered by UserService, so we can keep it simple or create another event type
        broadcastFullPlayerState();
    }

    public void broadcastPasswordChanged() {
        // Can create a specific event or use SystemMessageEvent
        // For now, let's keep it simple
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, null, "SYSTEM", "PASSWORD_CHANGED"));
    }

    private List<MusicQueueItem> getQueueWithUpdatedStatus() {
        return queueManager.getQueueSnapshot().stream().map(item -> {
            if ("netease".equals(item.music().platform())) {
                return item.status() == QueueItemStatus.READY ? item : item.withStatus(QueueItemStatus.READY);
            }
            if ("bilibili".equals(item.music().platform())) {
                CacheStatus cacheStatus = localCacheService.getStatus(item.music().id());
                QueueItemStatus newStatus = mapCacheStatusToEnum(cacheStatus);
                if (item.status() != newStatus) {
                    return item.withStatus(newStatus);
                }
            }
            return item;
        }).collect(Collectors.toList());
    }

    private Map<String, QueueItemStatus> buildStatusMap() {
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        for (MusicQueueItem item : queueManager.getQueueSnapshot()) {
            if ("bilibili".equals(item.music().platform())) {
                statusMap.put(item.music().id(), mapCacheStatusToEnum(localCacheService.getStatus(item.music().id())));
            } else {
                statusMap.put(item.music().id(), QueueItemStatus.READY);
            }
        }
        return statusMap;
    }

    private QueueItemStatus mapCacheStatusToEnum(CacheStatus status) {
        if (status == null) return QueueItemStatus.PENDING;
        return switch (status) {
            case COMPLETED -> QueueItemStatus.READY;
            case DOWNLOADING -> QueueItemStatus.DOWNLOADING;
            case FAILED -> QueueItemStatus.FAILED;
            default -> QueueItemStatus.PENDING;
        };
    }

    private void resetPauseState() {
        isPaused.set(false);
        pauseStateChangeTime.set(0);
        totalPausedTimeMillis.set(0);
    }

    private boolean isRateLimited(String userId) {
        long now = System.currentTimeMillis();
        if (now - lastControlTimestamp.get() < GLOBAL_COOLDOWN_MS) {
            log.warn("Action rate limited for user: {}", userId);
            // eventPublisher.publishEvent(...); // Optional: notify user about rate limit
            return true;
        }
        lastControlTimestamp.set(now);
        return false;
    }

    private IMusicApiService getApiService(String platform) {
        IMusicApiService service = apiServiceMap.get(platform);
        if (service == null) throw new ApiRequestException("Unsupported platform: " + platform);
        return service;
    }

    private String getUserToken(String sessionId) {
        return userService.getUser(sessionId).map(User::getToken).orElse("UNKNOWN_TOKEN");
    }

    private String getUserName(String sessionId) {
        return userService.getUser(sessionId).map(User::getName).orElse("Unknown User");
    }
}
package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.DownloadStatusEvent;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MusicPlayerService {

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, IMusicApiService> apiServiceMap;
    private final UserService userService;
    private final MusicProxyService musicProxyService;
    private final LocalCacheService localCacheService;

    // Player State
    private final AtomicReference<NowPlayingInfo> nowPlaying = new AtomicReference<>(null);
    private final Queue<MusicQueueItem> musicQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isShuffle = new AtomicBoolean(false);

    // NEW: State for pause/resume logic
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicLong pauseStateChangeTime = new AtomicLong(0); // Time when the last pause/resume occurred
    private final AtomicLong totalPausedTimeMillis = new AtomicLong(0); // Cumulative paused time for the current song

    private final AtomicBoolean isLoading = new AtomicBoolean(false);

    private final List<Music> playHistory = Collections.synchronizedList(new LinkedList<>());
    private static final int HISTORY_LIMIT = 50;

    private static final int PLAYLIST_ADD_LIMIT = 100;

    private final AtomicLong lastControlTimestamp = new AtomicLong(0);
    private static final long GLOBAL_COOLDOWN_MS = 1000; // 全局冷却时间 1秒

    private final ChatService chatService;

    private final AtomicReference<String> lastPlayedUserToken = new AtomicReference<>("");

    public MusicPlayerService(SimpMessagingTemplate messagingTemplate, List<IMusicApiService> apiServices, UserService userService, MusicProxyService musicProxyService, LocalCacheService localCacheService, ChatService chatService) {
        this.messagingTemplate = messagingTemplate;
        // Create a map of services, keyed by platform name for easy lookup
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.userService = userService;
        this.musicProxyService = musicProxyService;
        this.localCacheService = localCacheService;
        this.chatService = chatService;
    }

    @PostConstruct
    public void init() {
        log.info("MusicPlayerService initialized with {} API services: {}", apiServiceMap.size(), apiServiceMap.keySet());
    }

    // This method runs every second to check player state
    @Scheduled(fixedRate = 1000)
    public void playerLoop() {
        if (isPaused.get()) {
            return;
        }

        NowPlayingInfo current = nowPlaying.get();

        if (current != null) {

            long elapsed = Instant.now().toEpochMilli() - current.startTimeMillis() - totalPausedTimeMillis.get();
            // Check if the song has finished
            if (elapsed >= current.music().duration() && current.music().duration() > 0) {
                log.info("Song finished: {}", current.music().name());

                // 新增：加入历史记录
                // 注意：这里我们存的是 PlayableMusic 对应的基础 Music 信息
                // 我们需要从 PlayableMusic 转换回 Music DTO，或者直接存 PlayableMusic 但之后要重新获取 URL
                // 简单起见，我们存 Music DTO，回填时当作新点歌处理（重新获取 URL，保证链接有效性）
                addToHistory(new Music(
                        current.music().id(),
                        current.music().name(),
                        current.music().artists(),
                        current.music().duration(),
                        current.music().platform(),
                        current.music().coverUrl()
                ));

                // NEW LOGIC HERE:
                // If the song that just finished was using the proxy, cancel it.
                // This is crucial to clean up resources and prepare for the next song.
                if (current.music().needsProxy()) {
                    log.debug("Cancelling proxy for finished Bilibili song: {}", current.music().name());
                    musicProxyService.cancelCurrentProxy();
                }

                nowPlaying.set(null); // Clear current song
                playNextInQueue(); // Immediately attempt to play the next song
            }
        } else {
            // If nothing is playing, try to play the next song from the queue
            playNextInQueue();

            if (nowPlaying.get() == null && musicQueue.isEmpty() && !userService.getOnlineUserSummaries().isEmpty() && !playHistory.isEmpty()) {
                triggerAutoPlayFromHistory();
            }
        }
    }

    // 辅助方法：通过 SessionId 获取 Token
    private String getUserToken(String sessionId) {
        return userService.getUser(sessionId)
                .map(User::getToken)
                .orElse("UNKNOWN_TOKEN");
    }

    private void triggerAutoPlayFromHistory() {
        // 简单的防抖/限流：避免瞬间疯狂重试
        // 由于 playerLoop 是 1秒一次，所以这里其实还好。
        // 但为了保险，我们可以加一个随机延迟或者简单的标志位，这里演示直接逻辑。

        Music randomSong;
        synchronized (playHistory) {
            if (playHistory.isEmpty()) return;
            int randomIndex = new Random().nextInt(playHistory.size());
            randomSong = playHistory.get(randomIndex);
        }

        log.info("Auto-playing from history: {}", randomSong.name());

        // 构造一个“系统”用户
        UserSummary systemUser = new UserSummary("ADMIN", "ADMIN", "AutoDJ");

        String initStatus = "bilibili".equals(randomSong.platform()) ? "PENDING" : "READY";

        // 构造队列项
        MusicQueueItem item = new MusicQueueItem(
                UUID.randomUUID().toString(),
                randomSong,
                systemUser,
                initStatus
        );

        if ("bilibili".equals(randomSong.platform())) {
            IMusicApiService service = getApiService(randomSong.platform());
            service.prefetchMusic(randomSong.id());
        }

        // 加入队列
        musicQueue.add(item);

        // 广播队列更新 (让前端看到是 AutoDJ 点的歌)
        broadcastQueueUpdate();

        // 立即尝试播放
        playNextInQueue();
    }

    private synchronized void playNextInQueue() {
        if (nowPlaying.get() != null || musicQueue.isEmpty() || isLoading.get()) {
            return;
        }

        //  尝试寻找一首“就绪”的歌
        MusicQueueItem nextItem = findNextPlayableMusic();

        //  如果队列不为空，但没找到可播放的（说明都在下载中）
        // 或者是真的空了，且可以从历史播放
        if (nextItem == null) {
            // 如果队列真的空了，且允许历史播放
            if (musicQueue.isEmpty() && !playHistory.isEmpty() && !userService.getOnlineUserSummaries().isEmpty()) {
                triggerAutoPlayFromHistory();
            } else {
                // 队列里有歌，但都在下载中...
                // 暂时什么都不做，等待下一次 playerLoop 或 下载完成事件(未实现) 触发
                // 前端会看到队列里的歌，但播放器处于 Idle 状态
                log.info("All items in queue are downloading or queue empty. Waiting...");
            }
            return;
        }

        isLoading.set(true);
        broadcastPlayerState();
        resetPauseState();

        log.info("Playing next: {}", nextItem.music().name());

        try {
            // 检查是否为 Bilibili 源且已缓存
            if ("bilibili".equals(nextItem.music().platform())) {
                String localUrl = localCacheService.getLocalUrl(nextItem.music().id());

                // 如果本地有缓存，直接构造对象，跳过 Service 层（避免多余的网络请求）
                if (localUrl != null) {
                    log.info("Hit local cache for Bilibili: {}", nextItem.music().name());

                    // 利用队列中已有的元数据构建播放对象
                    PlayableMusic cachedMusic = new PlayableMusic(
                            nextItem.music().id(),
                            nextItem.music().name(),
                            nextItem.music().artists(),
                            nextItem.music().duration(),
                            nextItem.music().platform(),
                            localUrl, // 使用本地静态资源路径
                            nextItem.music().coverUrl(),
                            false // 不需要代理，直接播静态文件
                    );

                    // 直接应用，无需 subscribe 异步等待
                    applyNewSong(cachedMusic, nextItem);
                    return; // 结束方法
                }
            }

            // 获取服务并播放 (此时 getPlayableMusic 应该返回本地链接)
            IMusicApiService service = getApiService(nextItem.music().platform());
            service.getPlayableMusic(nextItem.music().id())
                    .timeout(Duration.ofSeconds(10))
                    .subscribe(playableMusic -> {
                        // 此时 playableMusic.needsProxy 应该为 false (B站源返回本地url)
                        applyNewSong(playableMusic, nextItem);
                    }, error -> {
                        log.error("Play failed", error);
                        broadcastEvent("ERROR", "LOAD_FAILED", "ADMIN", nextItem.music().name());
                        isLoading.set(false);
                        // 失败了移除，尝试下一首
                        musicQueue.remove(nextItem); // 确保移除
                        broadcastQueueUpdate();
                        broadcastPlayerState();
                        playNextInQueue();
                    });
        } catch (Exception e) {
            log.error("Unexpected error in playNextInQueue", e);
            isLoading.set(false);
            broadcastPlayerState();
        }
    }

    /**
     * 新增核心逻辑：寻找下一首可播放的歌曲
     * 规则：
     * 1. 优先找 "TOP-" 置顶的。
     * 2. 如果是随机模式，随机找一首。
     * 3. 如果是顺序模式，找队头。
     * 4. 关键：无论选中谁，如果它是 Bilibili 源且还没下载完，就跳过它找下一个。
     *    如果所有候选都在下载，返回 null。
     */
    private MusicQueueItem findNextPlayableMusic() {
        // 创建副本以防并发修改
        List<MusicQueueItem> snapshot = new ArrayList<>(musicQueue);
        if (snapshot.isEmpty()) return null;

        // ---------------------------------------------------------
        // 1. 优先处理置顶 (TOP-)
        // ---------------------------------------------------------
        List<MusicQueueItem> topItem = snapshot.stream()
                .filter(i -> i.queueId().startsWith("TOP-"))
                .toList();

        if (!CollectionUtils.isEmpty(topItem)) {
            for (MusicQueueItem item : topItem) {
                // 检查 Bilibili 源的状态
                if ("bilibili".equals(item.music().platform())) {
                    CacheStatus status = localCacheService.getStatus(item.music().id());

                    if (status == CacheStatus.FAILED) {
                        // 下载失败：从主队列移除，广播通知，递归查找下一首
                        log.warn("Top song {} failed to download. Removing.", item.music().name());
                        musicQueue.remove(item);
                        broadcastQueueUpdate();
                        broadcastEvent("ERROR", "LOAD_FAILED", "SYSTEM", item.music().name());
                        return findNextPlayableMusic();
                    }

                    if (status == null) {
                        log.warn("Song {} status unknown (restart?). Re-triggering download.", item.music().name());
                        // 重新触发下载
                        getApiService("bilibili").prefetchMusic(item.music().id());
                        // 跳过，等下载好了再说
                    }

                    else if (status != CacheStatus.COMPLETED) {
                        // 下载中 (PENDING / DOWNLOADING)：暂时跳过置顶，去尝试播放普通队列的歌
                        // (策略选择：不阻塞播放器，优先让已就绪的歌播放)
                        log.info("Top song {} is downloading, looking for others...", item.music().name());
                    }
                    else {
                        // 下载完成：播放
                        musicQueue.remove(item);
                        lastPlayedUserToken.set(item.enqueuedBy().token());
                        return item;
                    }
                } else {
                    // 网易云等无需下载的源：直接播放
                    musicQueue.remove(item);
                    lastPlayedUserToken.set(item.enqueuedBy().token());
                    return item;
                }
            }
        }

        // 剔除掉置顶歌曲，剩下的参与常规调度
        List<MusicQueueItem> candidates = snapshot.stream()
                .filter(i -> !i.queueId().startsWith("TOP-"))
                .toList();

        if (candidates.isEmpty()) return null;



        // ---------------------------------------------------------
        // 2. 随机模式 (Fair Shuffle - 公平调度)
        // ---------------------------------------------------------
        if (isShuffle.get()) {
            // A. 按用户分组 Map<UserToken, List<Song>>
            Map<String, List<MusicQueueItem>> userSongsMap = candidates.stream()
                    .collect(Collectors.groupingBy(item -> item.enqueuedBy().token()));

            // B. 获取所有待播放的用户列表
            List<String> userTokens = new ArrayList<>(userSongsMap.keySet());

            // C. 随机打乱用户顺序
            Collections.shuffle(userTokens);

            // D. 关键逻辑：防连续播放
            // 如果排队用户不止一人，且列表里包含上一个播放的人，把他移到最后
            String lastToken = lastPlayedUserToken.get();
            if (userTokens.size() > 1 && userTokens.contains(lastToken)) {
                userTokens.remove(lastToken);
                userTokens.add(lastToken); // 放到队尾
            }

            // E. 双重遍历：遍历用户 -> 遍历该用户的歌
            for (String userToken : userTokens) {
                List<MusicQueueItem> userSongs = userSongsMap.get(userToken);

                // 打乱该用户的歌单（实现该用户内部的随机）
                Collections.shuffle(userSongs);

                // 寻找该用户第一首 "Ready" 的歌
                for (MusicQueueItem item : userSongs) {
                    // --- 状态检查 ---
                    if (isReadyToPlay(item)) continue;

                    musicQueue.remove(item);
                    lastPlayedUserToken.set(userToken);
                    return item;
                    }
                }
                // 如果这个用户的所有歌都在下载中，循环继续，检查下一个用户...
            }else {
            // 顺序模式
            // 从前往后找，找到第一个 Ready 的
            for (MusicQueueItem item : candidates) {
                if (isReadyToPlay(item)) continue; // 下载中，跳过

                // 找到可播放歌曲
                musicQueue.remove(item);
                lastPlayedUserToken.set(item.enqueuedBy().token());
                return item;
            }
        }

        return null; // 所有候选歌曲都在下载中，或者队列为空
    }

    private boolean isReadyToPlay(MusicQueueItem item) {
        if ("bilibili".equals(item.music().platform())) {
            CacheStatus status = localCacheService.getStatus(item.music().id());

            if (status == CacheStatus.FAILED) {
                log.warn("Song {} failed to download. Removing.", item.music().name());
                musicQueue.remove(item); // 剔除坏死节点
                broadcastQueueUpdate();
                // 不返回，继续检查该用户的下一首歌
                return true;
            }
            if (status != CacheStatus.COMPLETED) {
                // 下载中，跳过这首，检查该用户的下一首
                return true;
            }
        }
        return false;
    }


    private void applyNewSong(PlayableMusic music, MusicQueueItem queueItem) {
        NowPlayingInfo newNowPlaying = new NowPlayingInfo(
                music,
                Instant.now().toEpochMilli(),
                queueItem.enqueuedBy().token(),
                queueItem.enqueuedBy().name());

        if (nowPlaying.compareAndSet(null, newNowPlaying)) {
            log.info("Now playing: {}", music.name());

            // 这里 isLoading 设为 false，前端 Loading 消失，开始请求音频
            isLoading.set(false);

            broadcastNowPlaying(newNowPlaying);
            broadcastPlayerState();
            broadcastQueueUpdate();
        } else {
            // 极少情况：并发冲突
            isLoading.set(false);
        }
    }

    public PlayerState getCurrentPlayerState() {
        NowPlayingInfo current = nowPlaying.get();
        NowPlayingInfo infoToSend = null;

        // 如果有正在播放的歌曲，我们需要修正它的 startTime
        if (current != null) {
            // 计算“有效开始时间” = 原始开始时间 + 总暂停时长
            // 这样前端只需要做减法，不需要关心中间暂停了多久
            long effectiveStartTime = current.startTimeMillis() + totalPausedTimeMillis.get();

            // 创建一个新的 NowPlayingInfo 对象（Record 是不可变的，所以要 new 一个新的）
            infoToSend = new NowPlayingInfo(
                    current.music(),
                    effectiveStartTime,
                    current.enqueuedById(),
                    current.enqueuedByName()
            );
        }

        boolean effectiveIsLoading = (current == null) && isLoading.get();

        return new PlayerState(
                infoToSend, // 使用修正后的 info
                getQueueWithUpdatedStatus(),
                isShuffle.get(),
                userService.getOnlineUserSummaries(),
                isPaused.get(),
                isPaused.get() ? pauseStateChangeTime.get() : 0,
                System.currentTimeMillis(),
                effectiveIsLoading
        );
    }

    private String getUserName(String sessionId) {
        return userService.getUser(sessionId).map(User::getName).orElse("Unknown User");
    }

    public void enqueue(EnqueueRequest request, String sessionId) {
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) {
            log.warn("Enqueue ignored: User session {} not found (Server restarted?).", sessionId);
            //TODO 发送一个错误提示给前端让其刷新
            return;
        }
        User enqueuer = userOpt.get();

        // 查重
        boolean alreadyExists = musicQueue.stream()
                .anyMatch(item -> item.music().id().equals(request.musicId()) && item.music().platform().equals(request.platform()));
        if (alreadyExists) {
            return;
        }

        IMusicApiService service = getApiService(request.platform());

        service.getPlayableMusic(request.musicId())
                .subscribe(playableMusic -> {
                    Music music = new Music(playableMusic.id(), playableMusic.name(), playableMusic.artists(), playableMusic.duration(), playableMusic.platform(), playableMusic.coverUrl());

                    // 初始化状态：如果是 B站，默认 PENDING，网易云则 READY
                    String initStatus = "bilibili".equals(request.platform()) ? "PENDING" : "READY";

                    MusicQueueItem newItem = new MusicQueueItem(UUID.randomUUID().toString(), music,
                            new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(),
                                    enqueuer.getName()), initStatus);
                    musicQueue.add(newItem);
                    log.info("{} enqueued: {}", enqueuer.getName(), music.name());

                    broadcastQueueUpdate();
                    // 广播添加事件
                    broadcastEvent("SUCCESS", "ADD", enqueuer.getToken(), music.name());
                },
                error -> {
                    log.error("Enqueue failed for musicId: {}", request.musicId(), error);

                    // 提取错误信息 (去掉一些技术性前缀，只保留核心原因)
                    String msg = error.getMessage();
                    if (msg.contains("Could not get Bilibili video info")) {
                        msg = "无效资源或API受限";
                    }

                    // 广播错误事件给前端
                    // 前端 Toast 会显示: [ERROR] 加载失败: 无效资源或API受限
                    broadcastEvent("ERROR", "LOAD_FAILED", enqueuer.getToken(), "添加失败: " + msg);
                });
    }

    public void enqueuePlaylist(EnqueuePlaylistRequest request, String sessionId) {
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) {
            log.warn("Enqueue ignored: User session {} not found (Server restarted?).", sessionId);
            //TODO 发送一个错误提示给前端让其刷新
            return;
        }
        User enqueuer = userOpt.get();
        String operatorName = enqueuer.getName();

        IMusicApiService service = getApiService(request.platform());
        service.getPlaylistMusics(request.playlistId(), 0, PLAYLIST_ADD_LIMIT)
                .subscribe(musics -> {
                    musics.forEach(m -> service.prefetchMusic(m.id()));

                    // 初始化状态：如果是 B站，默认 PENDING，网易云则 READY
                    String initStatus = "bilibili".equals(request.platform()) ? "PENDING" : "READY";

                    List<MusicQueueItem> itemsToAdd = musics.stream()
                            .filter(music -> musicQueue.stream().noneMatch(item -> item.music().id().equals(music.id())))
                            .map(music -> new MusicQueueItem(UUID.randomUUID().toString(), music,
                                    new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName()), initStatus))
                            .toList();

                    musicQueue.addAll(itemsToAdd);
                    log.info("{} enqueued {} songs from playlist", operatorName, itemsToAdd.size());

                    broadcastQueueUpdate();
                    // 广播批量添加事件
                    broadcastEvent("SUCCESS", "IMPORT", enqueuer.getToken(), String.valueOf(itemsToAdd.size()));
                });
    }

    private List<MusicQueueItem> getQueueWithUpdatedStatus() {
        return musicQueue.stream().map(item -> {
            // 网易云永远是 READY
            if ("netease".equals(item.music().platform())) {
                // 如果原始状态不是 READY，修正它 (避免反复创建对象)
                return "READY".equals(item.status()) ? item : item.withStatus("READY");
            }

            // Bilibili 查询缓存服务
            if ("bilibili".equals(item.music().platform())) {
                CacheStatus cacheStatus = localCacheService.getStatus(item.music().id());

                String statusStr;
                if (cacheStatus == CacheStatus.COMPLETED) {
                    statusStr = "READY";
                } else if (cacheStatus == CacheStatus.DOWNLOADING) {
                    statusStr = "DOWNLOADING";
                } else if (cacheStatus == CacheStatus.FAILED) {
                    statusStr = "FAILED";
                } else {
                    statusStr = "PENDING";
                }

                // 只有状态不一致时才创建新对象
                if (!statusStr.equals(item.status())) {
                    return item.withStatus(statusStr);
                }
            }
            return item;
        }).toList();
    }

    public synchronized void topSong(String queueId, String sessionId) {

        Optional<MusicQueueItem> itemToTop = musicQueue.stream()
                .filter(item -> item.queueId().equals(queueId))
                .findFirst();

        if (itemToTop.isPresent()) {
            MusicQueueItem item = itemToTop.get();
            musicQueue.remove(item);

            String currentStatus = "PENDING";

            if ("bilibili".equals(item.music().platform())) {
                CacheStatus status = localCacheService.getStatus(item.music().id());
                if (status == CacheStatus.COMPLETED) {
                    currentStatus = "READY";
                } else if (status == CacheStatus.DOWNLOADING) {
                    currentStatus = "DOWNLOADING";
                } else if (status == CacheStatus.FAILED) {
                    currentStatus = "FAILED";
                }
            } else {
                // 网易云直接 Ready
                currentStatus = "READY";
            }

            MusicQueueItem toppedItem = new MusicQueueItem(
                    "TOP-" + item.queueId(),
                    item.music(),
                    item.enqueuedBy(),
                    currentStatus);
            List<MusicQueueItem> tempQueue = new ArrayList<>(musicQueue);
            musicQueue.clear();
            musicQueue.add(toppedItem);
            musicQueue.addAll(tempQueue);

            log.info("Song topped: {}", item.music().name());
            broadcastQueueUpdate();
            // 广播置顶事件
            broadcastEvent("INFO", "TOP", sessionId, item.music().name());
            if (nowPlaying.get() == null) {
                playNextInQueue();
            }
        }
    }

    // 辅助方法：检查冷却时间
    private boolean isRateLimited(String userId) {
        long now = System.currentTimeMillis();
        long last = lastControlTimestamp.get();
        if (now - last < GLOBAL_COOLDOWN_MS) {
            log.warn("Action rate limited for user: {}", userId);
            // 广播警告
            broadcastEvent("ERROR", "RATE_LIMIT", userId, null);            return true;
        }
        lastControlTimestamp.set(now);
        return false;
    }

    public void skipToNext(String sessionId) {
        if (isRateLimited(sessionId)) return;

        isLoading.set(false);

        NowPlayingInfo current = nowPlaying.getAndSet(null);
        if (current != null) {
            if (current.music().needsProxy()) {
                musicProxyService.cancelCurrentProxy();
            }
        }

        broadcastNowPlaying(null);
        broadcastPlayerState();

        //广播切歌事件
        broadcastEvent("INFO", "SKIP", getUserToken(sessionId), null);

        playNextInQueue();
    }

    public void togglePause(String sessionId) {
        if (nowPlaying.get() == null) {
            // 如果没歌但队列有歌，尝试播放
            if (!musicQueue.isEmpty()) {
                playNextInQueue();
            }
            return;
        }
        if (isRateLimited(sessionId)) return;
        String operatorName = getUserName(sessionId);

        long now = Instant.now().toEpochMilli();
        if (isPaused.compareAndSet(false, true)) {
            pauseStateChangeTime.set(now);
            log.info("Player paused by {}", operatorName);
            broadcastPlayerState();
            // 广播暂停
            broadcastEvent("INFO", "PAUSE", getUserToken(sessionId), null);
        } else if (isPaused.compareAndSet(true, false)) {
            long pausedDuration = now - pauseStateChangeTime.get();
            totalPausedTimeMillis.addAndGet(pausedDuration);
            pauseStateChangeTime.set(now);
            log.info("Player resumed by {}", operatorName);
            broadcastPlayerState();
            // 广播继续
            broadcastEvent("INFO", "RESUME", getUserToken(sessionId), null);
        }
    }

    private void resetPauseState() {
        isPaused.set(false);
        pauseStateChangeTime.set(0);
        totalPausedTimeMillis.set(0);
    }


    public void toggleShuffle(String sessionId) {
        if (isRateLimited(sessionId)) return;
        String operatorName = getUserName(sessionId);
        boolean current;
        do {
            current = isShuffle.get();
        } while (!isShuffle.compareAndSet(current, !current));
        boolean newState = !current;
        log.info("Shuffle mode set to {} by {}", newState, operatorName);
        broadcastPlayerState();
        // 广播随机模式
        broadcastEvent("INFO", "SHUFFLE", getUserToken(sessionId), newState ? "ON" : "OFF");
    }

    // --- Helper and Broadcasting methods ---

    private IMusicApiService getApiService(String platform) {
        IMusicApiService service = apiServiceMap.get(platform);
        if (service == null) {
            throw new ApiRequestException("Unsupported platform: " + platform);
        }
        return service;
    }


    public void removeSongFromQueue(String queueId, String sessionId) {
        String operatorName = getUserName(sessionId);
        final String finalQueueId = queueId.startsWith("TOP-") ? queueId.substring(4) : queueId;

        // 查找歌曲名用于提示
        Optional<MusicQueueItem> target = musicQueue.stream()
                .filter(item -> item.queueId().equals(finalQueueId) || item.queueId().equals("TOP-" + finalQueueId))
                .findFirst();

        boolean removed = musicQueue.removeIf(item ->
                item.queueId().equals(finalQueueId) || item.queueId().equals("TOP-" + finalQueueId)
        );

        if (removed && target.isPresent()) {
            log.info("Removed song from queue by {}", operatorName);
            broadcastQueueUpdate();
            // 广播删除事件
            broadcastEvent("INFO", "REMOVE", getUserToken(sessionId), target.get().music().name());
        }
    }

    private void addToHistory(Music music) {
        if (music == null) return;

        synchronized (playHistory) {
            playHistory.removeIf(m -> m.id().equals(music.id()) && m.platform().equals(music.platform()));
            playHistory.add(music);
            if (playHistory.size() > HISTORY_LIMIT) {
                playHistory.removeFirst(); // 移除最早的
            }
        }
        log.debug("Added to history: {}. History size: {}", music.name(), playHistory.size());
    }

    private void broadcastNowPlaying(NowPlayingInfo info) {
        messagingTemplate.convertAndSend("/topic/player/now-playing", info);
    }

    private void broadcastQueueUpdate() {
        messagingTemplate.convertAndSend("/topic/player/queue", getQueueWithUpdatedStatus());
    }

    @EventListener
    public void handleDownloadEvent(DownloadStatusEvent event) {
        // 只有当队列里包含这首歌时，才需要广播更新
        boolean existsInQueue = musicQueue.stream()
                .anyMatch(item -> item.music().id().equals(event.getMusicId()));

        if (existsInQueue) {
            log.debug("Download status changed for {}, updating queue UI.", event.getMusicId());
            // 如果是失败状态，findNextPlayableMusic 会负责移除和发通知
            // 这里我们主要负责刷新 UI (LOADING -> READY)
            broadcastQueueUpdate();

            // 额外检查：如果是失败了，是否需要立即触发清理逻辑？
            // 虽然 playerLoop 会定期跑，但立即触发能让用户更快收到“移除通知”
            if (localCacheService.getStatus(event.getMusicId()) == CacheStatus.FAILED) {
                // 简单触发一次调度检查，它会自动剔除坏死节点
                playNextInQueue();
            }
        }
    }

    public void broadcastPlayerState() {
        messagingTemplate.convertAndSend("/topic/player/state", getCurrentPlayerState());
    }

    public void broadcastOnlineUsers() {
        messagingTemplate.convertAndSend("/topic/users/online", userService.getOnlineUserSummaries());
    }

    public void broadcastPasswordChanged() {
        broadcastEvent("ERROR", "PASSWORD_CHANGED", "ADMIN", null);
    }

    // 广播通用事件
    private void broadcastEvent(String type, String action, String userId, String payload) {
        messagingTemplate.convertAndSend("/topic/player/events", new PlayerEvent(type, action, userId, payload));
    }

    public void resetSystem() {
        log.warn("!!!SYSTEM RESET INITIATED!!!");

        // 1. 停止当前播放
        nowPlaying.set(null);

        // 2. 清空队列
        musicQueue.clear();

        // 3. 清空历史记录 (防止自动播放复活)
        playHistory.clear();

        // 4. 重置状态变量
        isPaused.set(false);
        totalPausedTimeMillis.set(0);
        pauseStateChangeTime.set(0);
        isShuffle.set(false); //

        // 5. 停止所有正在进行的代理下载
        musicProxyService.cancelCurrentProxy();

        // 6. 广播全空状态
        broadcastPlayerState();
        broadcastQueueUpdate();

        //清空聊天历史
        chatService.clearHistory();

        isLoading.set(false);

        log.warn("System reset complete.");
        broadcastEvent("ERROR", "RESET", "ADMIN", null);
    }
}
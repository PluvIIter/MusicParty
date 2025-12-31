package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;

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

    public MusicPlayerService(SimpMessagingTemplate messagingTemplate, List<IMusicApiService> apiServices, UserService userService, MusicProxyService musicProxyService, ChatService chatService) {
        this.messagingTemplate = messagingTemplate;
        // Create a map of services, keyed by platform name for easy lookup
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.userService = userService;
        this.musicProxyService = musicProxyService;
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

        // 构造队列项
        MusicQueueItem item = new MusicQueueItem(
                UUID.randomUUID().toString(),
                randomSong,
                systemUser
        );

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

        isLoading.set(true);
        broadcastPlayerState();

        MusicQueueItem nextItem = getNextMusicFromQueue();
        if (nextItem == null) {
            broadcastNowPlaying(null);
            broadcastPlayerState();
            isLoading.set(false); // 队列为空，解锁
            return;
        }

        // NEW: Reset pause state for the new song
        resetPauseState();

        log.info("Attempting to play next song: {}", nextItem.music().name());

        IMusicApiService service = getApiService(nextItem.music().platform());
        service.getPlayableMusic(nextItem.music().id())
                .doOnSuccess(data -> log.info("成功获取播放链接: {}", data.url()))
                .doOnError(e -> {
                    log.error("获取播放链接失败，原因: ", e); // 【关键】把异常堆栈打印出来
                    isLoading.set(false);
                    broadcastQueueUpdate();
                    playNextInQueue();
                })
                .subscribe(playableMusic -> {
                    if (playableMusic.needsProxy()) {
                        // B站源：必须等待代理准备就绪
                        musicProxyService.startProxy(playableMusic.url())
                                .doOnSuccess(unused -> {
                                    // 代理准备好了！
                                    log.info("Proxy ready, broadcasting to clients.");

                                    // 构建代理地址 + 时间戳
                                    String uniqueProxyUrl = "/proxy/stream?t=" + System.currentTimeMillis();

                                    PlayableMusic proxyMusic = new PlayableMusic(
                                            playableMusic.id(), playableMusic.name(), playableMusic.artists(),
                                            playableMusic.duration(), playableMusic.platform(),
                                            uniqueProxyUrl,
                                            playableMusic.coverUrl(), true
                                    );

                                    applyNewSong(proxyMusic, nextItem);
                                })
                                .doOnError(e -> {
                                    log.error("Proxy start failed", e);
                                    isLoading.set(false);
                                    playNextInQueue();
                                })
                                .subscribe(); // 触发 Mono
                    } else {
                        // 网易云源：直接播放
                        applyNewSong(playableMusic, nextItem);
                    }
                });
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

    private MusicQueueItem getNextMusicFromQueue() {
        if (musicQueue.isEmpty()) {
            return null;
        }

        Optional<MusicQueueItem> toppedItem = musicQueue.stream()
                .filter(i -> i.queueId().startsWith("TOP-"))
                .findFirst();

        if (toppedItem.isPresent()) {
            MusicQueueItem item = toppedItem.get();
            musicQueue.remove(item);
            lastPlayedUserToken.set(item.enqueuedBy().token()); // 记录置顶者
            return item;
        }

        // --- 改进后的随机 (公平调度) ---
        if (isShuffle.get()) {
            List<MusicQueueItem> snapshot = new ArrayList<>(musicQueue);

            // A. 按用户分组
            Map<String, List<MusicQueueItem>> userSongsMap = snapshot.stream()
                    .collect(Collectors.groupingBy(item -> item.enqueuedBy().token()));

            List<String> userIds = new ArrayList<>(userSongsMap.keySet());

            String luckyUserId;

            // B. 核心改进：如果有多个用户在排队，排除掉上一个播放的人
            if (userIds.size() > 1) {
                String lastToken = lastPlayedUserToken.get();
                // 过滤掉刚刚播过的用户
                List<String> candidates = userIds.stream()
                        .filter(id -> !id.equals(lastToken))
                        .toList();

                // 从剩下的候选人中随机选一个
                luckyUserId = candidates.get(new Random().nextInt(candidates.size()));
            } else {
                // 如果只有一个人点歌，那就只能还是他
                luckyUserId = userIds.getFirst();
            }

            // C. 更新最后播放者记录
            lastPlayedUserToken.set(luckyUserId);

            // D. 从选定用户的歌单中随机挑一首
            List<MusicQueueItem> luckyUserSongs = userSongsMap.get(luckyUserId);
            MusicQueueItem selectedItem = luckyUserSongs.get(new Random().nextInt(luckyUserSongs.size()));

            musicQueue.remove(selectedItem);
            return selectedItem;
        } else {
            // 顺序模式
            MusicQueueItem item = musicQueue.poll();
            if (item != null) lastPlayedUserToken.set(item.enqueuedBy().token());
            return item;
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
                new ArrayList<>(musicQueue),
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

        boolean alreadyExists = musicQueue.stream()
                .anyMatch(item -> item.music().id().equals(request.musicId()) && item.music().platform().equals(request.platform()));
        if (alreadyExists) {
            return;
        }

        IMusicApiService service = getApiService(request.platform());
        service.getPlayableMusic(request.musicId())
                .subscribe(playableMusic -> {
                    Music music = new Music(playableMusic.id(), playableMusic.name(), playableMusic.artists(), playableMusic.duration(), playableMusic.platform(), playableMusic.coverUrl());
                    MusicQueueItem newItem = new MusicQueueItem(UUID.randomUUID().toString(), music, new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName()));
                    musicQueue.add(newItem);
                    log.info("{} enqueued: {}", enqueuer.getName(), music.name());

                    broadcastQueueUpdate();
                    // 广播添加事件
                    broadcastEvent("SUCCESS", "ADD", enqueuer.getToken(), music.name());
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
                    List<MusicQueueItem> itemsToAdd = musics.stream()
                            .filter(music -> musicQueue.stream().noneMatch(item -> item.music().id().equals(music.id())))
                            .map(music -> new MusicQueueItem(UUID.randomUUID().toString(), music, new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName())))
                            .toList();

                    musicQueue.addAll(itemsToAdd);
                    log.info("{} enqueued {} songs from playlist", operatorName, itemsToAdd.size());

                    broadcastQueueUpdate();
                    // 广播批量添加事件
                    broadcastEvent("SUCCESS", "IMPORT", enqueuer.getToken(), String.valueOf(itemsToAdd.size()));
                });
    }

    public synchronized void topSong(String queueId, String sessionId) {

        Optional<MusicQueueItem> itemToTop = musicQueue.stream()
                .filter(item -> item.queueId().equals(queueId))
                .findFirst();

        if (itemToTop.isPresent()) {
            MusicQueueItem item = itemToTop.get();
            musicQueue.remove(item);
            MusicQueueItem toppedItem = new MusicQueueItem("TOP-" + item.queueId(), item.music(), item.enqueuedBy());
            List<MusicQueueItem> tempQueue = new ArrayList<>(musicQueue);
            musicQueue.clear();
            musicQueue.add(toppedItem);
            musicQueue.addAll(tempQueue);

            log.info("Song topped: {}", item.music().name());
            broadcastQueueUpdate();
            // 广播置顶事件
            broadcastEvent("INFO", "TOP", sessionId, item.music().name());
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

        NowPlayingInfo current = nowPlaying.getAndSet(null);
        if (current != null) {
            if (current.music().needsProxy()) {
                musicProxyService.cancelCurrentProxy();
            }
        }

        //广播切歌事件
        broadcastEvent("INFO", "SKIP", getUserToken(sessionId), null);

        broadcastPlayerState();
        playerLoop();
    }

    public void togglePause(String sessionId) {
        if (nowPlaying.get() == null) return;
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
        messagingTemplate.convertAndSend("/topic/player/queue", new ArrayList<>(musicQueue));
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
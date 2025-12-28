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

    private final List<Music> playHistory = Collections.synchronizedList(new LinkedList<>());
    private static final int HISTORY_LIMIT = 50;

    private static final int PLAYLIST_ADD_LIMIT = 100;

    private final AtomicLong lastControlTimestamp = new AtomicLong(0);
    private static final long GLOBAL_COOLDOWN_MS = 1000; // 全局冷却时间 1秒

    public MusicPlayerService(SimpMessagingTemplate messagingTemplate, List<IMusicApiService> apiServices, UserService userService, MusicProxyService musicProxyService) {
        this.messagingTemplate = messagingTemplate;
        // Create a map of services, keyed by platform name for easy lookup
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.userService = userService;
        this.musicProxyService = musicProxyService;
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
        UserSummary systemUser = new UserSummary("SYSTEM", "AutoDJ");

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
        // Ensure only one thread modifies the queue and nowPlaying state at a time
        if (nowPlaying.get() != null || musicQueue.isEmpty()) {
            return; // Already playing or queue is empty
        }

        // NEW: Reset pause state for the new song
        resetPauseState();

        MusicQueueItem nextItem = getNextMusicFromQueue();
        if (nextItem == null) {
            broadcastNowPlaying(null); // Ensure clients know nothing is playing
            broadcastPlayerState();
            return;
        }

        log.info("Attempting to play next song: {}", nextItem.music().name());

        IMusicApiService service = getApiService(nextItem.music().platform());
        service.getPlayableMusic(nextItem.music().id())
                .doOnSuccess(data -> log.info("成功获取播放链接: {}", data.url()))
                .doOnError(e -> {
                    log.error("获取播放链接失败，原因: ", e); // 【关键】把异常堆栈打印出来
                    broadcastQueueUpdate();
                    playNextInQueue();
                })
                .subscribe(playableMusic -> {
                    PlayableMusic finalPlayableMusic = playableMusic;
                    if (playableMusic.needsProxy()) {
                        musicProxyService.startProxy(playableMusic.url());
                        // Rewrite the URL to point to our proxy
                        finalPlayableMusic = new PlayableMusic(
                                playableMusic.id(), playableMusic.name(), playableMusic.artists(),
                                playableMusic.duration(), playableMusic.platform(),
                                "/proxy/stream", // The static proxy URL
                                playableMusic.coverUrl(), true
                        );
                    }

                    NowPlayingInfo newNowPlaying = new NowPlayingInfo(
                            finalPlayableMusic,
                            Instant.now().toEpochMilli(),
                            nextItem.enqueuedBy().name());

                    if (nowPlaying.compareAndSet(null, newNowPlaying)) {
                        log.info("Now playing: {}", finalPlayableMusic.name());

                        // 1. 推送当前播放信息 (前端收到这个才会开始播放)
                        broadcastNowPlaying(newNowPlaying);

                        // 2. 推送最新状态 (包含时间戳等)
                        broadcastPlayerState();

                        // 3. 推送队列更新 (因为歌曲从队列移出了)
                        broadcastQueueUpdate();
                    }
                });
    }

    private MusicQueueItem getNextMusicFromQueue() {
        if (musicQueue.isEmpty()) {
            return null;
        }

        Optional<MusicQueueItem> toppedItem = musicQueue.stream()
                .filter(i -> i.queueId().startsWith("TOP-"))
                .findFirst();

        if (toppedItem.isPresent()) {
            musicQueue.remove(toppedItem.get());
            log.info("Playing topped song: {}", toppedItem.get().music().name());
            return toppedItem.get();
        }

        // 2. 随机模式逻辑 (智能穿插)
        if (isShuffle.get()) {
            // 将队列快照转换为 List，方便操作
            List<MusicQueueItem> snapshot = new ArrayList<>(musicQueue);

            // 核心算法：选择“下一个播放者”
            // 我们不直接随机选一首歌，而是随机选一个“还没轮到的用户”的一首歌

            // Step A: 按用户分组
            Map<String, List<MusicQueueItem>> userSongsMap = new HashMap<>();
            for (MusicQueueItem item : snapshot) {
                // 使用 sessionId 或 name 作为分组依据
                String userId = item.enqueuedBy().sessionId();
                userSongsMap.computeIfAbsent(userId, k -> new ArrayList<>()).add(item);
            }

            // Step B: 获取所有有歌的用户ID，并随机打乱
            // 这决定了这一轮“发牌”的顺序
            List<String> userIds = new ArrayList<>(userSongsMap.keySet());
            Collections.shuffle(userIds);

            // Step C: 选歌
            // 简单策略：直接取打乱后的第一个用户的列表中的第一首歌
            // 进阶策略：这里其实还可以更复杂，比如记录上一次播放的用户，这次尽量避开他。
            // 但“随机打乱用户顺序”已经能在概率上很好地解决扎堆问题了。

            String luckyUserId = userIds.getFirst();
            List<MusicQueueItem> luckyUserSongs = userSongsMap.get(luckyUserId);

            // 再次随机：从该用户的歌单里随机挑一首
            // (这样既保证了用户间的公平，又保证了用户内部的随机)
            int songIndex = new Random().nextInt(luckyUserSongs.size());
            MusicQueueItem selectedItem = luckyUserSongs.get(songIndex);

            // Step D: 从实际队列中移除并返回
            musicQueue.remove(selectedItem);
            return selectedItem;
        } else {
            // Normal mode: poll from the front
            return musicQueue.poll();
        }
    }

    // --- Public methods for controllers ---

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
                    current.enqueuedBy()
            );
        }
        return new PlayerState(
                infoToSend, // 使用修正后的 info
                new ArrayList<>(musicQueue),
                isShuffle.get(),
                userService.getOnlineUserSummaries(),
                isPaused.get(),
                isPaused.get() ? pauseStateChangeTime.get() : 0,
                System.currentTimeMillis()
        );
    }

    private String getUserName(String sessionId) {
        return userService.getUser(sessionId).map(User::getName).orElse("Unknown User");
    }

    public void enqueue(EnqueueRequest request, String sessionId) {
        User enqueuer = userService.getUser(sessionId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        boolean alreadyExists = musicQueue.stream()
                .anyMatch(item -> item.music().id().equals(request.musicId()) && item.music().platform().equals(request.platform()));
        if (alreadyExists) {
            return;
        }

        IMusicApiService service = getApiService(request.platform());
        service.getPlayableMusic(request.musicId())
                .subscribe(playableMusic -> {
                    Music music = new Music(playableMusic.id(), playableMusic.name(), playableMusic.artists(), playableMusic.duration(), playableMusic.platform(), playableMusic.coverUrl());
                    MusicQueueItem newItem = new MusicQueueItem(UUID.randomUUID().toString(), music, new UserSummary(enqueuer.getSessionId(), enqueuer.getName()));
                    musicQueue.add(newItem);
                    log.info("{} enqueued: {}", enqueuer.getName(), music.name());

                    broadcastQueueUpdate();
                    // 🟢 广播添加成功事件
                    broadcastEvent("SUCCESS", enqueuer.getName() + " 添加了: " + music.name(), enqueuer.getName());
                });
    }

    public void enqueuePlaylist(EnqueuePlaylistRequest request, String sessionId) {
        User enqueuer = userService.getUser(sessionId).orElseThrow();
        String operatorName = enqueuer.getName();

        IMusicApiService service = getApiService(request.platform());
        service.getPlaylistMusics(request.playlistId(), 0, PLAYLIST_ADD_LIMIT)
                .subscribe(musics -> {
                    List<MusicQueueItem> itemsToAdd = musics.stream()
                            .filter(music -> musicQueue.stream().noneMatch(item -> item.music().id().equals(music.id())))
                            .map(music -> new MusicQueueItem(UUID.randomUUID().toString(), music, new UserSummary(enqueuer.getSessionId(), enqueuer.getName())))
                            .toList();

                    musicQueue.addAll(itemsToAdd);
                    log.info("{} enqueued {} songs from playlist", operatorName, itemsToAdd.size());

                    broadcastQueueUpdate();
                    // 🟢 广播批量添加事件
                    broadcastEvent("SUCCESS", operatorName + " 导入了歌单 (" + itemsToAdd.size() + " 首)", operatorName);
                });
    }

    public synchronized void topSong(String queueId, String sessionId) {
        String operatorName = getUserName(sessionId);

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
            // 🟢 广播置顶事件
            broadcastEvent("INFO", operatorName + " 置顶了: " + item.music().name(), operatorName);
        }
    }

    // 🟢 辅助方法：检查冷却时间
    private boolean isRateLimited(String operatorName) {
        long now = System.currentTimeMillis();
        long last = lastControlTimestamp.get();
        if (now - last < GLOBAL_COOLDOWN_MS) {
            log.warn("Action rate limited for user: {}", operatorName);
            // 广播警告
            broadcastEvent("ERROR", "系统冷却中，请勿频繁操作", operatorName);
            return true;
        }
        lastControlTimestamp.set(now);
        return false;
    }

    public void skipToNext(String sessionId) {
        String operatorName = getUserName(sessionId);

        if (isRateLimited(operatorName)) return;

        NowPlayingInfo current = nowPlaying.getAndSet(null);
        if (current != null) {
            if (current.music().needsProxy()) {
                musicProxyService.cancelCurrentProxy();
            }
        }

        // 🟢 广播切歌事件
        broadcastEvent("INFO", operatorName + " 切到了下一首", operatorName);

        broadcastPlayerState();
        playerLoop();
    }

    public void togglePause(String sessionId) {
        String operatorName = getUserName(sessionId);
        if (nowPlaying.get() == null) return;

        if (isRateLimited(operatorName)) return;

        long now = Instant.now().toEpochMilli();
        if (isPaused.compareAndSet(false, true)) {
            pauseStateChangeTime.set(now);
            log.info("Player paused by {}", operatorName);
            broadcastPlayerState();
            // 🟢 广播暂停
            broadcastEvent("INFO", operatorName + " 暂停了播放", operatorName);
        } else if (isPaused.compareAndSet(true, false)) {
            long pausedDuration = now - pauseStateChangeTime.get();
            totalPausedTimeMillis.addAndGet(pausedDuration);
            pauseStateChangeTime.set(now);
            log.info("Player resumed by {}", operatorName);
            broadcastPlayerState();
            // 🟢 广播继续
            broadcastEvent("INFO", operatorName + " 继续了播放", operatorName);
        }
    }

    private void resetPauseState() {
        isPaused.set(false);
        pauseStateChangeTime.set(0);
        totalPausedTimeMillis.set(0);
    }


    public void toggleShuffle(String sessionId) {
        String operatorName = getUserName(sessionId);
        if (isRateLimited(operatorName)) return;
        boolean current;
        do {
            current = isShuffle.get();
        } while (!isShuffle.compareAndSet(current, !current));
        boolean newState = !current;
        log.info("Shuffle mode set to {} by {}", newState, operatorName);
        broadcastPlayerState();
        // 🟢 广播随机模式
        broadcastEvent("INFO", operatorName + (newState ? " 开启了随机播放" : " 关闭了随机播放"), operatorName);
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
            // 🟢 广播删除事件
            broadcastEvent("INFO", operatorName + " 移除了: " + target.get().music().name(), operatorName);
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

    // 🟢 新增：广播通用事件
    private void broadcastEvent(String type, String message, String user) {
        messagingTemplate.convertAndSend("/topic/player/events", new PlayerEvent(type, message, user));
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
        isShuffle.set(false); // 重Ht随机模式

        // 5. 停止所有正在进行的代理下载
        musicProxyService.cancelCurrentProxy();

        // 6. 广播全空状态
        broadcastPlayerState();
        broadcastQueueUpdate();
        broadcastNowPlaying(null);

        log.warn("System reset complete.");
        broadcastEvent("ERROR", "⚠️ 系统已被管理员重置", "ADMIN");
    }
}
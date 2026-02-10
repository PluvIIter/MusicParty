package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class MusicQueueManager {

    private final AppProperties appProperties;

    // 使用并发安全的双端队列
    private final Deque<MusicQueueItem> queue = new ConcurrentLinkedDeque<>();
    private final List<Music> playHistory = Collections.synchronizedList(new LinkedList<>());

    // 用于实现“公平随机播放”：记录上一个播放的用户
    private final AtomicReference<String> lastPlayedUserToken = new AtomicReference<>("");

    // --- Public API for Queue Manipulation ---

    /**
     * 向队列末尾添加一首歌曲
     */
    public synchronized MusicQueueItem add(Music music, UserSummary enqueuedBy, QueueItemStatus initialStatus) {
        // 检查队列是否已满
        if (queue.size() >= appProperties.getQueue().getMaxSize()) {
            return null;
        }

        // 防止重复添加
        if (isMusicInQueue(music.id())) {
            return null;
        }

        MusicQueueItem newItem = new MusicQueueItem(
                UUID.randomUUID().toString(),
                music,
                enqueuedBy,
                initialStatus // 存储枚举的名称
        );
        queue.addLast(newItem);
        return newItem;
    }

    /**
     * 将指定歌曲置顶
     */
    public synchronized boolean top(String queueId) {
        Optional<MusicQueueItem> itemOpt = findByQueueId(queueId);
        if (itemOpt.isEmpty()) {
            return false;
        }

        MusicQueueItem item = itemOpt.get();
        if (queue.remove(item)) {
            // 创建一个新的置顶项，ID 加上 TOP- 前缀
            MusicQueueItem toppedItem = new MusicQueueItem(
                    "TOP-" + item.queueId(),
                    item.music(),
                    item.enqueuedBy(),
                    item.status()
            );
            queue.addFirst(toppedItem);
            return true;
        }
        return false;
    }

    /**
     * 从队列中移除一首歌曲
     */
    public synchronized Optional<MusicQueueItem> remove(String queueId) {
        // 统一处理 TOP- 前缀
        final String finalQueueId = queueId.startsWith("TOP-") ? queueId.substring(4) : queueId;

        Optional<MusicQueueItem> itemOpt = findByQueueId(finalQueueId);
        itemOpt.ifPresent(queue::remove);
        return itemOpt;
    }

    /**
     * 从队列中取出下一首可播放的歌曲
     * @param isShuffle 是否启用随机模式
     * @param onlineUserTokens 在线用户的 Token 集合 (用于优先调度)
     * @return 下一首歌曲，如果队列为空则返回 null
     */
    public synchronized MusicQueueItem pollNext(boolean isShuffle, Map<String, QueueItemStatus> statusMap, Set<String> onlineUserTokens) {
        if (queue.isEmpty()) {
            return pollFromHistory(); // 队列为空时，尝试从历史记录播放
        }

        List<MusicQueueItem> candidates = new ArrayList<>(queue);

        // 1. 优先处理置顶项
        Optional<MusicQueueItem> topItem = candidates.stream()
                .filter(item -> item.queueId().startsWith("TOP-") && isReadyOrFailed(statusMap, item))
                .findFirst();

        if (topItem.isPresent()) {
            queue.remove(topItem.get());
            lastPlayedUserToken.set(topItem.get().enqueuedBy().token());
            return topItem.get();
        }

        // 2. 移除置顶项和下载中的项，进行常规调度
        List<MusicQueueItem> availableItems = candidates.stream()
                .filter(item -> !item.queueId().startsWith("TOP-") && isReadyOrFailed(statusMap, item))
                .toList();

        if (availableItems.isEmpty()) {
            return null; // 所有歌曲都在下载中
        }

        MusicQueueItem chosenItem;
        if (isShuffle) {
            chosenItem = pollNextFairShuffle(availableItems, onlineUserTokens);
        } else {
            chosenItem = availableItems.get(0); // 顺序播放
        }

        queue.remove(chosenItem);
        lastPlayedUserToken.set(chosenItem.enqueuedBy().token());
        return chosenItem;
    }

    /**
     * "公平"随机播放算法：严格轮询 (Strict Round-Robin) + 在线优先
     */
    private MusicQueueItem pollNextFairShuffle(List<MusicQueueItem> availableItems, Set<String> onlineUserTokens) {
        // 1. 按用户分组
        Map<String, List<MusicQueueItem>> userSongsMap = new HashMap<>();
        for (MusicQueueItem item : availableItems) {
            userSongsMap.computeIfAbsent(item.enqueuedBy().token(), k -> new ArrayList<>()).add(item);
        }

        List<String> allUserTokens = new ArrayList<>(userSongsMap.keySet());
        
        // 2. 筛选目标用户池：优先在线用户
        List<String> onlineCandidates = allUserTokens.stream()
                .filter(onlineUserTokens::contains)
                .toList();

        List<String> targetUserTokens;
        if (!onlineCandidates.isEmpty()) {
            targetUserTokens = onlineCandidates;
        } else {
            // 如果没有在线用户有点歌，则使用所有有歌的用户（即离线用户）
            targetUserTokens = allUserTokens;
        }

        // 3. 严格轮询逻辑
        // 对用户列表进行排序，确保顺序固定 (A -> B -> C -> A)
        Collections.sort(targetUserTokens);

        String lastToken = lastPlayedUserToken.get();
        int nextIndex = 0;

        if (targetUserTokens.contains(lastToken)) {
            int currentIndex = targetUserTokens.indexOf(lastToken);
            nextIndex = (currentIndex + 1) % targetUserTokens.size();
        } else {
            // 如果上一个播放的用户不在当前列表中（离开了，或没歌了），
            // 为了平滑过渡，可以尝试寻找比 lastToken 大的第一个用户，或者直接从头开始。
            // 简单起见，从头开始。
            nextIndex = 0;
        }

        String selectedUserToken = targetUserTokens.get(nextIndex);
        List<MusicQueueItem> userSongs = userSongsMap.get(selectedUserToken);

        // 4. 用户内部随机 (Intra-User Shuffle)
        // 选中该用户后，从他的歌单里随机挑一首
        Collections.shuffle(userSongs);
        return userSongs.get(0);
    }

    /**
     * 将播放完的歌曲加入历史记录
     */
    public void addToHistory(Music music) {
        if (music == null) return;
        synchronized (playHistory) {
            playHistory.removeIf(m -> m.id().equals(music.id()) && m.platform().equals(music.platform()));
            playHistory.add(0, music); // 加到最前面
            if (playHistory.size() > appProperties.getQueue().getHistorySize()) {
                playHistory.removeLast();
            }
        }
    }

    /**
     * 清空队列和历史记录
     */
    public synchronized void clearAll() {
        queue.clear();
        playHistory.clear();
        lastPlayedUserToken.set("");
    }

    public synchronized void clearPendingQueue() {
        queue.clear();
    }

    public List<MusicQueueItem> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    public List<Music> getHistorySnapshot() {
        synchronized (playHistory) {
            return new ArrayList<>(playHistory);
        }
    }

    /**
     * 从持久化存储恢复队列和历史记录
     */
    public synchronized void restore(List<MusicQueueItem> loadedQueue, List<Music> loadedHistory) {
        // Clear current
        queue.clear();
        playHistory.clear();
        lastPlayedUserToken.set("");

        // Restore Queue
        if (loadedQueue != null) {
            queue.addAll(loadedQueue);
        }

        // Restore History
        if (loadedHistory != null) {
            playHistory.addAll(loadedHistory);
        }
    }

    // --- Private Helper Methods ---

    private boolean isMusicInQueue(String musicId) {
        return queue.stream().anyMatch(item -> item.music().id().equals(musicId));
    }

    private Optional<MusicQueueItem> findByQueueId(String queueId) {
        final String finalId = queueId.startsWith("TOP-") ? queueId.substring(4) : queueId;
        return queue.stream()
                .filter(item -> item.queueId().replace("TOP-", "").equals(finalId))
                .findFirst();
    }

    private boolean isReadyOrFailed(Map<String, QueueItemStatus> statusMap, MusicQueueItem item) {
        QueueItemStatus status = statusMap.getOrDefault(item.music().id(), QueueItemStatus.PENDING);
        return status == QueueItemStatus.READY || status == QueueItemStatus.FAILED;
    }

    /**
     * 当队列为空时，从历史记录随机取一首作为 AutoDJ
     */
    private MusicQueueItem pollFromHistory() {
        if (playHistory.isEmpty()) {
            return null;
        }
        Music randomSong = playHistory.get(new Random().nextInt(playHistory.size()));

        UserSummary systemUser = new UserSummary("SYSTEM", "SYSTEM", "AutoDJ", false);

        // 注意：历史记录出来的歌需要重新判断状态
        return new MusicQueueItem(
                UUID.randomUUID().toString(),
                randomSong,
                systemUser,
                QueueItemStatus.PENDING
        );
    }
}
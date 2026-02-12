package org.thornex.musicparty.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;

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
    public synchronized TopResult top(String queueId, boolean isShuffle) {
        Optional<MusicQueueItem> itemOpt = findByQueueId(queueId);
        if (itemOpt.isEmpty()) {
            return TopResult.NONE;
        }

        MusicQueueItem item = itemOpt.get();

        // 1. 如果已经是全局置顶 (TOP-)，不做操作
        if (item.queueId().startsWith("TOP-")) {
            return TopResult.NONE;
        }

        // 2. 如果是个人置顶 (USERTOP-) -> 升级为全局置顶 (TOP-)
        // 无论当前是否随机模式，二次置顶都视为升级
        if (item.queueId().startsWith("USERTOP-")) {
            if (queue.remove(item)) {
                // 去掉 USERTOP- (8 chars), 加上 TOP-
                String originalId = item.queueId().substring(8);
                MusicQueueItem newItem = new MusicQueueItem(
                        "TOP-" + originalId,
                        item.music(),
                        item.enqueuedBy(),
                        item.status()
                );
                queue.addFirst(newItem);
                return TopResult.GLOBAL;
            }
            return TopResult.NONE;
        }

        // 3. 如果是普通歌曲
        if (isShuffle) {
            // 随机模式下 -> 变为个人置顶 (USERTOP-)
            // 为了保持物理顺序不变（以便切换回顺序模式时不乱），我们需要原地替换
            // 由于 ConcurrentLinkedDeque 不支持原地替换，我们用重建队列的方式
            List<MusicQueueItem> snapshot = new ArrayList<>(queue);
            int index = snapshot.indexOf(item);
            if (index != -1) {
                MusicQueueItem newItem = new MusicQueueItem(
                        "USERTOP-" + item.queueId(),
                        item.music(),
                        item.enqueuedBy(),
                        item.status()
                );
                snapshot.set(index, newItem);
                queue.clear();
                queue.addAll(snapshot);
                return TopResult.PERSONAL;
            }
        } else {
            // 顺序模式下 -> 直接变为全局置顶 (TOP-)
            if (queue.remove(item)) {
                MusicQueueItem newItem = new MusicQueueItem(
                        "TOP-" + item.queueId(),
                        item.music(),
                        item.enqueuedBy(),
                        item.status()
                );
                queue.addFirst(newItem);
                return TopResult.GLOBAL;
            }
        }
        return TopResult.NONE;
    }

    /**
     * 从队列中移除指定用户的所有点歌
     * @return 移除的数量
     */
    public synchronized int removeByUser(String userToken) {
        List<MusicQueueItem> toRemove = queue.stream()
                .filter(item -> item.enqueuedBy().token().equals(userToken))
                .toList();
        
        toRemove.forEach(queue::remove);
        return toRemove.size();
    }

    /**
     * 从队列中移除一首歌曲
     */
    public synchronized Optional<MusicQueueItem> remove(String queueId) {
        // 统一处理前缀
        String idToFind = stripPrefix(queueId);

        Optional<MusicQueueItem> itemOpt = findByQueueId(idToFind);
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

        // 1. 优先处理全局置顶项 (TOP-)
        Optional<MusicQueueItem> topItem = candidates.stream()
                .filter(item -> item.queueId().startsWith("TOP-") && isReadyOrFailed(statusMap, item))
                .findFirst();

        if (topItem.isPresent()) {
            queue.remove(topItem.get());
            return topItem.get();
        }

        // 2. 移除全局置顶项，进行常规调度
        // 注意：USERTOP- 项在常规调度中处理
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
            chosenItem = availableItems.get(0); // 顺序播放，直接取第一个 (包含 USERTOP- 项，按物理顺序)
        }

        queue.remove(chosenItem);
        lastPlayedUserToken.set(chosenItem.enqueuedBy().token());
        return chosenItem;
    }

    /**
     * "公平"随机播放算法：严格轮询 (Strict Round-Robin) + 在线优先 + 个人置顶优先
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
            targetUserTokens = new ArrayList<>(onlineCandidates);
        } else {
            targetUserTokens = allUserTokens;
        }

        // 3. 严格轮询逻辑
        Collections.sort(targetUserTokens);

        String lastToken = lastPlayedUserToken.get();
        int nextIndex = 0;

        if (targetUserTokens.contains(lastToken)) {
            int currentIndex = targetUserTokens.indexOf(lastToken);
            nextIndex = (currentIndex + 1) % targetUserTokens.size();
        } else {
            // 寻找 Token 排序中紧随其后的用户，而非直接重置为 0
            nextIndex = 0;
            for (int i = 0; i < targetUserTokens.size(); i++) {
                if (targetUserTokens.get(i).compareTo(lastToken) > 0) {
                    nextIndex = i;
                    break;
                }
            }
        }

        String selectedUserToken = targetUserTokens.get(nextIndex);
        List<MusicQueueItem> userSongs = userSongsMap.get(selectedUserToken);

        // 4. 检查是否有个人置顶 (USERTOP-)
        Optional<MusicQueueItem> userTop = userSongs.stream()
                .filter(i -> i.queueId().startsWith("USERTOP-"))
                .findFirst();

        if (userTop.isPresent()) {
            return userTop.get();
        }

        // 5. 用户内部随机
        Collections.shuffle(userSongs);
        return userSongs.get(0);
    }
    
    // ... (rest of methods)

    private Optional<MusicQueueItem> findByQueueId(String queueId) {
        final String finalId = stripPrefix(queueId);
        return queue.stream()
                .filter(item -> stripPrefix(item.queueId()).equals(finalId))
                .findFirst();
    }
    
    private String stripPrefix(String queueId) {
        if (queueId.startsWith("TOP-")) return queueId.substring(4);
        if (queueId.startsWith("USERTOP-")) return queueId.substring(8);
        return queueId;
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

    private boolean isMusicInQueue(String musicId) {
        return queue.stream().anyMatch(item -> item.music().id().equals(musicId));
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
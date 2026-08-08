package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MusicQueueManagerFmMarkerTest {

    private final AppProperties props = new AppProperties();
    private final MusicQueueManager qm = new MusicQueueManager(props);

    @Test
    void ensureFmMarkerAddsOnceAndDedups() {
        qm.ensureFmMarker();
        assertEquals(1, qm.getQueueSnapshot().size());
        MusicQueueItem marker = qm.getQueueSnapshot().get(0);
        assertEquals("netease-fm", marker.music().platform());
        assertEquals("__FM__", marker.enqueuedBy().token());
        qm.ensureFmMarker();
        assertEquals(1, qm.getQueueSnapshot().size(), "重复调用不应重复添加");
    }

    @Test
    void hasPlayableItemsUsesStatusMap() {
        Music m = new Music("1", "a", List.of(), 1000L, "netease", null);
        qm.add(m, new UserSummary("t1", "s1", "u1", false), QueueItemStatus.READY);
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put("1", QueueItemStatus.READY);
        assertTrue(qm.hasPlayableItems(statusMap));
        statusMap.put("1", QueueItemStatus.PENDING);
        assertFalse(qm.hasPlayableItems(statusMap));
    }

    @Test
    void fmMarkerSelectableInTotalShuffleWithEmptyOnlineUsers() {
        qm.ensureFmMarker();
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put(MusicQueueManager.FM_MARKER_ID, QueueItemStatus.READY);
        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, false, false, statusMap, Collections.emptySet());
        assertNotNull(picked, "默认离线过滤下普通随机也应能选中FM标记");
        assertEquals(MusicQueueManager.FM_MARKER_ID, picked.music().platform());
    }

    @Test
    void fmMarkerSelectableInFairShuffleWithEmptyOnlineUsers() {
        qm.ensureFmMarker();
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put(MusicQueueManager.FM_MARKER_ID, QueueItemStatus.READY);
        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, true, false, statusMap, Collections.emptySet());
        assertNotNull(picked, "默认离线过滤下公平随机也应能选中FM标记");
        assertEquals(MusicQueueManager.FM_MARKER_ID, picked.music().platform());
    }

    @Test
    void removeFmMarkerRemovesOnlyMarker() {
        Music m = new Music("1", "a", List.of(), 1000L, "netease", null);
        qm.add(m, new UserSummary("t1", "s1", "u1", false), QueueItemStatus.READY);
        qm.ensureFmMarker();
        assertEquals(2, qm.getQueueSnapshot().size());
        qm.removeFmMarker();
        assertEquals(1, qm.getQueueSnapshot().size(), "应仅移除FM标记，保留真实点歌");
        assertEquals("1", qm.getQueueSnapshot().get(0).music().id());
        qm.removeFmMarker(); // 幂等
        assertEquals(1, qm.getQueueSnapshot().size());
    }

    // ─── 离线歌曲回退：播放列表只有离线用户的歌、没有在线用户点歌时，不应空转 ───

    @Test
    void fairShuffleFallsBackToOfflineSongsWhenNoOnlineUserQueued() {
        Music m = new Music("1", "offlineSong", List.of(), 1000L, "netease", null);
        qm.add(m, new UserSummary("offline-token", null, "offlineUser", false), QueueItemStatus.READY);
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put("1", QueueItemStatus.READY);

        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, true, false, statusMap, Collections.singleton("online-token"));
        assertNotNull(picked, "没有在线用户点歌时，公平随机应回退播放离线用户的歌");
        assertEquals("1", picked.music().id());
    }

    @Test
    void totalShuffleFallsBackToOfflineSongsWhenNoOnlineUserQueued() {
        Music m = new Music("1", "offlineSong", List.of(), 1000L, "netease", null);
        qm.add(m, new UserSummary("offline-token", null, "offlineUser", false), QueueItemStatus.READY);
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put("1", QueueItemStatus.READY);

        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, false, false, statusMap, Collections.singleton("online-token"));
        assertNotNull(picked, "没有在线用户点歌时，普通随机应回退播放离线用户的歌");
        assertEquals("1", picked.music().id());
    }

    @Test
    void fairShuffleStillPrefersOnlineSongsWhenOnlineUserQueued() {
        Music offline = new Music("1", "offlineSong", List.of(), 1000L, "netease", null);
        Music online = new Music("2", "onlineSong", List.of(), 1000L, "netease", null);
        qm.add(offline, new UserSummary("offline-token", null, "offlineUser", false), QueueItemStatus.READY);
        qm.add(online, new UserSummary("online-token", "s1", "onlineUser", false), QueueItemStatus.READY);
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put("1", QueueItemStatus.READY);
        statusMap.put("2", QueueItemStatus.READY);

        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, true, false, statusMap, Collections.singleton("online-token"));
        assertEquals("2", picked.music().id(), "在线用户有点歌时，公平随机仍应排除离线、优先在线用户的歌");
    }
}

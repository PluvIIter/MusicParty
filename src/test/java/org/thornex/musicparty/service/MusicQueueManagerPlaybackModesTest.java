package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.Priority;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MusicQueueManagerPlaybackModesTest {
    private AppProperties properties;
    private MusicQueueManager queue;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        queue = new MusicQueueManager(properties);
    }

    private MusicQueueItem add(String id, String owner) {
        MusicQueueItem item = queue.add(new Music(id, id, List.of("Artist"), 1000, "netease", null),
                new UserSummary(owner, owner, owner, false), QueueItemStatus.READY);
        assertNotNull(item);
        return item;
    }

    private Map<String, QueueItemStatus> ready() {
        Map<String, QueueItemStatus> statuses = new HashMap<>();
        queue.getQueueSnapshot().forEach(item -> statuses.put(item.music().id(), QueueItemStatus.READY));
        return statuses;
    }

    private MusicQueueItem pick(PlayMode mode, boolean fair) {
        MusicQueueItem item = queue.pollNext(mode, fair, true, ready(), Set.of());
        assertNotNull(item);
        queue.clearPlaybackFailure(item);
        return item;
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SEQUENTIAL", "REPEAT_ONE"})
    void nonRandomModesTakePhysicalFirstWhenAdvancing(PlayMode mode) {
        MusicQueueItem b = add("b", "B");
        MusicQueueItem a = add("a", "A");
        assertEquals(b, pick(mode, true));
        assertEquals(a, pick(mode, false));
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shuffleIgnoresLegacyFairFlagAndDoesNotRotateMembers(boolean fair) {
        properties.getQueue().setRetainPlaylist(true);
        add("heard-a", "A");
        add("fresh-b", "B");
        add("fresh-b2", "B");
        MusicQueueItem heard = pick(PlayMode.SEQUENTIAL, false);
        assertEquals("A", heard.enqueuedBy().token());
        assertEquals("B", pick(PlayMode.SHUFFLE, fair).enqueuedBy().token());
        assertEquals("B", pick(PlayMode.SHUFFLE, fair).enqueuedBy().token());
        assertEquals(3, queue.getQueueSnapshot().size());
    }

    @Test
    void memberOrderedPreferenceDoesNotChangeTotalShuffleFirstPlayProtection() {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem heard = add("heard", "A");
        MusicQueueItem fresh = add("fresh", "A");
        queue.clearPlaybackFailure(heard);
        queue.setMemberShuffle("A", false);
        assertEquals(fresh.queueId(), pick(PlayMode.SHUFFLE, true).queueId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void roundRobinAlwaysRotatesMembersAndDefaultsToIndependentShuffleRounds(boolean fair) {
        properties.getQueue().setRetainPlaylist(true);
        Map<String, Integer> sizes = Map.of("A", 2, "B", 3, "C", 1);
        for (String owner : List.of("A", "B", "C")) {
            assertTrue(queue.isMemberShuffle(owner));
            for (int i = 0; i < sizes.get(owner); i++) add(owner + i, owner);
        }
        Map<String, Set<String>> seen = new HashMap<>();
        Map<String, String> last = new HashMap<>();
        for (int i = 0; i < 180; i++) {
            MusicQueueItem item = pick(PlayMode.ROUND_ROBIN, fair);
            String owner = item.enqueuedBy().token();
            assertEquals(List.of("A", "B", "C").get(i % 3), owner);
            Set<String> round = seen.computeIfAbsent(owner, ignored -> new HashSet<>());
            if (round.size() == sizes.get(owner)) round.clear();
            assertTrue(round.add(item.queueId()));
            if (sizes.get(owner) > 1) assertNotEquals(last.get(owner), item.queueId());
            last.put(owner, item.queueId());
        }
    }

    @Test
    void roundRobinCanMixPhysicalMemberOrderWithRandomMemberRounds() {
        properties.getQueue().setRetainPlaylist(true);
        queue.setMemberShuffle("A", false);
        queue.setMemberShuffle("B", true);
        for (int i = 0; i < 3; i++) {
            add("a" + i, "A");
            add("b" + i, "B");
        }
        for (int round = 0; round < 12; round++) {
            Set<String> bSongs = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                assertEquals("a" + i, pick(PlayMode.ROUND_ROBIN, false).music().id());
                MusicQueueItem b = pick(PlayMode.ROUND_ROBIN, true);
                assertEquals("B", b.enqueuedBy().token());
                assertTrue(bSongs.add(b.music().id()));
            }
        }
    }

    @Test
    void memberOrderUsesCurrentPhysicalFirstAndDoesNotOverrideExplicitPin() {
        properties.getQueue().setRetainPlaylist(true);
        queue.setMemberShuffle("A", false);
        MusicQueueItem first = add("a1", "A");
        MusicQueueItem pinned = add("a2", "A");
        MusicQueueItem reordered = add("a3", "A");
        assertTrue(queue.reorder(reordered.queueId(), first.queueId(), "A"));
        assertEquals(TopResult.PERSONAL, queue.top(pinned.queueId(), PlayMode.ROUND_ROBIN));
        assertEquals(pinned.queueId(), pick(PlayMode.ROUND_ROBIN, false).queueId());
        assertEquals(Priority.REGULAR, queue.getItem(pinned.queueId()).orElseThrow().priority());
        assertEquals(reordered.queueId(), pick(PlayMode.ROUND_ROBIN, true).queueId());
        assertEquals(first.queueId(), pick(PlayMode.ROUND_ROBIN, true).queueId());
    }

    @Test
    void switchingMemberPreferenceKeepsLedgerAndOnlyChangesThatMembersSelection() {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem heard = add("heard", "A");
        MusicQueueItem fresh = add("fresh", "A");
        queue.clearPlaybackFailure(heard);
        queue.setMemberShuffle("A", false);
        assertEquals(heard.queueId(), pick(PlayMode.ROUND_ROBIN, true).queueId());
        assertTrue(queue.reorder(heard.queueId(), fresh.queueId(), "A"));
        queue.setMemberShuffle("A", true);
        assertEquals(Set.of(heard.queueId()), queue.getPlayedQueueIds());
        assertEquals(fresh.queueId(), pick(PlayMode.ROUND_ROBIN, false).queueId());
        assertTrue(queue.isMemberShuffle("B"));
    }

    @ParameterizedTest
    @EnumSource(PlayMode.class)
    void explicitTopHasModeSpecificScopeAndCanBePromoted(PlayMode mode) {
        add("a", "A");
        MusicQueueItem b = add("b", "B");
        TopResult result = queue.top(b.queueId(), mode);
        if (mode == PlayMode.ROUND_ROBIN) {
            assertEquals(TopResult.PERSONAL, result);
            assertEquals("a", pick(mode, false).music().id());
            assertEquals(TopResult.GLOBAL, queue.top(b.queueId(), mode));
        } else {
            assertEquals(TopResult.GLOBAL, result);
        }
        assertEquals(Priority.GLOBAL_TOP, queue.getItem(b.queueId()).orElseThrow().priority());
        assertEquals(b.queueId(), pick(mode, true).queueId());
    }

    @Test
    void personalPinDoesNotTakeAnotherMembersRoundRobinTurn() {
        MusicQueueItem a = add("a", "A");
        MusicQueueItem b = add("b", "B");
        queue.top(b.queueId(), PlayMode.ROUND_ROBIN);
        assertEquals(a, pick(PlayMode.ROUND_ROBIN, false));
        assertEquals(b.queueId(), pick(PlayMode.ROUND_ROBIN, true).queueId());
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void randomModesKeepFirstPlayProtectionAndExistingRoundAfterReorder(PlayMode mode) {
        properties.getQueue().setRetainPlaylist(true);
        for (int i = 0; i < 4; i++) add("old" + i, "A");
        for (int i = 0; i < 4; i++) pick(mode, true);
        MusicQueueItem roundFirst = pick(mode, false);
        assertTrue(queue.reorder(roundFirst.queueId(), queue.getQueueSnapshot().getFirst().queueId(), "A"));
        Set<String> round = new HashSet<>(Set.of(roundFirst.queueId()));
        for (int i = 0; i < 3; i++) assertTrue(round.add(pick(mode, true).queueId()));
        List<MusicQueueItem> newSongs = List.of(add("new1", "A"), add("new2", "A"));
        Set<String> fresh = new HashSet<>();
        for (int i = 0; i < 2; i++) fresh.add(pick(mode, false).queueId());
        assertEquals(new HashSet<>(newSongs.stream().map(MusicQueueItem::queueId).toList()), fresh);
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void randomModesSupportFmOnlineFilteringAndOfflineFallback(PlayMode mode) {
        add("offline", "offline");
        queue.ensureFmMarker();
        MusicQueueItem marker = queue.pollNext(mode, true, false, ready(), Set.of("online"));
        assertNotNull(marker);
        assertEquals(MusicQueueManager.FM_MARKER_ID, marker.music().platform());
        MusicQueueItem fallback = queue.pollNext(mode, false, false, ready(), Set.of("online"));
        assertNotNull(fallback);
        assertEquals("offline", fallback.music().id());
        add("offline2", "offline");
        add("online", "online");
        assertEquals("online", queue.pollNext(mode, true, false, ready(), Set.of("online")).music().id());
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"SHUFFLE", "ROUND_ROBIN"})
    void failedFirstAttemptKeepsFreshLedgerAndPlaylistWhileCoolingDown(PlayMode mode) {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem heard = add("heard", "A");
        MusicQueueItem fresh = add("fresh", "A");
        queue.clearPlaybackFailure(heard);
        MusicQueueItem attempt = queue.pollNext(mode, true, true, ready(), Set.of());
        assertEquals(fresh.queueId(), attempt.queueId());
        queue.markPlaybackFailed(attempt);
        assertEquals(Set.of(heard.queueId()), queue.getPlayedQueueIds());
        assertEquals(heard.queueId(), pick(mode, false).queueId());
        assertEquals(2, queue.getQueueSnapshot().size());
        queue.clearPlaybackFailure(attempt);
        assertEquals(Set.of(heard.queueId(), fresh.queueId()), queue.getPlayedQueueIds());
    }

    @Test
    void retainedTotalShuffleCompletesRoundsWithRegeneratedFmMarker() {
        properties.getQueue().setRetainPlaylist(true);
        for (int i = 0; i < 3; i++) add("a" + i, "A");
        for (int round = 0; round < 12; round++) {
            Set<String> songs = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                queue.ensureFmMarker();
                assertTrue(songs.add(pick(PlayMode.SHUFFLE, true).music().id()));
            }
            assertTrue(songs.contains(MusicQueueManager.FM_MARKER_ID));
        }
        assertEquals(3, queue.getQueueSnapshot().stream()
                .filter(item -> !MusicQueueManager.FM_MARKER_ID.equals(item.music().platform())).count());
    }

    @Test
    void retainedRoundRobinRegeneratesFmWithoutInterruptingMemberTurns() {
        properties.getQueue().setRetainPlaylist(true);
        add("a", "A");
        for (int turn = 0; turn < 12; turn++) {
            queue.ensureFmMarker();
            assertEquals("A", pick(PlayMode.ROUND_ROBIN, false).enqueuedBy().token());
            assertEquals(MusicQueueManager.FM_MARKER_USER_TOKEN,
                    pick(PlayMode.ROUND_ROBIN, true).enqueuedBy().token());
        }
        assertEquals(1, queue.getQueueSnapshot().size());
    }

    @Test
    void totalShufflePicksFmWhileRealSongsAreStillUnheard() {
        // 保留关闭时每轮都清空账本，FM 不能因为“首播优先”被永久饿死
        properties.getQueue().setRetainPlaylist(false);
        int fmPicked = 0;
        for (int run = 0; run < 60; run++) {
            MusicQueueManager manager = new MusicQueueManager(properties);
            manager.add(new Music("s1", "s1", List.of("Artist"), 1000, "netease", null),
                    new UserSummary("A", "A", "A", false), QueueItemStatus.READY);
            manager.ensureFmMarker();
            Map<String, QueueItemStatus> statuses = new HashMap<>();
            manager.getQueueSnapshot().forEach(item -> statuses.put(item.music().id(), QueueItemStatus.READY));
            MusicQueueItem picked = manager.pollNext(PlayMode.SHUFFLE, false, true, statuses, Set.of());
            if (MusicQueueManager.FM_MARKER_ID.equals(picked.music().platform())) fmPicked++;
        }
        assertTrue(fmPicked > 0, "保留关闭时 FM 也应参与随机选曲");
    }

    @Test
    void retainedTotalShufflePicksFmOncePerRoundEvenBeforeAllSongsHeard() {
        properties.getQueue().setRetainPlaylist(true);
        for (int i = 0; i < 3; i++) add("a" + i, "A");
        int fmPicked = 0;
        for (int i = 0; i < 8; i++) {
            queue.ensureFmMarker();
            if (MusicQueueManager.FM_MARKER_ID.equals(pick(PlayMode.SHUFFLE, false).music().platform())) fmPicked++;
        }
        assertEquals(2, fmPicked, "三轮真实歌曲 + FM 共 4 首，8 次播放应含 2 次 FM");
    }

    @ParameterizedTest
    @EnumSource(PlayMode.class)
    void retainedSelectionRotatesStableIdsAndConsumesPinOnce(PlayMode mode) {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem first = add("a", "A");
        MusicQueueItem pinned = add("b", "A");
        queue.top(pinned.queueId(), mode);
        assertEquals(pinned.queueId(), pick(mode, true).queueId());
        assertEquals(List.of(first.queueId(), pinned.queueId()), queue.getQueueSnapshot().stream()
                .map(MusicQueueItem::queueId).toList());
        assertEquals(Priority.REGULAR, queue.getItem(pinned.queueId()).orElseThrow().priority());
    }

    @Test
    void preferencesAreSnapshotsAndClearPendingKeepsMemberChoice() {
        assertTrue(queue.isMemberShuffle("A"));
        queue.setMemberShuffle("A", false);
        Map<String, Boolean> snapshot = queue.getMemberShufflePreferences();
        queue.setMemberShuffle("A", true);
        assertEquals(Map.of("A", false), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("B", false));
        Map<String, Boolean> restored = new HashMap<>(Map.of("A", false, "B", true));
        restored.put(null, false);
        restored.put("missing-value", null);
        restored.put("SYSTEM", false);
        restored.put("__FM__", false);
        queue.restoreMemberShufflePreferences(restored);
        restored.put("A", true);
        assertEquals(Map.of("A", false, "B", true), queue.getMemberShufflePreferences());
        add("a", "A");
        queue.clearPendingQueue();
        assertTrue(queue.getQueueSnapshot().isEmpty());
        assertFalse(queue.isMemberShuffle("A"));
        queue.clearAll();
        assertTrue(queue.getMemberShufflePreferences().isEmpty());
        assertTrue(queue.isMemberShuffle("A"));
    }

    @Test
    void preferencesRestoreReplacesOldValuesWithoutLeakingAcrossQueueRestore() {
        queue.setMemberShuffle("A", false);
        queue.restoreMemberShufflePreferences(Map.of("B", false));
        assertTrue(queue.isMemberShuffle("A"));
        assertFalse(queue.isMemberShuffle("B"));
        queue.restore(List.of(), List.of());
        assertTrue(queue.getMemberShufflePreferences().isEmpty());
        queue.setMemberShuffle("A", false);
        queue.restoreMemberShufflePreferences(null);
        assertTrue(queue.getMemberShufflePreferences().isEmpty());
    }

    @Test
    void claimKeepsTargetPreferenceAndDoesNotTransferSourcePreference() {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem source = add("source-song", "source");
        MusicQueueItem target = add("target-song", "target");
        queue.clearPlaybackFailure(source);
        queue.setMemberShuffle("source", false);
        queue.setMemberShuffle("target", true);
        queue.setMemberShuffle("other", false);
        UserSummary targetUser = target.enqueuedBy();
        MusicQueueItem claimed = new MusicQueueItem(source.queueId(), source.music(), targetUser,
                source.status(), source.priority());
        queue.commitPlaylistClaim(List.of(claimed, target), "source", "target");
        assertEquals(Map.of("target", true, "other", false), queue.getMemberShufflePreferences());
        assertEquals(Set.of(source.queueId()), queue.getPlayedQueueIds());
        queue.setMemberShuffle("source", true);
        queue.setMemberShuffle("target", false);
        queue.commitPlaylistClaim(List.of(claimed, target), "source", "target");
        assertFalse(queue.isMemberShuffle("target"));
        queue.commitPlaylistClaim(List.of(claimed, target), "target", "target");
        assertFalse(queue.isMemberShuffle("target"));
    }

    @Test
    void defaultTargetPreferenceIsNotInheritedFromClaimedOwner() {
        MusicQueueItem source = add("source-song", "source");
        queue.setMemberShuffle("source", false);
        MusicQueueItem claimed = new MusicQueueItem(source.queueId(), source.music(),
                new UserSummary("target", "target", "target", false), source.status(), source.priority());
        queue.commitPlaylistClaim(List.of(claimed), "source", "target");
        assertTrue(queue.isMemberShuffle("target"));
        assertTrue(queue.getMemberShufflePreferences().isEmpty());
    }
}

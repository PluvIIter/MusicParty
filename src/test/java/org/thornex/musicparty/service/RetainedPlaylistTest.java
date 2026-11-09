package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RetainedPlaylistTest {
    private AppProperties props;
    private MusicQueueManager queue;

    @BeforeEach void setUp() {
        props = new AppProperties();
        queue = new MusicQueueManager(props);
    }

    private MusicQueueItem add(String id, String owner) {
        return queue.add(new Music(id, id, List.of("Test"), 1000, "netease", ""),
                new UserSummary(owner, owner, owner, false), QueueItemStatus.READY);
    }

    private Map<String, QueueItemStatus> ready() {
        Map<String, QueueItemStatus> result = new HashMap<>();
        queue.getQueueSnapshot().forEach(item -> result.put(item.music().id(), QueueItemStatus.READY));
        return result;
    }

    private MusicQueueItem pick(boolean fair, Set<String> online) {
        MusicQueueItem item = queue.pollNext(PlayMode.SHUFFLE, fair, false, ready(), online);
        if (item != null) queue.clearPlaybackFailure(item);
        return item;
    }

    @Test void defaultConsumesAndTogglingDoesNotRebuildQueue() {
        assertFalse(props.getQueue().isRetainPlaylist());
        MusicQueueItem a = add("a", "A");
        MusicQueueItem b = add("b", "A");
        assertEquals(a, queue.pollNext(PlayMode.SEQUENTIAL, true, true, ready(), Set.of()));
        assertEquals(List.of(b), queue.getQueueSnapshot());
        props.getQueue().setRetainPlaylist(true);
        assertEquals(b, pick(true, Set.of("A")));
        assertEquals(List.of(b), queue.getQueueSnapshot());
        props.getQueue().setRetainPlaylist(false);
        assertEquals(b, pick(true, Set.of("A")));
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test void sequentialRotatesSameIdsAndConsumesPinOnce() {
        props.getQueue().setRetainPlaylist(true);
        MusicQueueItem a = add("a", "A");
        MusicQueueItem b = add("b", "B");
        queue.top(b.queueId(), PlayMode.SEQUENTIAL);
        assertEquals(b.queueId(), queue.pollNext(PlayMode.SEQUENTIAL, true, true, ready(), Set.of()).queueId());
        assertEquals(List.of(a, b), queue.getQueueSnapshot());
        assertEquals(a.queueId(), queue.pollNext(PlayMode.SEQUENTIAL, true, true, ready(), Set.of()).queueId());
    }

    @Test void unequalMemberPlaylistsHaveIndependentRoundsAndStrictTurns() {
        props.getQueue().setRetainPlaylist(true);
        Map<String, Integer> sizes = Map.of("A", 3, "B", 5, "C", 1);
        for (String owner : List.of("A", "B", "C"))
            for (int i = 0; i < sizes.get(owner); i++) add(owner + i, owner);
        Map<String, Set<String>> seen = new HashMap<>();
        Map<String, String> last = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            MusicQueueItem item = pick(true, sizes.keySet());
            String owner = item.enqueuedBy().token();
            assertEquals(List.of("A", "B", "C").get(i % 3), owner);
            Set<String> round = seen.computeIfAbsent(owner, ignored -> new HashSet<>());
            if (round.size() == sizes.get(owner)) round.clear();
            assertTrue(round.add(item.queueId()), "Repeated before member finished round");
            if (sizes.get(owner) > 1) assertNotEquals(last.get(owner), item.queueId());
            last.put(owner, item.queueId());
        }
        assertEquals(9, queue.getQueueSnapshot().size());
    }

    @Test void totalShuffleCompletesRoundsEvenWhenFmMarkerIsRegenerated() {
        props.getQueue().setRetainPlaylist(true);
        for (int i = 0; i < 4; i++) add("a" + i, "A");
        String last = "";
        for (int round = 0; round < 100; round++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 5; i++) {
                queue.ensureFmMarker();
                MusicQueueItem item = pick(false, Set.of("A"));
                assertTrue(seen.add(item.music().id()));
                assertNotEquals(last, item.music().id());
                last = item.music().id();
            }
        }
        assertEquals(4, queue.getQueueSnapshot().stream().filter(i -> !i.music().id().equals(MusicQueueManager.FM_MARKER_ID)).count());
    }

    @Test void additionsAndOwnerClearPreserveOtherMembersUnfinishedRound() {
        props.getQueue().setRetainPlaylist(true);
        add("a", "A");
        for (int i = 0; i < 3; i++) add("b" + i, "B");
        pick(true, Set.of("A", "B"));
        MusicQueueItem heard = pick(true, Set.of("A", "B"));
        add("new", "B");
        assertEquals(1, queue.removeByUser("A"));
        Set<String> seen = new HashSet<>(Set.of(heard.queueId()));
        for (int i = 0; i < 3; i++) assertTrue(seen.add(pick(true, Set.of("B")).queueId()));
        assertEquals(0, queue.removeByUser("A"));
    }

    @Test void offlineReturnAndExplicitPinRespectRemainingRound() {
        props.getQueue().setRetainPlaylist(true);
        for (int i = 0; i < 3; i++) add("a" + i, "A");
        add("b", "B");
        MusicQueueItem heard = pick(true, Set.of("A", "B"));
        for (int i = 0; i < 4; i++) assertEquals("B", pick(true, Set.of("B")).enqueuedBy().token());
        assertNotEquals(heard.queueId(), pick(true, Set.of("A", "B")).queueId());
        queue.top(heard.queueId(), PlayMode.SHUFFLE);
        pick(true, Set.of("A", "B")); // B's turn
        assertEquals(heard.queueId(), pick(true, Set.of("A", "B")).queueId());
        assertEquals(Priority.REGULAR, queue.getItem(heard.queueId()).orElseThrow().priority());
    }

    @Test void failedRetainedTracksCoolDownWithoutDiscardingPlaylist() {
        props.getQueue().setRetainPlaylist(true);
        MusicQueueItem a = add("a", "A");
        queue.markPlaybackFailed(a);
        assertNull(pick(true, Set.of("A")));
        assertFalse(queue.hasPlayableItems(ready()));
        assertEquals(List.of(a), queue.getQueueSnapshot());
        queue.clearPlaybackFailure(a);
        assertEquals(a, pick(true, Set.of("A")));
        queue.markPlaybackFailed(a);
        props.getQueue().setRetainPlaylist(false);
        assertEquals(a, pick(true, Set.of("A")), "Disabling retention restores normal consumption");
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test void neverPlayedAdditionsWinEvenAfterOlderSongsStartAnotherRound() {
        props.getQueue().setRetainPlaylist(true);
        for (boolean fair : List.of(true, false)) for (int trial = 0; trial < 500; trial++) {
            queue.clearAll();
            for (int i = 0; i < 4; i++) add("old" + i, "A");
            for (int i = 0; i < 5; i++) pick(fair, Set.of("A"));
            add("new-a", "A"); add("new-b", "A");
            Set<String> fresh = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                MusicQueueItem item = pick(fair, Set.of("A"));
                assertTrue(item.music().id().startsWith("new-"));
                assertTrue(fresh.add(item.queueId()));
            }
        }
    }

    @Test void firstPlayPriorityDoesNotStealAnotherMembersTurn() {
        props.getQueue().setRetainPlaylist(true);
        add("old-a", "A"); add("old-b", "B");
        pick(true, Set.of("A", "B")); pick(true, Set.of("A", "B"));
        add("new-a", "A"); add("new-a2", "A");
        assertTrue(pick(true, Set.of("A", "B")).music().id().startsWith("new-"));
        assertEquals("B", pick(true, Set.of("A", "B")).enqueuedBy().token());
        assertTrue(pick(true, Set.of("A", "B")).music().id().startsWith("new-"));
    }

    @Test void failedAttemptRemainsUnheardAndDeletedEntriesDoNotMarkReadditions() {
        props.getQueue().setRetainPlaylist(true);
        MusicQueueItem old = add("old", "A"); pick(true, Set.of("A"));
        MusicQueueItem fresh = add("new", "A");
        MusicQueueItem attempted = queue.pollNext(PlayMode.SHUFFLE, true, false, ready(), Set.of("A"));
        assertEquals(fresh.queueId(), attempted.queueId());
        queue.markPlaybackFailed(attempted);
        assertFalse(queue.getPlayedQueueIds().contains(fresh.queueId()));
        assertEquals(old.queueId(), pick(true, Set.of("A")).queueId());
        queue.clearPlaybackFailure(fresh); // A later successful load ends the music-level cooldown.
        queue.remove(fresh.queueId());
        MusicQueueItem readded = add("new", "A");
        assertNotEquals(fresh.queueId(), readded.queueId());
        assertEquals(readded.queueId(), pick(true, Set.of("A")).queueId());
    }

    @Test void restoredLedgerWinsOverOldSongHistoryAndPrunesDeletedIds() {
        props.getQueue().setRetainPlaylist(true);
        MusicQueueItem old = add("old", "A");
        MusicQueueItem fresh = add("new", "A");
        queue.restore(queue.getQueueSnapshot(), List.of(old.music(), fresh.music()));
        queue.restorePlayedQueueIds(Set.of(old.queueId(), "deleted-id"));
        assertEquals(Set.of(old.queueId()), queue.getPlayedQueueIds());
        assertEquals(fresh.queueId(), pick(true, Set.of("A")).queueId());
        queue.restore(queue.getQueueSnapshot(), List.of(old.music()));
        queue.restorePlayedQueueIds(null);
        assertEquals(Set.of(old.queueId()), queue.getPlayedQueueIds());
    }
}

package org.thornex.musicparty.service;

import org.thornex.musicparty.source.ResourceKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.Priority;
import org.thornex.musicparty.enums.QueueItemStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MusicQueueManagerReorderTest {
    private AppProperties properties;
    private MusicQueueManager queue;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        queue = new MusicQueueManager(properties);
    }

    private MusicQueueItem add(String id, String owner) {
        return queue.add(new Music(id, id, List.of("Artist"), 1000, "netease", null, null),
                new UserSummary(owner, owner, owner, false), QueueItemStatus.READY);
    }

    private List<String> ids() {
        return queue.getQueueSnapshot().stream().map(MusicQueueItem::queueId).toList();
    }

    private Map<String, QueueItemStatus> ready() {
        Map<String, QueueItemStatus> statuses = new HashMap<>();
        queue.getQueueSnapshot().forEach(item -> statuses.put(ResourceKey.encode(item.music().platform(), item.music().id()), QueueItemStatus.READY));
        return statuses;
    }

    @Test
    void movesOwnedEntryBeforeTargetOrToEndWithoutChangingOtherRelativeOrder() {
        MusicQueueItem a1 = add("a1", "A");
        MusicQueueItem b = add("b", "B");
        MusicQueueItem a2 = add("a2", "A");
        MusicQueueItem c = add("c", "C");
        MusicQueueItem a3 = add("a3", "A");
        assertFalse(queue.reorder(a3.queueId(), b.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertTrue(queue.reorder(a3.queueId(), a2.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(a1.queueId(), b.queueId(), a3.queueId(), c.queueId(), a2.queueId()), ids());
        assertTrue(queue.reorder(a1.queueId(), null, "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(a3.queueId(), b.queueId(), a2.queueId(), c.queueId(), a1.queueId()), ids());
        assertEquals(List.of(b, c), queue.getQueueSnapshot().stream()
                .filter(item -> !item.enqueuedBy().token().equals("A")).toList());
        assertEquals(a3, queue.getItem(a3.queueId()).orElseThrow());
        assertTrue(queue.reorder(a3.queueId(), a3.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(a3.queueId(), b.queueId(), a2.queueId(), c.queueId(), a1.queueId()), ids());
    }

    @Test
    void unauthorizedMissingAndStaleTargetsLeaveQueueUntouched() {
        MusicQueueItem a = add("a", "A");
        MusicQueueItem b = add("b", "B");
        List<MusicQueueItem> before = queue.getQueueSnapshot();
        assertFalse(queue.reorder(b.queueId(), a.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(a.queueId(), null, "B", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder("missing", b.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(a.queueId(), "missing", "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(null, b.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        for (String actor : List.of("", " ", "SYSTEM", "ADMIN", "__FM__")) {
            assertFalse(queue.reorder(a.queueId(), b.queueId(), actor, org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        }
        assertFalse(queue.reorder(a.queueId(), null, null, org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(before, queue.getQueueSnapshot());
        queue.remove(b.queueId());
        assertFalse(queue.reorder(a.queueId(), b.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(a), queue.getQueueSnapshot());
    }

    @ParameterizedTest
    @EnumSource(value = Priority.class, names = {"USER_TOP", "GLOBAL_TOP"})
    void pinnedEntriesCannotBeMovedEvenByOwner(Priority priority) {
        MusicQueueItem a = add("a", "A");
        MusicQueueItem pin = add("pinned", "A").withPriority(priority);
        queue.restore(List.of(a, pin), List.of());
        assertFalse(queue.reorder(pin.queueId(), a.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(pin.queueId(), null, "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(pin.queueId(), pin.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(a, pin), queue.getQueueSnapshot());
    }

    @Test
    void fmAndReservedSystemEntriesCannotBeMoved() {
        MusicQueueItem a = add("a", "A");
        MusicQueueItem system = add("system", "SYSTEM");
        MusicQueueItem admin = add("admin", "ADMIN");
        queue.ensureFmMarker();
        MusicQueueItem fm = queue.getQueueSnapshot().getLast();
        List<MusicQueueItem> before = queue.getQueueSnapshot();
        assertFalse(queue.reorder(fm.queueId(), a.queueId(), MusicQueueManager.FM_MARKER_USER_TOKEN, org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(system.queueId(), a.queueId(), "SYSTEM", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(admin.queueId(), a.queueId(), "ADMIN", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(before, queue.getQueueSnapshot());
        MusicQueueItem ownedFm = new MusicQueueItem("owned-fm", fm.music(), a.enqueuedBy(),
                QueueItemStatus.READY, Priority.REGULAR);
        queue.restore(List.of(a, ownedFm), List.of());
        assertFalse(queue.reorder(ownedFm.queueId(), a.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(a, ownedFm), queue.getQueueSnapshot());
    }

    @Test
    void movingRegularEntriesDoesNotChangePinPriorityOrOtherOwnersOrder() {
        MusicQueueItem a1 = add("a1", "A");
        MusicQueueItem pin = add("pin", "B");
        MusicQueueItem a2 = add("a2", "A");
        queue.top(pin.queueId(), PlayMode.SHUFFLE);
        assertFalse(queue.reorder(a2.queueId(), pin.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertTrue(queue.reorder(a2.queueId(), a1.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(List.of(pin.queueId(), a2.queueId(), a1.queueId()), ids());
        assertEquals(Priority.GLOBAL_TOP, queue.getItem(pin.queueId()).orElseThrow().priority());
        assertEquals(pin.queueId(), queue.pollNext(PlayMode.SEQUENTIAL, true, ready(), Set.of()).queueId());
    }

    @Test
    void reorderKeepsHeardLedgerLastUserRetryCooldownAndPreferences() {
        properties.getQueue().setRetainPlaylist(true);
        queue.setMemberShuffle("A", false);
        MusicQueueItem a1 = add("a1", "A");
        MusicQueueItem a2 = add("a2", "A");
        MusicQueueItem b = add("b", "B");
        MusicQueueItem first = queue.pollNext(PlayMode.ROUND_ROBIN, true, ready(), Set.of());
        assertEquals(a1, first);
        queue.clearPlaybackFailure(first);
        queue.markPlaybackFailed(a2);
        Set<String> heard = queue.getPlayedQueueIds();
        assertTrue(queue.reorder(a1.queueId(), a2.queueId(), "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(heard, queue.getPlayedQueueIds());
        assertEquals(Map.of("A", false), queue.getMemberShufflePreferences());
        MusicQueueItem next = queue.pollNext(PlayMode.ROUND_ROBIN, true, ready(), Set.of());
        assertEquals(b.queueId(), next.queueId());
        queue.clearPlaybackFailure(next);
        assertEquals(a1.queueId(), queue.pollNext(PlayMode.ROUND_ROBIN, true, ready(), Set.of()).queueId());
        assertFalse(queue.getPlayedQueueIds().contains(a2.queueId()));
    }

    @Test
    void sequentialMovesAnyMembersSongGloballyAndSelectsTheNewHead() {
        var a1 = add("a1", "A"); var b = add("b", "B"); var a2 = add("a2", "A"); var c = add("c", "C");
        assertTrue(queue.reorder(c.queueId(), a1.queueId(), "A", PlayMode.SEQUENTIAL));
        assertEquals(List.of(c.queueId(), a1.queueId(), b.queueId(), a2.queueId()), ids());
        assertEquals(c.queueId(), queue.pollNext(PlayMode.SEQUENTIAL, true, ready(), Set.of()).queueId());
        assertTrue(queue.reorder(a1.queueId(), null, "C", PlayMode.SEQUENTIAL));
        assertEquals(List.of(b.queueId(), a2.queueId(), a1.queueId()), ids());
    }

    @Test
    void globalReorderPreservesPinFmSystemSlotsAndHeardLedger() {
        var a = add("a", "A"); var b = add("b", "B"); var system = add("system", "SYSTEM");
        var pin = add("pin", "C").withPriority(Priority.GLOBAL_TOP);
        queue.restore(List.of(a, pin, b, system), List.of());
        queue.ensureFmMarker();
        var fm = queue.getQueueSnapshot().getLast();
        queue.restorePlayedQueueIds(Set.of(a.queueId()));
        assertTrue(queue.reorder(b.queueId(), a.queueId(), "C", PlayMode.SEQUENTIAL));
        assertEquals(List.of(b, pin, a, system, fm), queue.getQueueSnapshot());
        assertEquals(Set.of(a.queueId()), queue.getPlayedQueueIds());
        for (var fixed : List.of(pin, system, fm)) {
            assertFalse(queue.reorder(fixed.queueId(), a.queueId(), "A", PlayMode.SEQUENTIAL));
            assertFalse(queue.reorder(a.queueId(), fixed.queueId(), "A", PlayMode.SEQUENTIAL));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PlayMode.class, names = {"ROUND_ROBIN", "SHUFFLE", "REPEAT_ONE"})
    void nonSequentialCannotReorderForeignEntries(PlayMode mode) {
        var a1 = add("a1", "A"); var b = add("b", "B"); var a2 = add("a2", "A");
        assertFalse(queue.reorder(b.queueId(), a1.queueId(), "A", mode));
        assertFalse(queue.reorder(a2.queueId(), b.queueId(), "A", mode));
        assertTrue(queue.reorder(a2.queueId(), a1.queueId(), "A", mode));
        assertEquals(List.of(a2, b, a1), queue.getQueueSnapshot());
    }

    @Test
    void failedReorderDoesNotResetPlaybackLedgerOrConsumePins() {
        properties.getQueue().setRetainPlaylist(true);
        MusicQueueItem heard = add("heard", "A");
        MusicQueueItem pinned = add("pin", "B");
        queue.clearPlaybackFailure(heard);
        queue.top(pinned.queueId(), PlayMode.ROUND_ROBIN);
        List<MusicQueueItem> before = queue.getQueueSnapshot();
        assertFalse(queue.reorder(heard.queueId(), "stale-id", "A", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertFalse(queue.reorder(pinned.queueId(), heard.queueId(), "B", org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN));
        assertEquals(before, queue.getQueueSnapshot());
        assertEquals(Set.of(heard.queueId()), queue.getPlayedQueueIds());
        assertEquals(Priority.USER_TOP, queue.getItem(pinned.queueId()).orElseThrow().priority());
    }
}

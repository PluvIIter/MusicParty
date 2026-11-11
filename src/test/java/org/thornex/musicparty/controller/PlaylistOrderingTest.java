package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaylistOrderingTest {
    final UserService users = mock(UserService.class);
    final MusicQueueManager queue = new MusicQueueManager(new AppProperties());
    final MusicPlayerService player = mock(MusicPlayerService.class);
    final QueuePersistenceService persistence = mock(QueuePersistenceService.class);
    final SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
    final PlaylistController controller = new PlaylistController(users, queue, player, persistence, messages);
    @org.junit.jupiter.api.BeforeEach void mode() {
        when(player.getPlayMode()).thenReturn(org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN);
        clearInvocations(player);
    }
    MusicQueueItem add(String id, String owner) {
        return queue.add(new Music(id, id, List.of(), 1000, "netease", ""),
                new UserSummary(owner, owner, owner, false), QueueItemStatus.READY);
    }
    void member() { when(users.getUser("s")).thenReturn(Optional.of(new User("A", "s", "Alice"))); }

    @Test void orderingUsesSessionOwnerAndPreservesOtherSlots() throws Exception {
        member();
        var a = add("a", "A"); var b = add("b", "B"); var c = add("c", "A");
        queue.restorePlayedQueueIds(Set.of(a.queueId()));
        controller.reorder(new PlaylistController.ReorderRequest(c.queueId(), a.queueId(), "ROUND_ROBIN"), "s");
        assertEquals(List.of(c, b, a), queue.getQueueSnapshot());
        assertEquals(Set.of(a.queueId()), queue.getPlayedQueueIds());
        verify(persistence).saveQueueSnapshot(List.of(c, b, a));
        verify(player).broadcastQueueUpdate();
        verify(player).getPlayMode();
        verifyNoMoreInteractions(player);
    }
    @Test void rejectedStaleForeignAndUnauthenticatedRequestsDoNotSave() {
        member();
        var a = add("a", "A"); var b = add("b", "B");
        when(users.getUser("guest")).thenReturn(Optional.of(new User("g", "guest", "游客")));
        controller.reorder(new PlaylistController.ReorderRequest(b.queueId(), a.queueId(), "ROUND_ROBIN"), "s");
        controller.reorder(new PlaylistController.ReorderRequest(a.queueId(), "stale", "ROUND_ROBIN"), "s");
        controller.reorder(new PlaylistController.ReorderRequest(a.queueId(), null, "ROUND_ROBIN"), "guest");
        controller.reorder(new PlaylistController.ReorderRequest(a.queueId(), null, "ROUND_ROBIN"), "gone");
        controller.reorder(null, "s");
        assertEquals(List.of(a, b), queue.getQueueSnapshot());
        verifyNoInteractions(persistence);
        verify(player, never()).broadcastQueueUpdate();
    }
    @Test void diskFailureRollsBackOrderWithoutErasingLedgerAndReportsFailure() throws Exception {
        member();
        var a = add("a", "A"); var b = add("b", "B"); var c = add("c", "A");
        queue.restorePlayedQueueIds(Set.of(a.queueId()));
        queue.setMemberShuffle("A", false);
        doThrow(new IOException("disk full")).when(persistence).saveQueueSnapshot(anyList());
        controller.reorder(new PlaylistController.ReorderRequest(c.queueId(), a.queueId(), "ROUND_ROBIN"), "s");
        assertEquals(List.of(a, b, c), queue.getQueueSnapshot());
        assertEquals(Set.of(a.queueId()), queue.getPlayedQueueIds());
        assertFalse(queue.isMemberShuffle("A"));
        verify(player, never()).broadcastQueueUpdate();
        verify(messages).convertAndSendToUser(eq("s"), eq("/queue/events"), argThat((Object event) ->
                event instanceof PlayerEvent e && e.type().equals("ERROR") && e.message().contains("原顺序")), anyMap());
    }
    @Test void sequentialAllowsMovingAnotherMembersSongAcrossOwners() throws Exception {
        member();
        when(player.getPlayMode()).thenReturn(org.thornex.musicparty.enums.PlayMode.SEQUENTIAL);
        var a = add("a", "A"); var b = add("b", "B"); var c = add("c", "C");
        controller.reorder(new PlaylistController.ReorderRequest(c.queueId(), a.queueId(), "SEQUENTIAL"), "s");
        assertEquals(List.of(c, a, b), queue.getQueueSnapshot());
        verify(persistence).saveQueueSnapshot(List.of(c, a, b));
        verify(player).broadcastQueueUpdate();
    }
    @Test void staleOrMissingModeCannotAcquireGlobalSortingPermission() {
        member();
        var a = add("a", "A"); var b = add("b", "B");
        controller.reorder(new PlaylistController.ReorderRequest(b.queueId(), a.queueId(), "SEQUENTIAL"), "s");
        controller.reorder(new PlaylistController.ReorderRequest(b.queueId(), a.queueId()), "s");
        when(player.getPlayMode()).thenReturn(org.thornex.musicparty.enums.PlayMode.SEQUENTIAL);
        controller.reorder(new PlaylistController.ReorderRequest(b.queueId(), a.queueId(), "ROUND_ROBIN"), "s");
        assertEquals(List.of(a, b), queue.getQueueSnapshot());
        verifyNoInteractions(persistence);
        verify(player, never()).broadcastQueueUpdate();
    }
    @Test void queuedSequentialDragIsRejectedAfterModeChangesBeforeItAcquiresPlayerLock() throws Exception {
        member();
        when(player.getPlayMode()).thenReturn(org.thornex.musicparty.enums.PlayMode.SEQUENTIAL);
        var a = add("a", "A"); var b = add("b", "B");
        var started = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> reorder;
            synchronized (player) {
                reorder = executor.submit(() -> {
                    started.countDown();
                    controller.reorder(new PlaylistController.ReorderRequest(b.queueId(), a.queueId(), "SEQUENTIAL"), "s");
                });
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> reorder.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
                when(player.getPlayMode()).thenReturn(org.thornex.musicparty.enums.PlayMode.ROUND_ROBIN);
            }
            reorder.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of(a, b), queue.getQueueSnapshot());
            verifyNoInteractions(persistence);
            verify(player, never()).broadcastQueueUpdate();
        } finally { executor.shutdownNow(); }
    }
    @Test void globalSortingFailureRestoresOtherMembersOrderToo() throws Exception {
        member();
        when(player.getPlayMode()).thenReturn(org.thornex.musicparty.enums.PlayMode.SEQUENTIAL);
        var a = add("a", "A"); var b = add("b", "B"); var c = add("c", "C");
        doThrow(new IOException("full")).when(persistence).saveQueueSnapshot(anyList());
        controller.reorder(new PlaylistController.ReorderRequest(c.queueId(), a.queueId(), "SEQUENTIAL"), "s");
        assertEquals(List.of(a, b, c), queue.getQueueSnapshot());
        verify(player, never()).broadcastQueueUpdate();
    }
    @Test void preferenceOnlyChangesSessionMemberAndPersistsBeforeBroadcast() throws Exception {
        member();
        queue.setMemberShuffle("B", false);
        controller.memberShuffle(new PlaylistController.MemberShuffleRequest(false), "s");
        assertEquals(Map.of("A", false, "B", false), queue.getMemberShufflePreferences());
        verify(persistence).saveQueueSnapshot(List.of());
        verify(player).broadcastFullPlayerState();
        controller.memberShuffle(new PlaylistController.MemberShuffleRequest(true), "gone");
        controller.memberShuffle(new PlaylistController.MemberShuffleRequest(null), "s");
        assertFalse(queue.isMemberShuffle("A"));
    }
    @Test void preferenceSaveFailureRestoresEntirePreviousMap() throws Exception {
        member();
        queue.setMemberShuffle("B", false);
        doThrow(new IOException("full")).when(persistence).saveQueueSnapshot(anyList());
        controller.memberShuffle(new PlaylistController.MemberShuffleRequest(false), "s");
        assertEquals(Map.of("B", false), queue.getMemberShufflePreferences());
        assertTrue(queue.isMemberShuffle("A"));
        verifyNoInteractions(player);
        verify(messages).convertAndSendToUser(eq("s"), eq("/queue/events"), any(PlayerEvent.class), anyMap());
    }
}

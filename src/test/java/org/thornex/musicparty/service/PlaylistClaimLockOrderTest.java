package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AdminController;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaylistClaimLockOrderTest {
    @TempDir Path directory;
    private AppProperties properties;
    private UserService users;
    private MusicQueueManager queue;
    private MusicPlayerService player;
    private QueuePersistenceService persistence;
    private PlaylistClaimService claims;
    private AdminController admin;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.setAdminPassword("fixture-admin");
        properties.getQueue().setAllowPlaylistClaim(true);
        properties.getQueue().setRetainPlaylist(true);
        properties.getQueue().setPersistenceFile(directory.resolve("queue.json").toString());
        var events = mock(ApplicationEventPublisher.class);
        users = new UserService(events);
        users.handleConnect("target-session", "target", "Target");
        var source = users.handleConnect("source-session", "source", "Original");
        source.setSessionId(null);
        source.setLastActiveTime(0);
        queue = new MusicQueueManager(properties);
        queue.restore(List.of(new MusicQueueItem("stable-id",
                new Music("track", "Track", List.of("Fixture"), 180000, "netease", ""),
                new UserSummary("source", "source-session", "Original", false), QueueItemStatus.READY)), List.of());
        var cache = mock(LocalCacheService.class);
        var stream = mock(LiveStreamService.class);
        var netease = mock(NeteaseMusicApiService.class);
        when(netease.getPlatformName()).thenReturn("netease");
        when(netease.getPlayableMusic("track")).thenReturn(Mono.just(new PlayableMusic("track", "Track",
                List.of("Fixture"), 180000, "netease", "fixture://audio", "", false)));
        var dj = mock(PrivateDjService.class);
        player = new MusicPlayerService(List.of(netease), users, cache, stream, queue, events, properties, netease, dj);
        var chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        var auth = mock(AuthController.class);
        persistence = new QueuePersistenceService(queue, chat, properties, new ObjectMapper(), player, auth, stream);
        claims = new PlaylistClaimService(users, queue, player, persistence, properties);
        admin = new AdminController(player, chat, properties, auth, netease, mock(BilibiliMusicApiService.class), stream, dj, persistence);
    }

    @AfterEach
    void stopScheduler() {
        ((ScheduledExecutorService) ReflectionTestUtils.getField(users, "scheduler")).shutdownNow();
    }

    @Test
    void claimWaitingForPersistenceDoesNotHoldPlayerAndAdminCanSkip() throws Exception {
        @SuppressWarnings("unchecked")
        var currentMusic = (AtomicReference<PlayableMusic>) ReflectionTestUtils.getField(player, "currentMusic");
        currentMusic.set(new PlayableMusic("track", "Track", List.of("Fixture"), 180000,
                "netease", "fixture://audio", "", false));
        var result = new CompletableFuture<PlaylistClaimService.Claimed>();
        Thread claimant = new Thread(() -> {
            try { result.complete(claims.claim("target-session", "source")); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }, "playlist-claim-lock-regression");
        claimant.setDaemon(true);
        try {
            synchronized (persistence) {
                claimant.start();
                ThreadInfo blocked = awaitBlockedOn(claimant, persistence);
                assertTrue(owns(blocked, users));
                assertFalse(owns(blocked, player), "claim must acquire persistence before player");
                assertFalse(owns(blocked, queue), "claim must acquire persistence before queue");
                var request = new AdminConfigUpdateRequest(null, null, null, null, null, null,
                        null, null, null, null, true, 0.0, null, null, null);
                assertEquals(200, admin.updateConfig("fixture-admin", request).getStatusCode().value());
                assertTrue(((AtomicLong) ReflectionTestUtils.getField(player, "playHeadVersion")).get() > 0,
                        "config must exercise the synchronized player skip path");
                assertFalse(result.isDone());
            }
            assertEquals(1, result.get(5, TimeUnit.SECONDS).claimed());
            assertEquals("target", queue.getQueueSnapshot().getFirst().enqueuedBy().token());
        } finally {
            claimant.join(5000);
        }
    }

    @Test
    void actualClaimRoundTripsHeardLedgerWithoutChangingQueueIds() throws Exception {
        var original = queue.getQueueSnapshot().getFirst();
        queue.clearPlaybackFailure(original);

        claims.claim("target-session", "source");

        var restored = new MusicQueueManager(properties);
        var reloader = new QueuePersistenceService(restored, mock(ChatService.class), properties,
                new ObjectMapper(), player, mock(AuthController.class), mock(LiveStreamService.class));
        reloader.loadData();
        assertEquals(queue.getQueueSnapshot(), restored.getQueueSnapshot());
        assertEquals(original.queueId(), restored.getQueueSnapshot().getFirst().queueId());
        assertEquals(java.util.Set.of(original.queueId()), restored.getPlayedQueueIds());
        assertTrue(restored.getHistorySnapshot().isEmpty());
    }

    @Test
    void proposedSnapshotWaitsForQueueBeforeReadingFirstPlayLedger() throws Exception {
        var proposed = queue.getQueueSnapshot();
        var result = new CompletableFuture<Void>();
        Thread saver = new Thread(() -> {
            try {
                persistence.saveQueueSnapshot(proposed);
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        }, "proposed-snapshot-lock-regression");
        saver.setDaemon(true);
        try {
            synchronized (queue) {
                saver.start();
                ThreadInfo blocked = awaitBlockedOn(saver, queue);
                assertTrue(owns(blocked, persistence));
                assertFalse(owns(blocked, player));
                queue.clearPlaybackFailure(proposed.getFirst());
            }
            result.get(5, TimeUnit.SECONDS);
            var root = new ObjectMapper().readTree(directory.resolve("queue.json").toFile());
            assertEquals("stable-id", root.path("playedQueueIds").get(0).asText());
        } finally {
            saver.join(5000);
        }
    }

    private static ThreadInfo awaitBlockedOn(Thread thread, Object monitor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(new long[]{thread.getId()}, true, true)[0];
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) return info;
            Thread.yield();
        }
        throw new AssertionError(thread.getName() + " did not block on the expected monitor");
    }

    private static boolean owns(ThreadInfo info, Object monitor) {
        return java.util.Arrays.stream(info.getLockedMonitors())
                .anyMatch(lock -> lock.getIdentityHashCode() == System.identityHashCode(monitor));
    }
}

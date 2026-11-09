package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceAlbumTest {
    private AppProperties properties;
    private MusicQueueManager queue;
    private NeteaseMusicApiService api;
    private UserService users;
    private MusicPlayerService player;
    private User member;
    private AlbumInfo album;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        queue = new MusicQueueManager(properties);
        api = mock(NeteaseMusicApiService.class);
        when(api.getPlatformName()).thenReturn("netease");
        users = mock(UserService.class);
        member = new User("member", "session", "Alice");
        when(users.getUser("session")).thenReturn(Optional.of(member));
        album = new AlbumInfo("900", "Album", List.of("Artist"), null, 3,
                List.of(music("1"), music("2"), music("3")));
        when(api.getAlbum("900")).thenReturn(Mono.just(album));
        player = new MusicPlayerService(List.of(api), users, mock(LocalCacheService.class),
                mock(LiveStreamService.class), queue, mock(ApplicationEventPublisher.class),
                properties, api, mock(PrivateDjService.class));
    }

    private Music music(String id) {
        return new Music(id, "Song " + id, List.of("Artist"), 180_000L, "netease", null);
    }

    private AlbumImportResult enqueue() {
        return player.enqueueAlbum(new EnqueueAlbumRequest("netease", "900", "request"), "session").block();
    }

    @Test
    void addsInAlbumOrderWithMemberOwnershipAndCorrelatedResult() {
        AlbumImportResult result = enqueue();
        assertNotNull(result);
        assertFalse(result.error());
        assertEquals("request", result.requestId());
        assertEquals(3, result.added());
        assertEquals(List.of("1", "2", "3"), queue.getQueueSnapshot().stream().map(item -> item.music().id()).toList());
        assertTrue(queue.getQueueSnapshot().stream().allMatch(item -> item.enqueuedBy().token().equals("member")));
        verify(api, never()).getPlayableMusic(anyString());
    }

    @Test
    void repeatedImportSkipsExistingSongs() {
        enqueue();
        AlbumImportResult repeated = enqueue();
        assertEquals(0, repeated.added());
        assertEquals(3, repeated.duplicates());
        assertEquals(3, queue.getQueueSnapshot().size());
    }

    @Test
    void respectsPerImportLimit() {
        properties.getPlayer().setMaxPlaylistImportSize(1);
        AlbumImportResult result = enqueue();
        assertEquals(1, result.added());
        assertEquals(2, result.limited());
    }

    @Test
    void respectsRemainingMemberQuota() {
        properties.getQueue().setMaxUserSongs(2);
        queue.add(music("existing"), new UserSummary("member", "session", "Alice", false), QueueItemStatus.READY);
        AlbumImportResult result = enqueue();
        assertEquals(1, result.added());
        assertEquals(2, result.limited());
        assertEquals(2, queue.getQueueSnapshot().size());
    }

    @Test
    void respectsRoomCapacity() {
        properties.getQueue().setMaxSize(2);
        AlbumImportResult result = enqueue();
        assertEquals(2, result.added());
        assertEquals(1, result.limited());
    }

    @Test
    void concurrentAlbumImportsRespectMemberQuota() throws Exception {
        properties.getQueue().setMaxUserSongs(2);
        CountDownLatch fetched = new CountDownLatch(2);
        when(api.getAlbum("900")).thenReturn(Mono.fromCallable(() -> {
            fetched.countDown();
            assertTrue(fetched.await(5, TimeUnit.SECONDS));
            return album;
        }));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(this::enqueue);
            var second = executor.submit(this::enqueue);
            assertEquals(2, first.get(5, TimeUnit.SECONDS).added() + second.get(5, TimeUnit.SECONDS).added());
            assertEquals(2, queue.getQueueSnapshot().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsGuestsAndDisabledSourceBeforeFetching() {
        member.setGuest(true);
        assertTrue(enqueue().error());
        member.setGuest(false);
        properties.getNetease().setEnabled(false);
        assertTrue(enqueue().error());
        verify(api, never()).getAlbum(anyString());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test
    void disconnectWhileFetchingDoesNotEnqueue() {
        Sinks.One<AlbumInfo> response = Sinks.one();
        when(api.getAlbum("900")).thenReturn(response.asMono());
        AtomicReference<AlbumImportResult> result = new AtomicReference<>();
        player.enqueueAlbum(new EnqueueAlbumRequest("netease", "900", "request"), "session").subscribe(result::set);
        when(users.getUser("session")).thenReturn(Optional.empty());
        response.tryEmitValue(album);
        assertTrue(result.get().error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test
    void sourceDisabledWhileFetchingDoesNotEnqueue() {
        Sinks.One<AlbumInfo> response = Sinks.one();
        when(api.getAlbum("900")).thenReturn(response.asMono());
        AtomicReference<AlbumImportResult> result = new AtomicReference<>();
        player.enqueueAlbum(new EnqueueAlbumRequest("netease", "900", "request"), "session").subscribe(result::set);
        properties.getNetease().setEnabled(false);
        response.tryEmitValue(album);
        assertTrue(result.get().error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test
    void upstreamFailureReturnsCorrelatedFailureWithoutChangingQueue() {
        when(api.getAlbum("900")).thenReturn(Mono.error(new IllegalStateException("private-details")));
        AlbumImportResult result = enqueue();
        assertTrue(result.error());
        assertEquals("request", result.requestId());
        assertFalse(result.message().contains("private-details"));
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }
}

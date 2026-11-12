package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.resource.ContainerResource;
import org.thornex.musicparty.resource.ResourceService;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerImportServiceTest {
    private ResourceService resources;
    private SourceRegistry sources;
    private MediaDeliveryService media;
    private UserService users;
    private MusicQueueManager queue;
    private ContainerImportService imports;
    private final ContainerImportService.Request request = new ContainerImportService.Request("third", "favorites", "opaque:id", "r1");
    private final Music track = new Music("same", "Track", List.of(), 1000, "third", null, null);

    @BeforeEach
    void setUp() {
        var properties = new AppProperties();
        properties.getQueue().setMaxUserSongs(1);
        resources = mock(ResourceService.class);
        sources = mock(SourceRegistry.class);
        media = mock(MediaDeliveryService.class);
        users = mock(UserService.class);
        queue = new MusicQueueManager(properties);
        var player = mock(MusicPlayerService.class);
        imports = new ContainerImportService(resources, sources, media, users, queue, player, properties);
        when(users.getUser("session")).thenReturn(Optional.of(new User("member", "session", "Listener")));
        when(resources.container("third", "favorites", "opaque:id")).thenReturn(Mono.just(new ContainerResource("opaque:id", "Favorites", "third", "favorites", null, 2)));
        when(resources.importTracks(eq("third"), eq("favorites"), eq("opaque:id"), anyInt()))
                .thenReturn(Mono.just(List.of(track, new Music("2", "Second", List.of(), 1000, "third", null, null))));
        when(media.status(any())).thenReturn(QueueItemStatus.PENDING);
    }

    @Test
    void genericContainerImportIsBoundedIdempotentAndPrefetchesOutsideQueueMonitor() {
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(queue));
            return null;
        }).when(media).prefetch(any());
        var first = imports.enqueue(request, "session").block();
        assertNotNull(first);
        assertFalse(first.error());
        assertEquals(1, first.added());
        assertEquals(1, first.limited());
        assertEquals(first, imports.enqueue(request, "session").block());
        assertEquals(1, queue.getQueueSnapshot().size());
        verify(resources).importTracks(eq("third"), eq("favorites"), eq("opaque:id"), anyInt());
        verify(media).prefetch(track);
        verify(media, never()).getPlayableMusic(anyString(), anyString());
    }

    @Test
    void reimportRetriesFailedDuplicateInsteadOfDroppingIt() {
        queue.add(track, new UserSummary("member", "session", "Listener", false), QueueItemStatus.FAILED);
        when(media.status(track)).thenReturn(QueueItemStatus.FAILED);
        var second = new ContainerImportService.Request("third", "favorites", "opaque:id", "r2");
        var result = imports.enqueue(second, "session").block();
        assertNotNull(result);
        assertEquals(1, result.duplicates());
        verify(media).retry(track);
    }

    @Test
    void requestIdCannotBeReusedForDifferentResource() {
        imports.enqueue(request, "session").block();
        var other = new ContainerImportService.Request("third", "favorites", "different", "r1");
        assertTrue(imports.enqueue(other, "session").block().error());
        verify(resources, never()).container("third", "favorites", "different");
    }

    @Test
    void disconnectDuringContainerFetchDoesNotMutateQueue() {
        Sinks.One<List<Music>> response = Sinks.one();
        when(resources.importTracks(eq("third"), eq("favorites"), eq("opaque:id"), anyInt())).thenReturn(response.asMono());
        var result = imports.enqueue(request, "session").toFuture();
        when(users.getUser("session")).thenReturn(Optional.empty());
        response.tryEmitValue(List.of(track));
        assertTrue(result.join().error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
        verify(media, never()).prefetch(any());
    }

    @Test
    void totalDeadlineIncludesNeverCompletingContainerMetadata() {
        when(resources.container(anyString(), anyString(), anyString())).thenReturn(Mono.never());
        assertTrue(imports.enqueue(request, "session").block(java.time.Duration.ofSeconds(35)).error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test
    void disableAndReenableDuringFetchRejectsOriginalAuthorizationEpoch() {
        Sinks.One<List<Music>> response = Sinks.one();
        when(resources.importTracks(anyString(), anyString(), anyString(), anyInt())).thenReturn(response.asMono());
        when(sources.authorizationEpoch("third")).thenReturn(0L);
        var result = imports.enqueue(request, "session").toFuture();
        when(sources.authorizationEpoch("third")).thenReturn(2L);
        response.tryEmitValue(List.of(track));
        assertTrue(result.join().error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test
    void commitRevalidatesMemberInsideQueueGate() {
        when(users.getUser("session")).thenAnswer(invocation -> {
            if (Thread.holdsLock(queue)) {
                assertTrue(Thread.holdsLock(users));
                assertTrue(Thread.holdsLock(sources));
                return Optional.empty();
            }
            return Optional.of(new User("member", "session", "Listener"));
        });
        assertTrue(imports.enqueue(request, "session").block().error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }

    @Test
    void postCommitPrefetchFailureDoesNotReportImportFailure() {
        doThrow(new IllegalStateException("disabled after commit")).when(media).prefetch(any());
        var result = imports.enqueue(request, "session").block();
        assertFalse(result.error());
        assertEquals(1, result.added());
        assertEquals(result, imports.enqueue(request, "session").block());
        assertEquals(1, queue.getQueueSnapshot().size());
    }

    @Test
    void malformedMixedSourceBatchIsRejectedBeforeAnyInsertion() {
        when(resources.importTracks(eq("third"), eq("favorites"), eq("opaque:id"), anyInt()))
                .thenReturn(Mono.just(List.of(track, new Music("2", "Other", List.of(), 1000, "other", null, null))));
        assertTrue(imports.enqueue(request, "session").block().error());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }
}

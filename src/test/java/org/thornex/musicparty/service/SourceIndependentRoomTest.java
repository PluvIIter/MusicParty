package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceIndependentRoomTest {
    private AppProperties properties;
    private SourceRegistry sources;
    private MediaDeliveryService media;
    private MusicQueueManager queue;
    private MusicPlayerService player;
    private UserService users;
    private final UserSummary owner = new UserSummary("member", "session", "Listener", false);

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        IMusicApiService adapter = mock(IMusicApiService.class);
        when(adapter.getPlatformName()).thenReturn("third-source");
        when(adapter.defaultDeliveryMode()).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("third-source", "Third", "music",
                new SourceCapabilities(true, true, true, false, false, false, true, true),
                Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD), List.of()));
        sources = new SourceRegistry(List.of(adapter), properties);
        media = mock(MediaDeliveryService.class);
        when(media.status(any())).thenReturn(QueueItemStatus.PENDING);
        queue = new MusicQueueManager(properties);
        users = mock(UserService.class);
        when(users.getUser("session")).thenReturn(Optional.of(new User("member", "session", "Listener")));
        when(users.getOnlineUserSummaries()).thenReturn(List.of(owner));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of("member"));
        player = new MusicPlayerService(sources, users, media, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), properties, new PrivateDjService(List.of(), sources, properties));
    }

    private Music track(String source) {
        return new Music("same-id", "Track", List.of("Artist"), 1000, source, null);
    }

    private PlayableMusic playable() {
        return new PlayableMusic("same-id", "Track", List.of("Artist"), 1000, "third-source", "/media/ticket", null, false);
    }

    @Test
    void thirdSourceEnqueuesMetadataThenPrefetchesWithoutResolvingPlayable() {
        when(media.getMetadata("third-source", "same-id")).thenReturn(Mono.just(track("third-source")));
        player.enqueue(new EnqueueRequest("third-source", "same-id"), "session");
        assertEquals(QueueItemStatus.PENDING, queue.getQueueSnapshot().getFirst().status());
        verify(media).prefetch(track("third-source"));
        verify(media, never()).getPlayableMusic(anyString(), anyString());
        player.playerLoop();
        assertFalse(player.getCurrentPlayerState().isLoading());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertEquals(1, queue.getQueueSnapshot().size());
    }

    @Test
    void transientDownloadFailureRetriesOnlyAfterCooldownAndPreservesFirstPlayLedger() {
        properties.getQueue().setRetainPlaylist(true);
        var entry = queue.add(track("third-source"), owner, QueueItemStatus.PENDING);
        when(media.status(any())).thenReturn(QueueItemStatus.FAILED);
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(player));
            assertFalse(Thread.holdsLock(queue));
            when(media.status(any())).thenReturn(QueueItemStatus.READY);
            return null;
        }).when(media).prefetch(track("third-source"));
        when(media.getPlayableMusic("third-source", "same-id")).thenReturn(Mono.just(playable()));
        player.playerLoop();
        verify(media, timeout(5000)).prefetch(track("third-source"));
        assertEquals(List.of(entry), queue.getQueueSnapshot());
        assertTrue(queue.getPlayedQueueIds().isEmpty());
        player.playerLoop();
        verify(media, never()).getPlayableMusic(anyString(), anyString());
        @SuppressWarnings("unchecked")
        var retryAt = (Map<String, Long>) org.springframework.test.util.ReflectionTestUtils.getField(queue, "playbackRetryAt");
        retryAt.replaceAll((key, time) -> System.currentTimeMillis() - 1);
        player.playerLoop();
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertEquals(Set.of(entry.queueId()), queue.getPlayedQueueIds());
        assertEquals(1, queue.getQueueSnapshot().size());
        verify(media).prefetch(track("third-source"));
    }

    @Test
    void readinessAndDuplicateIdentityAreNamespacedForSameRawId() {
        var first = queue.add(track("third-source"), owner, QueueItemStatus.PENDING);
        var other = queue.add(track("other-source"), owner, QueueItemStatus.READY);
        assertNotNull(first);
        assertNotNull(other);
        assertNull(queue.add(track("third-source"), owner, QueueItemStatus.READY));
        var status = Map.of(ResourceKey.of(first.music()).encoded(), QueueItemStatus.PENDING,
                ResourceKey.of(other.music()).encoded(), QueueItemStatus.READY);
        assertEquals(other, queue.pollNext(PlayMode.SEQUENTIAL, false, false, status, Set.of("member")));
        assertEquals(List.of(first), queue.getQueueSnapshot());
    }

    @Test
    void disablingSourcePreservesQueuedTracksInsteadOfConsumingFailures() {
        queue.add(track("third-source"), owner, QueueItemStatus.READY);
        sources.update("third-source", false, null);
        when(media.status(any())).thenReturn(QueueItemStatus.FAILED);
        player.playerLoop();
        assertEquals(1, queue.getQueueSnapshot().size());
        assertEquals(QueueItemStatus.PENDING, player.getCurrentPlayerState().queue().getFirst().status());
        verify(media, never()).getPlayableMusic(anyString(), anyString());
        verify(media, never()).prefetch(any());
    }

    @Test
    void repeatOneRecoversWhenAuthorizationFailsAfterSourceDisabled() {
        when(media.status(any())).thenReturn(QueueItemStatus.READY);
        when(media.getPlayableMusic("third-source", "same-id")).thenReturn(Mono.just(playable()));
        when(media.authorizationScope("third-source")).thenAnswer(invocation -> {
            sources.getRequired("third-source");
            return "scope";
        });
        queue.add(track("third-source"), owner, QueueItemStatus.READY);
        player.playerLoop();
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        player.setPlayModeForTest(PlayMode.REPEAT_ONE);
        sources.update("third-source", false, null);
        player.setPositionForTest(1000);
        assertDoesNotThrow(player::playerLoop);
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        sources.update("third-source", true, null);
        queue.add(track("third-source"), owner, QueueItemStatus.READY);
        player.playerLoop();
        PlayerTestFactory.await(() -> player.getCurrentPlayerState().nowPlaying() != null);
        assertFalse(player.getCurrentPlayerState().isLoading());
    }

    @Test
    void disabledSourceResultCannotCommitAfterResolvingBeforeRoomMonitor() {
        properties.getQueue().setRetainPlaylist(true);
        queue.add(track("third-source"), owner, QueueItemStatus.READY);
        when(media.status(any())).thenReturn(QueueItemStatus.READY);
        var response = reactor.core.publisher.Sinks.<PlayableMusic>one();
        when(media.getPlayableMusic("third-source", "same-id")).thenReturn(response.asMono());
        player.playerLoop();
        verify(media, timeout(5000)).getPlayableMusic("third-source", "same-id");
        sources.update("third-source", false, null);
        response.tryEmitValue(playable());
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertEquals(1, queue.getQueueSnapshot().size());
        assertTrue(queue.getPlayedQueueIds().isEmpty());
    }

    @Test
    void changedCredentialScopeCannotCommitLatePlayableOrConsumeFirstPlay() {
        properties.getQueue().setRetainPlaylist(true);
        queue.add(track("third-source"), owner, QueueItemStatus.READY);
        when(media.status(any())).thenReturn(QueueItemStatus.READY);
        when(media.authorizationScope("third-source")).thenReturn("old-scope");
        var response = reactor.core.publisher.Sinks.<PlayableMusic>one();
        when(media.getPlayableMusic("third-source", "same-id")).thenReturn(response.asMono());
        player.playerLoop();
        verify(media, timeout(5000)).getPlayableMusic("third-source", "same-id");
        when(media.authorizationScope("third-source")).thenReturn("new-scope");
        response.tryEmitValue(playable());
        PlayerTestFactory.await(() -> !player.getCurrentPlayerState().isLoading());
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertTrue(queue.getPlayedQueueIds().isEmpty());
        assertEquals(1, queue.getQueueSnapshot().size());
    }

    @Test
    void mediaPreparationRunsOutsidePlayerAndQueueMonitors() throws Exception {
        queue.add(track("third-source"), owner, QueueItemStatus.READY);
        when(media.status(any())).thenReturn(QueueItemStatus.READY);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(media.getPlayableMusic("third-source", "same-id")).thenAnswer(invocation -> {
            assertFalse(Thread.holdsLock(player));
            assertFalse(Thread.holdsLock(queue));
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Mono.just(playable());
        });
        try {
            player.playerLoop();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(player.getCurrentPlayerState().isLoading());
            player.resetSystem();
        } finally { release.countDown(); }
        verify(media, timeout(5000)).getPlayableMusic("third-source", "same-id");
        assertNull(player.getCurrentPlayerState().nowPlaying());
    }

    @Test
    void persistedSourceOverridesRoundTripWithoutPersistingDeploymentDeliveryMode(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var mapper = new ObjectMapper();
        properties.getQueue().setPersistenceFile(directory.resolve("queue.json").toString());
        var persistence = new QueuePersistenceService(queue, mock(ChatService.class), properties, mapper, player,
                mock(org.thornex.musicparty.controller.AuthController.class), mock(LiveStreamService.class));
        sources.update("third-source", false, null);
        persistence.saveNow();
        var snapshot = mapper.readTree(directory.resolve("queue.json").toFile());
        assertFalse(snapshot.path("settings").path("sourceEnabled").path("third-source").asBoolean());
        assertFalse(snapshot.path("settings").has("deliveryMode"));
        sources.update("third-source", true, null);
        persistence.loadData();
        assertFalse(sources.isEnabled("third-source"));
        assertEquals(DeliveryMode.SERVER_DOWNLOAD, sources.deliveryMode("third-source"));
    }

    @Test
    void pendingAssetsArePreparedAfterRestoreOrCredentialInvalidationWithoutResolvingUnderRoomLock() {
        queue.add(track("third-source"), owner, QueueItemStatus.PENDING);
        player.prepareRestoredQueue();
        verify(media).prefetch(track("third-source"));
        when(media.status(any())).thenReturn(QueueItemStatus.READY);
        player.prepareRestoredQueue();
        verify(media, times(2)).status(track("third-source"));
        verifyNoMoreInteractions(media);
    }

    @Test
    void adminSettingsUseEffectiveRegisteredSourcesAndRejectPartialModeMutations() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(new SourceSettings(true, DeliveryMode.SERVER_DOWNLOAD), player.getSourceSettings().get("third-source"));
        var invalid = mapper.readValue("{\"maxUserSongs\":1,\"sources\":{\"third-source\":{\"enabled\":false,\"deliveryMode\":\"DIRECT_URL\"}}}", AdminConfigUpdateRequest.class);
        assertThrows(RuntimeException.class, () -> player.updateConfig(invalid));
        assertTrue(sources.isEnabled("third-source"));
        assertNotEquals(1, properties.getQueue().getMaxUserSongs());
        player.updateConfig(mapper.readValue("{\"sources\":{\"third-source\":{\"enabled\":false}}}", AdminConfigUpdateRequest.class));
        assertFalse(sources.isEnabled("third-source"));
        assertFalse(player.getCurrentPlayerState().config().sources().get("third-source").enabled());
        assertFalse(player.getCurrentPlayerState().config().recommendationsAvailable());
    }
}

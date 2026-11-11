package org.thornex.musicparty.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaDeliveryServiceTest {
    SourceRegistry registry;
    LocalCacheService cache;
    IMusicApiService adapter;
    MediaDeliveryService service;
    Music music = new Music("same/id", "Track", List.of(), 120000, "stub", "");

    @BeforeEach void setup() {
        registry = mock(SourceRegistry.class); cache = mock(LocalCacheService.class); adapter = mock(IMusicApiService.class);
        when(registry.getRequired("stub")).thenReturn(adapter); when(registry.isEnabled("stub")).thenReturn(true);
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.DIRECT_URL);
        when(adapter.getMusic("same/id")).thenReturn(Mono.just(music));
        when(adapter.resolveMedia(eq("same/id"), any())).thenAnswer(call -> Mono.just(new ResolvedMedia(
                new ResourceKey("stub", "same/id"), "mp3:full", "https://93.184.216.34/audio", "audio/mpeg", ".mp3",
                Instant.now().plusSeconds(100), Map.of(), true)));
        service = new MediaDeliveryService(registry, cache);
    }

    @Test void metadataNeverResolvesOrDownloads() {
        assertEquals(music, service.getMetadata("stub", "same/id").block());
        verify(adapter, never()).resolveMedia(anyString(), any()); verifyNoInteractions(cache);
    }

    @Test void stubDirectModeUsesBrowserConsumerAndNoSourceBranches() {
        var playable = service.getPlayableMusic("stub", "same/id").block();
        assertEquals("https://93.184.216.34/audio", playable.url()); assertEquals("stub", playable.platform());
        assertEquals(QueueItemStatus.READY, service.status(music));
        verify(adapter).resolveMedia("same/id", MediaConsumer.BROWSER); verifyNoInteractions(cache);
    }

    @Test void sourceDisableIsCheckedAtSubscription() {
        var pending = service.getMetadata("stub", "same/id");
        when(registry.getRequired("stub")).thenThrow(new IllegalStateException("disabled"));
        assertThrows(IllegalStateException.class, pending::block); verify(adapter, never()).getMusic(anyString());
    }

    @Test void downloadFailureNeverFallsBackToBrowserDirect() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.FAILED);
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "same/id").block());
        verify(adapter, never()).resolveMedia(anyString(), eq(MediaConsumer.BROWSER));
        verify(cache).submitDownload(startsWith(ResourceKey.encode("stub", "same/id")), any(), eq(Map.of()), any());
        assertEquals(QueueItemStatus.FAILED, service.status(music));
    }

    @Test void browserUnsafeResolvedMediaIsRejected() {
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "dash", "https://93.184.216.34/audio", "audio/mp4", ".m4a", Instant.now().plusSeconds(100), Map.of("Cookie", "secret"), false)));
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "same/id").block());
    }

    @Test void radioUsesServerConsumerAndRepresentationIdentityNotSignedUrl() {
        var playable = new org.thornex.musicparty.dto.PlayableMusic(music.id(), music.name(), music.artists(), music.duration(), music.platform(), "old", "", false);
        var input = service.serverInput(playable).block();
        assertEquals(ResourceKey.encode("stub", "same/id") + ":mp3:full", input.key());
        verify(adapter).resolveMedia("same/id", MediaConsumer.SERVER);
    }

    @Test void ticketCannotAttachNewAuthorizationToOldPreparedAsset() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        java.util.concurrent.atomic.AtomicReference<String> oldAsset = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> { oldAsset.set(call.getArgument(0)); return null; }).when(cache)
                .submitDownload(anyString(), any(), anyMap(), any());
        service.prefetch(music);
        when(adapter.credentialRevision()).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "issueTicket", "stub", oldAsset.get()));
    }

    @Test void downloadedRefreshRejectsFullToTrialContentChange() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        var entry = new LocalCacheService.CacheEntry(); entry.setRepresentationKey("full:120000");
        when(cache.getCacheEntry(anyString())).thenReturn(entry);
        service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L);
        entry.setRepresentationKey("trial:30000");
        var failure = assertThrows(RuntimeException.class, () -> service.refreshSongUrl("stub", "same/id").block());
        assertEquals("MEDIA_CONTENT_CHANGED", failure.getMessage());
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class,
                () -> service.refreshSongUrl("stub", "same/id").block()).getMessage());
    }

    @Test void restartNeverReusesPriorCredentialScopeEvenWhenRevisionsReset() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        java.util.Set<String> completed = new java.util.HashSet<>();
        doAnswer(call -> { completed.add(call.getArgument(0)); return null; }).when(cache)
                .submitDownload(anyString(), any(), anyMap(), any());
        when(cache.getStatus(anyString())).thenAnswer(call -> completed.contains(call.getArgument(0)) ? CacheStatus.COMPLETED : null);
        service.prefetch(music);
        assertEquals(QueueItemStatus.READY, service.status(music));
        MediaDeliveryService restartedWithOtherCredentials = new MediaDeliveryService(registry, cache);
        assertEquals(QueueItemStatus.PENDING, restartedWithOtherCredentials.status(music));
        restartedWithOtherCredentials.prefetch(music);
        assertEquals(2, completed.size());
        assertEquals(QueueItemStatus.READY, restartedWithOtherCredentials.status(music));
    }

    @Test void mediaPolicyRejectsPrivateHostsUnsafeSchemesAndHeaderInjection() {
        for (String url : List.of("http://127.0.0.1/a", "http://169.254.169.254/a", "http://10.0.0.1/a", "file:///etc/passwd", "https://user:secret@example.com/a")) {
            assertThrows(RuntimeException.class, () -> MediaUrlPolicy.validateUrl(url));
        }
        assertThrows(RuntimeException.class, () -> MediaUrlPolicy.validateHeaders(Map.of("Referer", "safe\r\nCookie: secret")));
    }
}

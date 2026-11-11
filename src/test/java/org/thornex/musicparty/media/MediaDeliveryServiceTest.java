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
    Music music = new Music("same/id", "Track", List.of(), 120000, "stub", "", null);

    @BeforeEach void setup() {
        registry = mock(SourceRegistry.class); cache = mock(LocalCacheService.class); adapter = mock(IMusicApiService.class);
        when(registry.getRequired("stub")).thenReturn(adapter); when(registry.isEnabled("stub")).thenReturn(true);
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.DIRECT_URL);
        when(adapter.getMusic("same/id")).thenReturn(Mono.just(music));
        when(adapter.resolveMedia(eq("same/id"), any())).thenAnswer(call -> Mono.just(new ResolvedMedia(
                new ResourceKey("stub", "same/id"), "mp3:full", "https://93.184.216.34/audio", "audio/mpeg", ".mp3",
                Instant.now().plusSeconds(100), Map.of(), true, "mp3:full")));
        when(registry.descriptor("stub")).thenReturn(new SourceDescriptor("stub", "Stub", "music",
                new SourceCapabilities(true, false, false, false, false, false, true, true),
                java.util.Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD), List.of()));
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
                "dash", "https://93.184.216.34/audio", "audio/mp4", ".m4a", Instant.now().plusSeconds(100), Map.of("Cookie", "secret"), false, "dash")));
        assertThrows(RuntimeException.class, () -> service.getPlayableMusic("stub", "same/id").block());
    }

    @Test void radioUsesServerConsumerAndRepresentationIdentityNotSignedUrl() {
        var playable = service.getPlayableMusic("stub", "same/id").block();
        var input = service.serverInput(playable).block();
        assertEquals(ResourceKey.encode("stub", "same/id") + ":mp3:full:timeline:mp3:full", input.key());
        verify(adapter).resolveMedia("same/id", MediaConsumer.SERVER);
    }

    @Test void radioRejectsChangedTimelineAfterCredentialDowngrade() {
        var full = service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L);
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "trial:30000", "https://93.184.216.34/trial", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial:30000")));
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.serverInput(full).block()).getMessage());
    }

    @Test void radioRejectsUnregisteredOldPlayableInsteadOfRebaselining() {
        var manual = new org.thornex.musicparty.dto.PlayableMusic(music.id(), music.name(), music.artists(), music.duration(), "stub", "old", "", "SONG", null);
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.serverInput(manual).block()).getMessage());
        verify(adapter, never()).resolveMedia(anyString(), any());
    }

    @Test void distinctIssuedInstancesDoNotOverwriteTimelineEvenWithIdenticalDtoFields() {
        var full = service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L);
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "trial:30000", full.url(), "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial:30000")));
        var trial = service.getPlayableMusic("stub", "same/id").block();
        assertEquals(full, trial); assertNotSame(full, trial);
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.serverInput(full).block()).getMessage());
        assertNotNull(service.serverInput(trial).block());
    }

    @Test void currentRefreshRejectsTimelineChangeEvenWithSameEncodingAndSignedUrl() {
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "html5", "https://93.184.216.34/audio", "audio/mp4", ".mp4", Instant.now().plusSeconds(100), Map.of(), true, "cid:full")));
        var current = service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L);
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "html5", current.url(), "audio/mp4", ".mp4", Instant.now().plusSeconds(100), Map.of(), true, "cid:trial")));
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.refreshSongUrl(current).block()).getMessage());
    }

    @Test void downloadUsesTimelineVersionedAssetsInsteadOfReusingOldReadyBytes() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        var entry = new LocalCacheService.CacheEntry(); entry.setRepresentationKey("same-codec");
        when(cache.getCacheEntry(anyString())).thenReturn(entry);
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "same-codec", "https://93.184.216.34/audio", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "full")));
        var full = service.getPlayableMusic("stub", "same/id").block();
        String fullKey = service.ticket(full.url().substring("/media/".length())).assetKey();
        Map<String, Mono<ResolvedMedia>> resolutions = (Map<String, Mono<ResolvedMedia>>) org.springframework.test.util.ReflectionTestUtils.getField(service, "resolutions");
        resolutions.clear();
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "same-codec", "https://93.184.216.34/audio", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial")));
        var trial = service.getPlayableMusic("stub", "same/id").block();
        String trialKey = service.ticket(trial.url().substring("/media/".length())).assetKey();
        assertNotEquals(fullKey, trialKey);
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.refreshSongUrl(full).block()).getMessage());
        when(cache.localPath(trialKey)).thenReturn(java.nio.file.Path.of("/cache/trial.mp3"));
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.serverInput(full).block()).getMessage());
        assertNotNull(service.serverInput(trial).block());
    }

    @Test void rejectedRefreshCandidateCannotMoveActivePlaybackPinToOtherAsset() throws Exception {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        var entry = new LocalCacheService.CacheEntry(); entry.setRepresentationKey("full"); when(cache.getCacheEntry(anyString())).thenReturn(entry);
        var full = service.getPlayableMusic("stub", "same/id").block();
        String fullKey = service.ticket(full.url().substring("/media/".length())).assetKey();
        when(cache.localPath(fullKey)).thenReturn(java.nio.file.Path.of("/cache/full.mp3"));
        AutoCloseable originalPin = mock(AutoCloseable.class); when(cache.pin(fullKey)).thenReturn(originalPin);
        var state = mock(org.thornex.musicparty.dto.PlayerState.class); var now = mock(org.thornex.musicparty.dto.NowPlayingInfo.class);
        when(state.nowPlaying()).thenReturn(now); when(now.music()).thenReturn(full);
        var event = new org.thornex.musicparty.event.PlayerStateEvent(this, state);
        service.onPlayerState(event);
        Map<String, Mono<ResolvedMedia>> resolutions = (Map<String, Mono<ResolvedMedia>>) org.springframework.test.util.ReflectionTestUtils.getField(service, "resolutions");
        resolutions.clear();
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                "same-codec", "https://93.184.216.34/trial", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial")));
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.refreshSongUrl(full).block()).getMessage());
        service.onPlayerState(event);
        verify(originalPin, never()).close(); verify(cache, times(1)).pin(fullKey); verify(cache, times(1)).pin(anyString());
    }

    @Test void currentDownloadRefreshRejectsChangedAssetTimeline() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        var entry = new LocalCacheService.CacheEntry(); entry.setRepresentationKey("full");
        when(cache.getCacheEntry(anyString())).thenReturn(entry);
        var current = service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L); entry.setRepresentationKey("trial");
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"), "trial", "https://93.184.216.34/trial", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial")));
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.refreshSongUrl(current).block()).getMessage());
    }

    @Test void radioAllowsDifferentEncodingOfSameTimeline() {
        when(adapter.resolveMedia(anyString(), any())).thenAnswer(call -> Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"),
                call.getArgument(1) == MediaConsumer.BROWSER ? "html5:mp4" : "dash:m4a", "https://93.184.216.34/audio",
                "audio/mp4", ".m4a", Instant.now().plusSeconds(100), Map.of(), true, "full:120000")));
        var full = service.getPlayableMusic("stub", "same/id").block();
        assertNotNull(service.serverInput(full).block());
    }

    @Test void radioRejectsChangedCachedAndAwaitedDownloadTimeline() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        var entry = new LocalCacheService.CacheEntry(); entry.setRepresentationKey("mp3:full");
        when(cache.getCacheEntry(anyString())).thenReturn(entry);
        var full = service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L); entry.setRepresentationKey("trial:30000");
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"), "trial:30000", "https://93.184.216.34/trial", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial:30000")));
        when(cache.localPath(anyString())).thenReturn(java.nio.file.Path.of("/cache/trial.mp3"));
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.serverInput(full).block()).getMessage());
        when(cache.localPath(anyString())).thenReturn(null);
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class, () -> service.serverInput(full).block()).getMessage());
    }

    @Test void radioCapabilityIsEnforcedBeforeResolvingOrReadingCache() {
        when(registry.descriptor("stub")).thenReturn(new SourceDescriptor("stub", "Stub", "music",
                new SourceCapabilities(true, false, false, false, false, false, true, false),
                java.util.Set.of(DeliveryMode.DIRECT_URL), List.of()));
        var playable = new org.thornex.musicparty.dto.PlayableMusic(music.id(), music.name(), music.artists(), music.duration(), "stub", "old", "", "SONG", null);
        assertThrows(RuntimeException.class, () -> service.serverInput(playable).block());
        assertFalse(service.sourceAvailable("stub"));
        verifyNoInteractions(cache); verify(adapter, never()).resolveMedia(anyString(), any());
    }

    @Test void ticketCannotAttachNewAuthorizationToOldPreparedAsset() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        java.util.concurrent.atomic.AtomicReference<String> oldAsset = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> { oldAsset.set(call.getArgument(0)); return null; }).when(cache)
                .submitDownload(anyString(), any(), anyMap(), any());
        service.prefetch(music);
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.timeout(3000)).submitDownload(anyString(), any(), anyMap(), any());
        when(adapter.credentialRevision()).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "issueTicket", "stub", oldAsset.get()));
    }

    @Test void downloadedRefreshRejectsFullToTrialContentChange() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        var entry = new LocalCacheService.CacheEntry(); entry.setRepresentationKey("mp3:full");
        when(cache.getCacheEntry(anyString())).thenReturn(entry);
        var current = service.getPlayableMusic("stub", "same/id").block();
        when(adapter.credentialRevision()).thenReturn(1L);
        entry.setRepresentationKey("trial:30000");
        when(adapter.resolveMedia(anyString(), any())).thenReturn(Mono.just(new ResolvedMedia(new ResourceKey("stub", "same/id"), "trial:30000", "https://93.184.216.34/trial", "audio/mpeg", ".mp3", Instant.now().plusSeconds(100), Map.of(), true, "trial:30000")));
        var failure = assertThrows(RuntimeException.class, () -> service.refreshSongUrl(current).block());
        assertEquals("MEDIA_CONTENT_CHANGED", failure.getMessage());
        assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(RuntimeException.class,
                () -> service.refreshSongUrl(current).block()).getMessage());
    }

    @Test void restartNeverReusesPriorCredentialScopeEvenWhenRevisionsReset() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        java.util.Set<String> completed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        doAnswer(call -> { completed.add(call.getArgument(0)); return null; }).when(cache)
                .submitDownload(anyString(), any(), anyMap(), any());
        when(cache.getStatus(anyString())).thenAnswer(call -> completed.contains(call.getArgument(0)) ? CacheStatus.COMPLETED : null);
        service.prefetch(music);
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.timeout(3000)).submitDownload(anyString(), any(), anyMap(), any());
        assertEquals(QueueItemStatus.READY, service.status(music));
        MediaDeliveryService restartedWithOtherCredentials = new MediaDeliveryService(registry, cache);
        assertEquals(QueueItemStatus.PENDING, restartedWithOtherCredentials.status(music));
        restartedWithOtherCredentials.prefetch(music);
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.timeout(3000).times(2)).submitDownload(anyString(), any(), anyMap(), any());
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

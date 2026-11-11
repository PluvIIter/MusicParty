package org.thornex.musicparty.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.DeliveryMode;
import org.thornex.musicparty.source.ResourceKey;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceMediaDeliveryTest {
    SourceRegistry registry;
    IMusicApiService adapter;
    LocalCacheService cache;
    MediaDeliveryService delivery;
    PrivateDjSegment.Voice voice = new PrivateDjSegment.Voice("https://93.184.216.34/native-voice.mp3", "native/audio:opaque", 5000, "1", "cover");

    @BeforeEach void setup() {
        registry = mock(SourceRegistry.class); adapter = mock(IMusicApiService.class); cache = mock(LocalCacheService.class);
        when(registry.getRequired("stub")).thenReturn(adapter); when(registry.isEnabled("stub")).thenReturn(true);
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.DIRECT_URL);
        when(registry.descriptor("stub")).thenReturn(new org.thornex.musicparty.source.SourceDescriptor("stub", "Stub", "music",
                new org.thornex.musicparty.source.SourceCapabilities(true, false, false, false, false, true, true, true),
                java.util.Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD), java.util.List.of()));
        delivery = new MediaDeliveryService(registry, cache);
    }

    @Test void directVoiceUsesProviderMediaAndRadioNeverReparsesSong() {
        PlayableMusic prepared = delivery.prepareVoice("stub", voice).block();
        assertEquals("VOICE", prepared.kind()); assertTrue(prepared.id().startsWith("voice:"));
        assertNotEquals(voice.voiceId(), prepared.id()); assertEquals(5000, prepared.duration());
        assertEquals(voice.voiceUrl(), prepared.url()); assertNull(prepared.externalUrl());
        ServerInput input = delivery.serverInput(prepared).block();
        assertEquals(voice.voiceUrl(), input.location()); assertEquals(Map.of(), input.headers());
        verify(adapter, never()).getMusic(anyString()); verify(adapter, never()).resolveMedia(anyString(), any());
        verifyNoInteractions(cache);
    }

    @Test void identicalSegmentsDeduplicateButChangedRepresentationIsVersioned() {
        PlayableMusic first = delivery.prepareVoice("stub", voice).block();
        assertEquals(first, delivery.prepareVoice("stub", voice).block());
        PrivateDjSegment.Voice changed = new PrivateDjSegment.Voice("https://93.184.216.34/voice-v2.mp3", voice.voiceId(), voice.durationMs(), "1", "cover");
        PlayableMusic next = delivery.prepareVoice("stub", changed).block();
        assertNotEquals(first.id(), next.id());
        assertNotEquals(ResourceKey.encode("stub", voice.voiceId()), ResourceKey.encode("stub", first.id()));
        assertNotEquals(delivery.serverInput(first).block().key(), delivery.serverInput(next).block().key());
    }

    @Test void downloadedVoiceProducesScopedTicketAndRadioUsesLocalAsset() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        AtomicReference<String> key = new AtomicReference<>();
        doAnswer(call -> { key.set(call.getArgument(0)); return null; }).when(cache).submitVoiceDownload(anyString(), any(), anyMap(), any());
        PlayableMusic prepared = delivery.prepareVoice("stub", voice).block();
        assertTrue(prepared.url().startsWith("/media/")); assertNotEquals(voice.voiceUrl(), prepared.url());
        var ticket = delivery.ticket(prepared.url().substring("/media/".length()));
        assertEquals(key.get(), ticket.assetKey());
        when(cache.localPath(key.get())).thenReturn(Path.of("/controlled/voice.mp3"));
        assertEquals("/controlled/voice.mp3", delivery.serverInput(prepared).block().location());
        verify(adapter, never()).resolveMedia(anyString(), any());
    }

    @Test void canceledCallerRetriesSamePreparationAndAssetWithoutNewDownload() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        when(cache.getStatus(anyString())).thenAnswer(call -> ready.get() ? CacheStatus.COMPLETED : CacheStatus.DOWNLOADING);
        AtomicReference<String> key = new AtomicReference<>();
        java.util.concurrent.CountDownLatch submitted = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> { key.set(call.getArgument(0)); submitted.countDown(); return null; }).when(cache).submitVoiceDownload(anyString(), any(), anyMap(), any());
        var canceled = delivery.prepareVoice("stub", voice).toFuture();
        try { assertTrue(submitted.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new RuntimeException(error); }
        canceled.cancel(true);
        ready.set(true);
        PlayableMusic retried = delivery.prepareVoice("stub", voice).block(Duration.ofSeconds(3));
        assertEquals(key.get(), delivery.ticket(retried.url().substring("/media/".length())).assetKey());
        assertEquals(retried, delivery.prepareVoice("stub", voice).block());
        verify(cache, times(1)).submitVoiceDownload(anyString(), any(), anyMap(), any());
    }

    @Test void invalidConstructedVoiceDurationsNeverReachCache() {
        for (long duration : new long[]{-1, 0, 600001, Long.MAX_VALUE}) {
            PrivateDjSegment.Voice invalid = new PrivateDjSegment.Voice(voice.voiceUrl(), voice.voiceId(), duration, "", "");
            assertThrows(RuntimeException.class, () -> delivery.prepareVoice("stub", invalid).block());
        }
        verifyNoInteractions(cache);
    }

    @Test void queuedVoiceDownloadKeepsProviderMediaAndRechecksAuthorizationAtCommit() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
        AtomicReference<Mono<LocalCacheService.DownloadSource>> download = new AtomicReference<>();
        AtomicReference<Runnable> commit = new AtomicReference<>();
        doAnswer(call -> { download.set(call.getArgument(1)); commit.set(call.getArgument(3)); return null; })
                .when(cache).submitVoiceDownload(anyString(), any(), anyMap(), any());
        delivery.prepareVoice("stub", voice).block();
        assertEquals(voice.voiceUrl(), download.get().block().url());
        assertEquals(".mp3", download.get().block().extension());
        commit.get().run();
        when(adapter.credentialRevision()).thenReturn(1L);
        assertThrows(RuntimeException.class, commit.get()::run);
        assertThrows(RuntimeException.class, () -> download.get().block());
    }

    @Test void failedDownloadNeverReturnsProviderDirectUrl() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenReturn(CacheStatus.FAILED);
        assertThrows(RuntimeException.class, () -> delivery.prepareVoice("stub", voice).block());
        verify(cache).submitVoiceDownload(anyString(), any(), eq(Map.of()), any());
        verify(adapter, never()).getMusic(anyString()); verify(adapter, never()).resolveMedia(anyString(), any());
    }

    @Test void rawOrAlteredVoiceCannotBecomeRadioInput() {
        PlayableMusic prepared = delivery.prepareVoice("stub", voice).block();
        PlayableMusic forged = new PlayableMusic(prepared.id(), prepared.name(), prepared.artists(), prepared.duration(), "stub",
                "https://93.184.216.34/other.mp3", "", "VOICE", null);
        assertThrows(RuntimeException.class, () -> delivery.serverInput(forged).block());
        MediaDeliveryService restarted = new MediaDeliveryService(registry, cache);
        assertThrows(RuntimeException.class, () -> restarted.serverInput(prepared).block());
    }

    @Test void credentialsEpochOrDisableRevokesVoiceAssociation() {
        PlayableMusic prepared = delivery.prepareVoice("stub", voice).block();
        assertTrue(delivery.inputAvailable(prepared));
        when(adapter.credentialRevision()).thenReturn(1L);
        assertFalse(delivery.inputAvailable(prepared)); assertThrows(RuntimeException.class, () -> delivery.serverInput(prepared).block());
        when(adapter.credentialRevision()).thenReturn(0L); when(registry.authorizationEpoch("stub")).thenReturn(1L);
        assertFalse(delivery.inputAvailable(prepared));
        when(registry.isEnabled("stub")).thenReturn(false); assertFalse(delivery.inputAvailable(prepared));
    }

    @Test void privateProviderUrlIsRejectedAndDisabledSourceCheckedOnSubscription() {
        var privateVoice = new PrivateDjSegment.Voice("http://127.0.0.1/voice.mp3", "voice", 5000, "", "");
        assertThrows(RuntimeException.class, () -> delivery.prepareVoice("stub", privateVoice).block());
        Mono<PlayableMusic> pending = delivery.prepareVoice("stub", voice);
        when(registry.getRequired("stub")).thenThrow(new IllegalStateException("disabled"));
        assertThrows(RuntimeException.class, pending::block); verifyNoInteractions(cache);
    }

    @Test void downloadedVoiceRejectsLateAuthorizationChangeBeforeReady() {
        when(registry.deliveryMode("stub")).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(cache.getStatus(anyString())).thenAnswer(call -> { when(adapter.credentialRevision()).thenReturn(1L); return CacheStatus.COMPLETED; });
        assertThrows(RuntimeException.class, () -> delivery.prepareVoice("stub", voice).block(Duration.ofSeconds(3)));
    }

    @Test void voiceLifetimeEndsRadioAvailabilityWithoutSongRefresh() throws Exception {
        PlayableMusic prepared = delivery.prepareVoice("stub", voice).block();
        Map<String, Object> associations = (Map<String, Object>) ReflectionTestUtils.getField(delivery, "voices");
        String key = ResourceKey.encode("stub", prepared.id()); Object asset = associations.get(key);
        var type = asset.getClass(); var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var mediaAccessor = type.getDeclaredMethod("media"); mediaAccessor.setAccessible(true);
        ResolvedMedia original = (ResolvedMedia) mediaAccessor.invoke(asset);
        ResolvedMedia expired = new ResolvedMedia(original.resource(), original.representationKey(), original.url(), original.mimeType(), original.extension(),
                Instant.now().minusSeconds(1), Map.of(), true, original.representationKey());
        associations.put(key, constructor.newInstance("stub", prepared.id(), delivery.authorizationScope("stub"), expired,
                "unused", prepared.url()));
        assertFalse(delivery.inputAvailable(prepared)); assertThrows(RuntimeException.class, () -> delivery.serverInput(prepared).block());
        verify(adapter, never()).resolveMedia(anyString(), any());
    }
}

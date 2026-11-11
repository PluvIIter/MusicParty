package org.thornex.musicparty.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackRecoveryControllerTest {
    AppProperties props;
    AuthController auth;
    MusicPlayerService player;
    MediaDeliveryService delivery;
    SourceRegistry registry;
    IMusicApiService adapter;
    PlayerState state;
    NowPlayingInfo now;
    PlayableMusic track;
    PlaybackRecoveryController controller;

    @BeforeEach void setup() {
        props = new AppProperties(); props.setAdminPassword("admin-secret");
        auth = mock(AuthController.class); when(auth.getRawPassword()).thenReturn("room-secret");
        player = mock(MusicPlayerService.class); delivery = mock(MediaDeliveryService.class);
        registry = mock(SourceRegistry.class); adapter = mock(IMusicApiService.class);
        when(registry.getRequired("stub")).thenReturn(adapter); when(registry.isEnabled("stub")).thenReturn(true);
        state = mock(PlayerState.class); now = mock(NowPlayingInfo.class);
        track = new PlayableMusic("1", "Track", List.of("Artist"), 180000, "stub", "https://audio.test/expired", "", false);
        when(player.getCurrentPlayerState()).thenReturn(state); when(state.nowPlaying()).thenReturn(now);
        when(state.playbackId()).thenReturn("playback-1"); when(now.music()).thenReturn(track);
        when(delivery.refreshSongUrl(track)).thenReturn(Mono.just("https://audio.test/fresh"));
        controller = new PlaybackRecoveryController(player, auth, props, delivery, registry);
    }

    @Test void unauthorizedAndUninitializedRoomsDoNotReadPlayer() {
        assertEquals(403, controller.refreshCurrent("playback-1", null).block().getStatusCode().value());
        when(auth.getRawPassword()).thenReturn(null);
        assertEquals(403, controller.refreshCurrent("playback-1", null).block().getStatusCode().value());
        verifyNoInteractions(player, delivery);
    }

    @Test void anySourceRefreshPreservesMetadataAndSharedClock() {
        var first = controller.refreshCurrent("playback-1", "room-secret").block();
        var second = controller.refreshCurrent("playback-1", "room-secret").block();
        assertEquals("no-store", first.getHeaders().getCacheControl());
        assertEquals("https://audio.test/fresh", first.getBody().url());
        assertEquals(track.id(), first.getBody().id()); assertEquals(track.duration(), first.getBody().duration());
        assertEquals(first.getBody(), second.getBody());
        verify(delivery, times(1)).refreshSongUrl(track);
        assertTrue(mockingDetails(player).getInvocations().stream().allMatch(i -> i.getMethod().getName().equals("getCurrentPlayerState")));
    }

    @Test void stalePlaybackDisabledSourceAndVoiceRejectBeforeResolve() {
        assertEquals(409, controller.refreshCurrent("old", "room-secret").block().getStatusCode().value());
        when(registry.isEnabled("stub")).thenReturn(false);
        assertEquals(409, controller.refreshCurrent("playback-1", "room-secret").block().getStatusCode().value());
        when(registry.isEnabled("stub")).thenReturn(true);
        when(now.music()).thenReturn(new PlayableMusic("1", "Voice", List.of(), 1000, "stub", "https://voice.test", "", false, "VOICE"));
        assertEquals(409, controller.refreshCurrent("playback-1", "room-secret").block().getStatusCode().value());
        verifyNoInteractions(delivery);
    }

    @Test void credentialChangeInvalidatesSharedCache() {
        AtomicLong revision = new AtomicLong(); when(delivery.authorizationScope("stub")).thenAnswer(call -> "session:" + revision.get());
        controller.refreshCurrent("playback-1", "room-secret").block(); revision.incrementAndGet();
        controller.refreshCurrent("playback-1", "room-secret").block();
        verify(delivery, times(2)).refreshSongUrl(track);
    }

    @Test void lateRefreshRejectsSameSongNewPlaybackInstance() {
        Sinks.One<String> pending = Sinks.one(); when(delivery.refreshSongUrl(track)).thenReturn(pending.asMono());
        var result = controller.refreshCurrent("playback-1", "room-secret").toFuture();
        when(state.playbackId()).thenReturn("playback-2"); pending.tryEmitValue("https://audio.test/late");
        assertEquals(409, result.join().getStatusCode().value());
    }

    @Test void failuresAreSanitized() {
        when(delivery.refreshSongUrl(track)).thenReturn(Mono.error(new RuntimeException("private-cookie")));
        var error = assertThrows(RuntimeException.class, () -> controller.refreshCurrent("playback-1", "room-secret").block());
        assertFalse(error.getMessage().contains("private-cookie"));
    }
}

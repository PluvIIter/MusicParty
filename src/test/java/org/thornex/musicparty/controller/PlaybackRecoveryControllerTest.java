package org.thornex.musicparty.controller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackRecoveryControllerTest {
    AppProperties props; AuthController auth; MusicPlayerService player; NeteaseMusicApiService api;
    PlayerState state; NowPlayingInfo now; PlayableMusic track; PlaybackRecoveryController controller;
    @BeforeEach void setup() {
        props = new AppProperties(); props.setAdminPassword("admin-secret"); props.getNetease().setCookie("fixture-cookie");
        auth = mock(AuthController.class); when(auth.getRawPassword()).thenReturn("room-secret");
        player = mock(MusicPlayerService.class); api = mock(NeteaseMusicApiService.class);
        state = mock(PlayerState.class); now = mock(NowPlayingInfo.class);
        track = new PlayableMusic("1", "Track", List.of("Artist"), 180000, "netease", "https://audio.test/expired", "", false);
        when(player.getCurrentPlayerState()).thenReturn(state); when(state.nowPlaying()).thenReturn(now); when(now.music()).thenReturn(track);
        when(api.refreshSongUrl("1")).thenReturn(Mono.just("https://audio.test/fresh"));
        controller = new PlaybackRecoveryController(player, auth, props, api);
    }
    @Test void protectedAndUninitializedRoomsRejectBeforeReadingPlayer() {
        assertEquals(403, controller.refresh("1", null).block().getStatusCode().value());
        assertEquals(403, controller.refresh("1", "wrong").block().getStatusCode().value());
        when(auth.getRawPassword()).thenReturn(null);
        assertEquals(403, controller.refresh("1", null).block().getStatusCode().value());
        verifyNoInteractions(player, api);
    }
    @Test void refreshPreservesMetadataAndCachesAcrossListenersWithoutPlayerMutations() {
        var first = controller.refresh("1", "room-secret").block();
        var second = controller.refresh("1", "room-secret").block();
        assertEquals("no-store", first.getHeaders().getCacheControl());
        assertEquals("https://audio.test/fresh", first.getBody().url());
        assertEquals(track.id(), first.getBody().id()); assertEquals(track.duration(), first.getBody().duration());
        assertEquals(first.getBody(), second.getBody());
        verify(api, times(1)).refreshSongUrl("1");
        assertTrue(mockingDetails(player).getInvocations().stream().allMatch(i -> i.getMethod().getName().equals("getCurrentPlayerState")));
    }
    @Test void adminAndPublicRoomAccessWorkButOtherTracksAndDisabledSourceAreRejected() {
        assertEquals(200, controller.refresh("1", "admin-secret").block().getStatusCode().value());
        when(auth.getRawPassword()).thenReturn("");
        assertEquals(200, controller.refresh("1", null).block().getStatusCode().value());
        assertEquals(409, controller.refresh("2", null).block().getStatusCode().value());
        props.getNetease().setEnabled(false);
        assertEquals(409, controller.refresh("1", null).block().getStatusCode().value());
    }
    @Test void credentialChangeInvalidatesSharedLinkCache() {
        controller.refresh("1", "room-secret").block();
        props.getNetease().setCookie("new-fixture-cookie");
        controller.refresh("1", "room-secret").block();
        verify(api, times(2)).refreshSongUrl("1");
    }
    @Test void lateRefreshIsRejectedAfterTrackChange() {
        Sinks.One<String> pending = Sinks.one(); when(api.refreshSongUrl("1")).thenReturn(pending.asMono());
        var result = controller.refresh("1", "room-secret").toFuture();
        when(now.music()).thenReturn(new PlayableMusic("2", "New", List.of(), 180000, "netease", "https://audio.test/new", "", false));
        pending.tryEmitValue("https://audio.test/late");
        assertThrows(Exception.class, result::get);
    }
    @Test void failuresAreSanitized() {
        when(api.refreshSongUrl("1")).thenReturn(Mono.error(new RuntimeException("private-cookie-or-upstream-body")));
        ApiRequestException e = assertThrows(ApiRequestException.class, () -> controller.refresh("1", "room-secret").block());
        assertFalse(e.getMessage().contains("private-cookie"));
    }
}

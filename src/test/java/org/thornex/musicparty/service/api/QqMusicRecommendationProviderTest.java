package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaDeliveryService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicRecommendationProviderTest {
    @Test
    void nativeFmRequiresExplicitCredentialAndPreservesSongmidWithoutInventingDj() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, body) -> {
            assertEquals("/recommend/get_guess_recommend", request.url().getPath());
            assertTrue(request.headers().getFirst("Cookie").contains("musicid=\"123\""));
            assertNull(request.url().getQuery());
            return Mono.just(data(json("{\"songs\":[" + TRACK + "]}")));
        }), credentials);
        var delivery = mock(MediaDeliveryService.class);
        var provider = new QqMusicRecommendationProvider(api, delivery);
        assertEquals(Set.of("FM"), provider.supportedModes());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments("FM").block());
        credentials.updateCookie("{\"musicid\":\"123\",\"musickey\":\"secret\"}");
        var result = provider.nextSegments("FM").block();
        var segment = assertInstanceOf(PrivateDjSegment.Song.class, result.getFirst());
        assertEquals("songMID", segment.songId());
        assertEquals(180000, segment.durationMs());
        assertEquals(1, provider.credentialRevision());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments("DJ").block());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments("OFF").block());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments(null).block());
        verifyNoInteractions(delivery);
    }

    @Test
    void recommendedSongsAlwaysUseUnifiedDeliveryAndVoiceIsUnavailable() {
        var api = mock(QqMusicApiService.class);
        var delivery = mock(MediaDeliveryService.class);
        var provider = new QqMusicRecommendationProvider(api, delivery);
        var song = new PrivateDjSegment.Song("songMID", "Song", List.of("Artist"), 180000, null);
        var playable = new PlayableMusic("songMID", "Song", List.of("Artist"), 180000, "qqmusic", "/media/ticket", null, "SONG", null);
        when(delivery.getPlayableMusic("qqmusic", "songMID")).thenReturn(Mono.just(playable));
        assertSame(playable, provider.getPlayable(song).block());
        assertThrows(ApiRequestException.class, () -> provider.getPlayable(new PrivateDjSegment.Voice("https://voice.test", "voice", 1000, null, null)).block());
        verify(delivery).getPlayableMusic("qqmusic", "songMID");
        verifyNoMoreInteractions(delivery);
    }

    @Test
    void emptyNativeFmIsExplicitlyUnavailableWithoutPlaylistFallback() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"secret\"}");
        var api = api(properties, client((request, body) -> {
            assertEquals("/recommend/get_guess_recommend", request.url().getPath());
            return Mono.just(data(json("{\"songs\":[]}")));
        }), credentials);
        var provider = new QqMusicRecommendationProvider(api, mock(MediaDeliveryService.class));
        assertTrue(assertThrows(ApiRequestException.class, () -> provider.nextSegments("FM").block())
                .getMessage().contains("RECOMMENDATION_UNAVAILABLE"));
    }
}

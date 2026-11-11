package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaDeliveryService;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NeteaseRecommendationProviderTest {
    private final NeteaseMusicApiService api = mock(NeteaseMusicApiService.class);
    private final MediaDeliveryService delivery = mock(MediaDeliveryService.class);
    private final NeteaseRecommendationProvider provider = new NeteaseRecommendationProvider(api, delivery);

    private JsonNode json(String body) throws Exception {
        return new ObjectMapper().readTree(body);
    }

    @Test void fmPreservesNumericAndStringIdsBeyondLongAndSupportsBothSongShapes() throws Exception {
        var segments = NeteaseRecommendationProvider.parseFmBatch(json("""
                {"code":200,"data":[
                  {"id":"9223372036854775808123","name":"Legacy","duration":1234,
                   "artists":[{"name":"Singer"}],"album":{"picUrl":"http://cdn.test/cover.jpg"}},
                  {"id":9223372036854775808124,"name":"Modern","dt":5678,
                   "ar":[{"name":"Other"}],"al":{"picUrl":"https://cdn.test/modern.jpg"}}
                ]}
                """));
        var first = (PrivateDjSegment.Song) segments.get(0);
        var second = (PrivateDjSegment.Song) segments.get(1);
        assertEquals("9223372036854775808123", first.songId());
        assertEquals("9223372036854775808124", second.songId());
        assertEquals(List.of("Singer"), first.artists());
        assertEquals("https://cdn.test/cover.jpg", first.coverUrl());
        assertEquals(1234, first.durationMs());
        assertEquals(List.of("Other"), second.artists());
        assertEquals(5678, second.durationMs());
    }

    @Test void djPreservesNativeOrderAllVoiceClipsAndRelatedSongIdentity() throws Exception {
        var segments = NeteaseRecommendationProvider.parseDjBatch(json("""
                {"code":200,"data":{"aiDjResources":[
                  {"type":"audio","value":{"audioList":[
                    {"audioId":"voice:a","audioUrl":"http://cdn.test/a.mp3","duration":1.25,
                     "introductionRelatedSongIds":["9223372036854775808123"]},
                    {"audioId":"voice:b","audioUrl":"https://cdn.test/b.mp3","duration":2}
                  ]}},
                  {"type":"song","value":{"songData":{"id":"9223372036854775808123","name":"Track",
                    "duration":3000,"artists":[],"album":{"picUrl":"http://cdn.test/cover.jpg"}}}}
                ]}}
                """));
        assertEquals(3, segments.size());
        var voice = (PrivateDjSegment.Voice) segments.get(0);
        assertEquals("voice:a", voice.voiceId());
        assertEquals("https://cdn.test/a.mp3", voice.voiceUrl());
        assertEquals(1250, voice.durationMs());
        assertEquals("9223372036854775808123", voice.relatedSongId());
        assertEquals("https://cdn.test/cover.jpg", voice.relatedCoverUrl());
        assertEquals("voice:b", ((PrivateDjSegment.Voice) segments.get(1)).voiceId());
        assertInstanceOf(PrivateDjSegment.Song.class, segments.get(2));
    }

    @Test void voicesRejectMissingNonpositiveNonfiniteAndOversizedDurations() throws Exception {
        for (String duration : List.of("", ",\"duration\":null", ",\"duration\":0", ",\"duration\":-1",
                ",\"duration\":0.0001", ",\"duration\":600.001", ",\"duration\":\"NaN\"",
                ",\"duration\":\"Infinity\"", ",\"duration\":\"invalid\"")) {
            JsonNode root = json("""
                    {"code":200,"data":{"aiDjResources":[{"type":"audio","value":{"audioList":[
                      {"audioId":"voice","audioUrl":"https://cdn.test/voice.mp3"%s}
                    ]}}]}}
                    """.formatted(duration));
            assertThrows(ApiRequestException.class, () -> NeteaseRecommendationProvider.parseDjBatch(root), duration);
        }
    }

    @Test void voiceDurationAcceptsPositiveMillisecondAndTenMinuteBoundaries() throws Exception {
        for (String duration : List.of("0.001", "600")) {
            var segments = NeteaseRecommendationProvider.parseDjBatch(json("""
                    {"code":200,"data":{"aiDjResources":[{"type":"audio","value":{"audioList":[
                      {"audioId":"voice","audioUrl":"https://cdn.test/voice.mp3","duration":%s}
                    ]}}]}}
                    """.formatted(duration)));
            var voice = (PrivateDjSegment.Voice) segments.get(0);
            assertEquals(Math.round(Double.parseDouble(duration) * 1000), voice.durationMs());
        }
    }

    @Test void emptyMalformedAndBusinessErrorsAreExplicit() throws Exception {
        for (String body : List.of("{\"code\":200,\"data\":[]}", "{\"code\":200}",
                "{\"code\":200,\"data\":[{\"id\":0}]}", "{\"code\":500,\"data\":[]}")) {
            JsonNode root = json(body);
            assertThrows(ApiRequestException.class, () -> NeteaseRecommendationProvider.parseFmBatch(root));
        }
        for (String body : List.of("{\"code\":200,\"data\":{\"aiDjResources\":[]}}",
                "{\"code\":200,\"data\":{\"aiDjResources\":[{\"type\":\"audio\",\"value\":{\"audioList\":[{}]}}]}}")) {
            JsonNode root = json(body);
            assertThrows(ApiRequestException.class, () -> NeteaseRecommendationProvider.parseDjBatch(root));
        }
    }

    @Test void modesUseOnlyNativeEndpointsAndNeverFallback() throws Exception {
        when(api.fetchPersonalFm()).thenReturn(Mono.just(json("{\"code\":200,\"data\":[{\"id\":1}]}")));
        when(api.fetchAidjRcmd(null, null)).thenReturn(Mono.error(new ApiRequestException("login expired")));
        assertEquals("netease", provider.sourceId());
        assertEquals(1, provider.nextSegments("FM").block().size());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments("DJ").block());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments("OFF").block());
        assertThrows(ApiRequestException.class, () -> provider.nextSegments(null).block());
        verify(api).fetchPersonalFm();
        verify(api).fetchAidjRcmd(null, null);
        verifyNoInteractions(delivery);
    }

    @Test void songsAlwaysUseUnifiedMediaDelivery() {
        var segment = new PrivateDjSegment.Song("42", "Track", List.of(), 1000, "");
        var playable = new PlayableMusic("42", "Track", List.of(), 1000, "netease", "/media/ticket", "", false);
        when(delivery.getPlayableMusic("netease", "42")).thenReturn(Mono.just(playable));
        assertSame(playable, provider.getPlayable(segment).block());
        verify(delivery).getPlayableMusic("netease", "42");
        verifyNoMoreInteractions(delivery);
        verifyNoInteractions(api);
    }

    @Test void voicesDelegatePolicyAndDeliveryWithoutSongRefresh() {
        var segment = new PrivateDjSegment.Voice("https://cdn.test/voice.mp3", "voice-native", 1000, "42", "");
        var playable = new PlayableMusic("voice:opaque", "私人DJ", List.of(), 1000, "netease", "/media/voice", "", false, "VOICE");
        when(delivery.prepareVoice("netease", segment)).thenReturn(Mono.just(playable));
        assertSame(playable, provider.getPlayable(segment).block());
        verify(delivery).prepareVoice("netease", segment);
        verifyNoMoreInteractions(delivery);
        verifyNoInteractions(api);
    }

    @Test void voiceSafetyFailureDoesNotFallbackToSongOrDirectUrl() {
        var segment = new PrivateDjSegment.Voice("http://127.0.0.1/private", "voice", 1000, null, null);
        when(delivery.prepareVoice("netease", segment)).thenReturn(Mono.error(new ApiRequestException("unsafe media")));
        assertThrows(ApiRequestException.class, () -> provider.getPlayable(segment).block());
        verify(delivery).prepareVoice("netease", segment);
        verifyNoMoreInteractions(delivery);
        verifyNoInteractions(api);
    }
}

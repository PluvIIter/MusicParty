package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.source.DeliveryMode;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicMediaDeliveryTest {
    @Test
    void issuedPlaybackRejectsDifferentMediaAtSameSongIdAndDurationInEveryDeliveryMode() {
        for (DeliveryMode mode : DeliveryMode.values()) {
            var properties = properties();
            var configured = new org.thornex.musicparty.config.AppProperties.SourceConfig();
            configured.setEnabled(true);
            configured.setDeliveryMode(mode);
            properties.getSources().put("qqmusic", configured);
            var credentials = new QqMusicCredentialService(properties, MAPPER);
            var mediaMid = new AtomicReference<>("originalMID");
            var api = api(properties, client((request, body) -> Mono.just(data(
                    request.url().getPath().endsWith("/detail") ? json("{\"track\":" + TRACK.replace("mediaMID", mediaMid.get()) + "}")
                            : media("MP3_128", mediaMid.get())))), credentials);
            var registry = new SourceRegistry(List.of(api), properties);
            var cache = mock(LocalCacheService.class);
            when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
            var delivery = new MediaDeliveryService(registry, cache);
            var current = delivery.getPlayableMusic("qqmusic", "songMID").block();
            assertNotNull(current);
            assertEquals(180000, current.duration());
            mediaMid.set("replacementMID");
            credentials.updateCookie("{\"musicid\":123,\"musickey\":\"new\"}");
            var failure = assertThrows(ApiRequestException.class, () -> delivery.refreshSongUrl(current).block());
            assertEquals("MEDIA_CONTENT_CHANGED", failure.getMessage());
            assertEquals("MEDIA_CONTENT_CHANGED", assertThrows(ApiRequestException.class,
                    () -> delivery.serverInput(current).block()).getMessage());
        }
    }
}

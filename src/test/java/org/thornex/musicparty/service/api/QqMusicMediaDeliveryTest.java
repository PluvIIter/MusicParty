package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.source.DeliveryMode;
import org.thornex.musicparty.source.SourceRegistry;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicMediaDeliveryTest {
    @Test
    void bothDeliveryModesProducePlayableMediaForTheSameSongId() {
        for (DeliveryMode mode : DeliveryMode.values()) {
            var properties = properties();
            var configured = new org.thornex.musicparty.config.AppProperties.SourceConfig();
            configured.setEnabled(true);
            configured.setDeliveryMode(mode);
            properties.getSources().put("qqmusic", configured);
            var credentials = new QqMusicCredentialService(properties);
            var api = api(properties, client((request, body) -> Mono.just(data(
                    request.url().getPath().endsWith("/detail")
                            ? json("{\"track\":" + TRACK + "}")
                            : media("MP3_320", "mediaMID")))), credentials);
            var registry = new SourceRegistry(List.of(api), properties);
            var cache = mock(LocalCacheService.class);
            when(cache.getStatus(anyString())).thenReturn(CacheStatus.COMPLETED);
            var delivery = new MediaDeliveryService(registry, cache);
            var playable = delivery.getPlayableMusic("qqmusic", "songMID").block();
            assertNotNull(playable);
            assertEquals("songMID", playable.id());
            assertEquals(180000, playable.duration());
            assertEquals("qqmusic", playable.platform());
            if (mode == DeliveryMode.DIRECT_URL) {
                assertTrue(playable.url().startsWith("https://isure.stream.qqmusic.qq.com/"), playable.url());
            } else {
                assertTrue(playable.url().startsWith("/media/"), playable.url());
            }
        }
    }
}

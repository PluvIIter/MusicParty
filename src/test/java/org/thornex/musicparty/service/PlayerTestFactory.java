package org.thornex.musicparty.service;

import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.source.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

final class PlayerTestFactory {
    static MusicPlayerService create(List<IMusicApiService> adapters, UserService users, LocalCacheService unusedCache,
                                    LiveStreamService streams, MusicQueueManager queue, ApplicationEventPublisher events,
                                    AppProperties properties, NeteaseMusicApiService unusedApi, PrivateDjService dj) {
        SourceRegistry sources = mock(SourceRegistry.class);
        when(sources.descriptors()).thenReturn(List.of());
        when(sources.isEnabled(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            var config = properties.getSources().get(id);
            if (config != null && config.getEnabled() != null) return config.getEnabled();
            return !"netease".equals(id) || properties.getNetease().isEnabled();
        });
        when(sources.descriptor(anyString())).thenAnswer(invocation -> new SourceDescriptor(invocation.getArgument(0),
                "Fixture", "music", new SourceCapabilities(true, true, true, true, false, false, true, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of()));
        when(sources.getRequired(anyString())).thenAnswer(invocation -> adapters.stream()
                .filter(api -> api.getPlatformName().equals(invocation.getArgument(0))).findFirst().orElseThrow());
        MediaDeliveryService media = mock(MediaDeliveryService.class);
        when(media.status(any(Music.class))).thenReturn(QueueItemStatus.READY);
        when(media.getPlayableMusic(anyString(), anyString())).thenAnswer(invocation -> adapters.stream()
                .filter(api -> api.getPlatformName().equals(invocation.getArgument(0))).findFirst().orElseThrow()
                .getPlayableMusic(invocation.getArgument(1)));
        when(dj.isAvailable()).thenReturn(true);
        when(dj.supportsMode("FM")).thenReturn(true);
        when(dj.supportsMode("DJ")).thenReturn(true);
        return new MusicPlayerService(sources, users, media, streams, queue, events, properties, dj);
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(condition.getAsBoolean(), "asynchronous player operation did not complete");
    }
}

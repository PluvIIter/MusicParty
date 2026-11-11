package org.thornex.musicparty.source;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.api.IMusicApiService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceConfigBindingTest {
    @Test
    void bundledDefaultsPreserveLegacyPropertyAndExplicitSourceOverride() throws IOException {
        for (boolean override : List.of(false, true)) {
            var environment = new StandardEnvironment();
            for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
            var legacy = new java.util.HashMap<String, Object>();
            legacy.put("app.music-api.netease.enabled", "false");
            if (override) legacy.put("app.music-api.sources.netease.enabled", "true");
            environment.getPropertySources().addFirst(new MapPropertySource("commandLine", legacy));
            ConfigurationPropertySources.attach(environment);
            var properties = Binder.get(environment).bind("app.music-api", AppProperties.class).get();
            var adapter = mock(IMusicApiService.class);
            when(adapter.getPlatformName()).thenReturn("netease");
            when(adapter.defaultDeliveryMode()).thenReturn(DeliveryMode.DIRECT_URL);
            when(adapter.descriptor()).thenReturn(new SourceDescriptor("netease", "Netease", "music",
                    new SourceCapabilities(true, true, true, true, false, false, true, true),
                    Set.of(DeliveryMode.DIRECT_URL), List.of()));
            var bili = mock(IMusicApiService.class);
            when(bili.getPlatformName()).thenReturn("bilibili");
            when(bili.defaultDeliveryMode()).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
            when(bili.descriptor()).thenReturn(new SourceDescriptor("bilibili", "Bili", "video",
                    new SourceCapabilities(true, false, false, true, false, false, true, true),
                    Set.of(DeliveryMode.SERVER_DOWNLOAD), List.of()));
            assertEquals(override, new SourceRegistry(List.of(adapter, bili), properties).isEnabled("netease"));
            assertFalse(properties.getNetease().isEnabled());
        }
    }
}

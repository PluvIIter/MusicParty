package org.thornex.musicparty.source;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.api.IMusicApiService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceRegistryTest {
    private IMusicApiService source(String id) {
        var api = mock(IMusicApiService.class);
        when(api.getPlatformName()).thenReturn(id);
        when(api.descriptor()).thenReturn(new SourceDescriptor(id, id, "music",
                new SourceCapabilities(true, false, false, false, false, false, false, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of()));
        when(api.defaultDeliveryMode()).thenReturn(DeliveryMode.DIRECT_URL);
        return api;
    }

    @Test
    void discoversUnknownSourceWithoutPlatformListAndRevokesAuthorizationOnToggle() {
        var props = new AppProperties();
        var registry = new SourceRegistry(List.of(source("fixture")), props);
        assertEquals(1, ((List<?>) registry.catalog().get("sources")).size());
        assertTrue(registry.isEnabled("fixture"));
        registry.update("fixture", false, null);
        assertEquals(1, registry.authorizationEpoch("fixture"));
        assertThrows(RuntimeException.class, () -> registry.getRequired("fixture"));
        assertEquals(0, ((List<?>) registry.catalog().get("sources")).size());
        registry.update("fixture", true, null);
        assertEquals(2, registry.authorizationEpoch("fixture"));
        assertTrue(registry.isEnabled("fixture"));
    }

    @Test
    void rejectsUnknownDuplicateOrIncompatibleConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new SourceRegistry(List.of(source("x"), source("x")), new AppProperties()));
        var props = new AppProperties();
        props.getSources().put("missing", new AppProperties.SourceConfig());
        assertThrows(IllegalArgumentException.class, () -> new SourceRegistry(List.of(source("x")), props));
        props.getSources().clear();
        var config = new AppProperties.SourceConfig();
        config.setDeliveryMode(DeliveryMode.SERVER_DOWNLOAD);
        props.getSources().put("x", config);
        assertThrows(IllegalArgumentException.class, () -> new SourceRegistry(List.of(source("x")), props));
    }

    @Test
    void sourceMapConfigurationCannotHotChangeDelivery() {
        var props = new AppProperties();
        var configured = new AppProperties.SourceConfig();
        configured.setEnabled(true);
        props.getSources().put("netease", configured);
        var registry = new SourceRegistry(List.of(source("netease")), props);
        assertTrue(registry.isEnabled("netease"));
        assertThrows(RuntimeException.class, () -> registry.update("netease", false, DeliveryMode.SERVER_DOWNLOAD));
        assertTrue(registry.isEnabled("netease"));
    }

    @Test
    void resourceKeysCannotCollideAcrossSourcesOrAmbiguousDelimiters() {
        assertNotEquals(ResourceKey.encode("a", "b:c"), ResourceKey.encode("a", "b"));
        assertNotEquals(ResourceKey.encode("a", "42"), ResourceKey.encode("b", "42"));
        assertThrows(IllegalArgumentException.class, () -> ResourceKey.encode("../a", "42"));
        assertDoesNotThrow(() -> ResourceKey.encode("fixture", "9007199254740993/专辑"));
    }
}

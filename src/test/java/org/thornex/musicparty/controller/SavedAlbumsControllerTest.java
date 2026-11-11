package org.thornex.musicparty.controller;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.resource.*;
import org.thornex.musicparty.service.api.*;
import org.thornex.musicparty.source.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedAlbumsControllerTest {
    @Test
    void uninitializedAndProtectedRoomsRejectBeforeReadingCollection() {
        var properties = new AppProperties();
        var auth = mock(AuthController.class);
        var resources = mock(ResourceService.class);
        var controller = new SavedAlbumsController(auth, properties, resources);
        assertEquals(403, controller.saved(null, 0, 25).block().getStatusCode().value());
        when(auth.getRawPassword()).thenReturn("room");
        assertEquals(403, controller.saved("wrong", 0, 25).block().getStatusCode().value());
        verifyNoInteractions(resources);
    }

    @Test
    void noIdentityCanReadRemovedCookieAccountCategory() {
        var properties = new AppProperties();
        properties.setAdminPassword("admin");
        var auth = mock(AuthController.class);
        when(auth.getRawPassword()).thenReturn("room");
        var resources = mock(ResourceService.class);
        var controller = new SavedAlbumsController(auth, properties, resources);
        for (String password : List.of("room", "admin")) {
            var response = controller.saved(password, 0, 25).block();
            assertEquals(410, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
        }
        when(auth.getRawPassword()).thenReturn("");
        assertEquals(410, controller.saved(null, 0, 25).block().getStatusCode().value());
        verifyNoInteractions(resources);
    }

    @Test
    void legacyDetailHonorsSourceMapDisabledInsteadOfLegacyFlag() {
        var properties = new AppProperties();
        var adapter = mock(IMusicApiService.class);
        when(adapter.getPlatformName()).thenReturn("netease");
        when(adapter.defaultDeliveryMode()).thenReturn(DeliveryMode.DIRECT_URL);
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("netease", "Netease", "music",
                new SourceCapabilities(true, true, true, true, false, false, true, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of()));
        var registry = new SourceRegistry(List.of(adapter), properties);
        registry.update("netease", false, null);
        assertTrue(properties.getNetease().isEnabled());
        var auth = mock(AuthController.class);
        when(auth.getRawPassword()).thenReturn("");
        var controller = new SavedAlbumsController(auth, properties, new ResourceService(registry, new ResourceCursor()));
        assertEquals(409, controller.detail("42", null).block().getStatusCode().value());
        verify(adapter, never()).getAlbum(anyString());
    }
}

package org.thornex.musicparty.resource;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceApiControllerTest {
    @Test
    void discoveryAndReadsRequireRoomAccessNotUpstreamIdentity() {
        var adapter = mock(IMusicApiService.class);
        when(adapter.getPlatformName()).thenReturn("fixture");
        when(adapter.defaultDeliveryMode()).thenReturn(DeliveryMode.DIRECT_URL);
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("fixture", "Fixture", "music",
                new SourceCapabilities(true, false, false, false, false, false, false, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of()));
        var props = new AppProperties();
        var registry = new SourceRegistry(List.of(adapter), props);
        var auth = mock(AuthController.class);
        var controller = new SourceApiController(registry, new ResourceService(registry, new ResourceCursor()), auth, props);
        var request = new MockHttpServletRequest();
        assertThrows(RuntimeException.class, () -> controller.authorize(request));
        when(auth.getRawPassword()).thenReturn("room");
        assertThrows(RuntimeException.class, () -> controller.authorize(request));
        request.addHeader("X-Room-Password", "room");
        assertDoesNotThrow(() -> controller.authorize(request));
        assertEquals(2, controller.sources().get("schemaVersion"));
    }

    @Test
    void upstreamErrorsRemainErrorsAndUnexpectedFailureIsSanitized() {
        var controller = new SourceApiController(null, null, null, null);
        var denied = controller.failure(new org.thornex.musicparty.exception.ApiRequestException("COLLECTION_NOT_PUBLIC: 目标不公开"));
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("COLLECTION_NOT_PUBLIC", denied.getBody().get("code"));
        var upstream = controller.failure(new IllegalStateException("secret fixture cookie"));
        assertEquals(502, upstream.getStatusCode().value());
        assertFalse(upstream.getBody().toString().contains("secret"));
        assertEquals("no-store", upstream.getHeaders().getCacheControl());
    }
}

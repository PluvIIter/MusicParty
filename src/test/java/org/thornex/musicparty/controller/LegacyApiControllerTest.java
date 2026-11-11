package org.thornex.musicparty.controller;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.resource.*;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyApiControllerTest {
    @Test
    void malformedHttpParametersAreClientErrorsRatherThanUpstreamFailures() throws Exception {
        var method = ApiController.class.getMethod("getPlaylistSongs", String.class, String.class, int.class, int.class);
        var mismatch = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException("abc", int.class,
                "limit", new org.springframework.core.MethodParameter(method, 3), new NumberFormatException());
        assertEquals(400, ResourceAccess.failure(mismatch).getStatusCode().value());
        assertEquals(400, ResourceAccess.failure(new org.springframework.web.bind.MissingServletRequestParameterException("limit", "int"))
                .getStatusCode().value());
    }

    @Test
    void legacyReadsUseRoomAuthAndSharedCapabilityAndBoundsGuards() {
        var adapter = mock(IMusicApiService.class);
        when(adapter.getPlatformName()).thenReturn("fixture");
        when(adapter.defaultDeliveryMode()).thenReturn(DeliveryMode.DIRECT_URL);
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("fixture", "Fixture", "music",
                new SourceCapabilities(true, false, false, false, false, false, false, false),
                Set.of(DeliveryMode.DIRECT_URL), List.of()));
        var properties = new AppProperties();
        var auth = mock(AuthController.class);
        var registry = new SourceRegistry(List.of(adapter), properties);
        var controller = new ApiController(new ResourceService(registry, new ResourceCursor()), properties, auth);
        var request = new MockHttpServletRequest("GET", "/api/search/fixture/test");
        assertThrows(RuntimeException.class, () -> controller.authorize(request));
        when(auth.getRawPassword()).thenReturn("room");
        request.addHeader("X-Room-Password", "room");
        assertDoesNotThrow(() -> controller.authorize(request));
        assertThrows(RuntimeException.class, () -> controller.searchMusic("fixture", " "));
        assertThrows(RuntimeException.class, () -> controller.searchUsers("fixture", "test"));
        assertThrows(RuntimeException.class, () -> controller.getLyric("fixture", "song"));
        assertThrows(RuntimeException.class, () -> controller.getSongAlbum("fixture", "song"));
        assertThrows(RuntimeException.class, () -> controller.getPlaylistSongs("fixture", "id", -1, 20));
        assertThrows(RuntimeException.class, () -> controller.getPlaylistSongs("fixture", "id", 0, 1000));
        verify(adapter, never()).searchUsers(anyString());
        verify(adapter, never()).getLyric(anyString());
        verify(adapter, never()).getAlbumForSong(anyString());
        when(adapter.searchMusic("test")).thenReturn(Mono.never());
        RuntimeException timeout = assertThrows(RuntimeException.class,
                () -> controller.searchMusic("fixture", "test").block(Duration.ofSeconds(15)));
        assertInstanceOf(java.util.concurrent.TimeoutException.class, timeout.getCause());
        registry.update("fixture", false, null);
        assertThrows(RuntimeException.class, () -> controller.searchMusic("fixture", "test"));
        assertEquals(409, controller.failure(new org.thornex.musicparty.exception.ApiRequestException("SOURCE_DISABLED: no")).getStatusCode().value());
    }
}

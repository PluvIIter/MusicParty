package org.thornex.musicparty.controller;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedAlbumsControllerTest {
    @Test
    void uninitializedAndProtectedRoomsRejectBeforeReadingCollection() {
        var properties = new AppProperties();
        var auth = mock(AuthController.class);
        var albums = mock(SavedAlbumsService.class);
        var api = mock(NeteaseMusicApiService.class);
        var controller = new SavedAlbumsController(auth, properties, albums, api);
        assertEquals(403, controller.saved(null, 0, 25).block().getStatusCode().value());
        when(auth.getRawPassword()).thenReturn("room");
        assertEquals(403, controller.saved("wrong", 0, 25).block().getStatusCode().value());
        verifyNoInteractions(albums, api);
    }

    @Test
    void noIdentityCanReadRemovedCookieAccountCategory() {
        var properties = new AppProperties();
        properties.setAdminPassword("admin");
        var auth = mock(AuthController.class);
        when(auth.getRawPassword()).thenReturn("room");
        var albums = mock(SavedAlbumsService.class);
        var api = mock(NeteaseMusicApiService.class);
        var controller = new SavedAlbumsController(auth, properties, albums, api);
        for (String password : List.of("room", "admin")) {
            var response = controller.saved(password, 0, 25).block();
            assertEquals(410, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
        }
        when(auth.getRawPassword()).thenReturn("");
        assertEquals(410, controller.saved(null, 0, 25).block().getStatusCode().value());
        verifyNoInteractions(albums, api);
    }
}

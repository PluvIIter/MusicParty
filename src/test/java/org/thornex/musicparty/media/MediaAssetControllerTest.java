package org.thornex.musicparty.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thornex.musicparty.service.LocalCacheService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaAssetControllerTest {
    @TempDir Path directory;

    @Test void validTicketSupportsRangeSuffixAndRejectsMultipleRanges() throws Exception {
        Path file = directory.resolve("track.mp3"); Files.write(file, "0123456789".getBytes());
        var delivery = mock(MediaDeliveryService.class); var cache = mock(LocalCacheService.class);
        when(delivery.ticket("ticket")).thenReturn(new MediaDeliveryService.Ticket("stub", "asset", 0, 0, Instant.now().plusSeconds(100)));
        when(cache.localPath("asset")).thenReturn(file); when(cache.pin("asset")).thenReturn(() -> {});
        var entry = new LocalCacheService.CacheEntry(); entry.setMimeType("audio/mpeg"); when(cache.getCacheEntry("asset")).thenReturn(entry);
        var controller = new MediaAssetController(delivery, cache);
        var request = new MockHttpServletRequest("GET", "/media/ticket"); request.addHeader("Range", "bytes=2-5");
        var response = new MockHttpServletResponse(); controller.read("ticket", request, response);
        assertEquals(206, response.getStatus()); assertEquals("bytes 2-5/10", response.getHeader("Content-Range"));
        assertEquals("2345", response.getContentAsString()); assertEquals("private, no-store", response.getHeader("Cache-Control"));
        request = new MockHttpServletRequest("GET", "/media/ticket"); request.addHeader("Range", "bytes=-3");
        response = new MockHttpServletResponse(); controller.read("ticket", request, response); assertEquals("789", response.getContentAsString());
        request = new MockHttpServletRequest("GET", "/media/ticket"); request.addHeader("Range", "bytes=0-1,3-4");
        response = new MockHttpServletResponse(); controller.read("ticket", request, response); assertEquals(416, response.getStatus());
    }

    @Test void invalidRevokedTicketNeverLooksUpFile() throws Exception {
        var delivery = mock(MediaDeliveryService.class); var cache = mock(LocalCacheService.class);
        when(delivery.ticket("old")).thenThrow(new IllegalArgumentException());
        var response = new MockHttpServletResponse();
        new MediaAssetController(delivery, cache).read("old", new MockHttpServletRequest(), response);
        assertEquals(403, response.getStatus()); verifyNoInteractions(cache);
    }
}

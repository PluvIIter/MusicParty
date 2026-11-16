package org.thornex.musicparty.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.service.LocalCacheService;

import java.io.RandomAccessFile;
import java.util.regex.Pattern;

@RestController
public class MediaAssetController {
    private final MediaDeliveryService delivery;
    private final LocalCacheService cache;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    public MediaAssetController(MediaDeliveryService delivery, LocalCacheService cache) {
        this.delivery = delivery;
        this.cache = cache;
    }

    @GetMapping("/media/{token}")
    public void read(@PathVariable String token, HttpServletRequest request, HttpServletResponse response) throws Exception {
        MediaDeliveryService.Ticket ticket;
        try { ticket = delivery.ticket(token); }
        catch (RuntimeException error) { response.sendError(403); return; }
        var path = cache.localPath(ticket.assetKey());
        if (path == null) { response.sendError(404); return; }
        try (var pin = cache.pin(ticket.assetKey()); var file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            long first = 0, last = length - 1;
            String range = request.getHeader("Range");
            if (range != null) {
                var match = RANGE.matcher(range);
                try {
                    if (!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty())) throw new IllegalArgumentException();
                    if (match.group(1).isEmpty()) first = Math.max(0, length - Long.parseLong(match.group(2)));
                    else {
                        first = Long.parseLong(match.group(1));
                        if (!match.group(2).isEmpty()) last = Math.min(last, Long.parseLong(match.group(2)));
                    }
                    if (first > last || first >= length) throw new IllegalArgumentException();
                } catch (IllegalArgumentException error) {
                    response.setHeader("Content-Range", "bytes */" + length); response.sendError(416); return;
                }
                response.setStatus(206);
                response.setHeader("Content-Range", "bytes " + first + "-" + last + "/" + length);
            }
            var entry = cache.getCacheEntry(ticket.assetKey());
            response.setContentType(entry.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : entry.getMimeType());
            response.setHeader("Accept-Ranges", "bytes"); response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff"); response.setContentLengthLong(last - first + 1);
            if (request.getMethod().equals("HEAD")) return;
            file.seek(first);
            byte[] buffer = new byte[64 * 1024];
            long remaining = last - first + 1;
            while (remaining > 0) {
                int count = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) break;
                response.getOutputStream().write(buffer, 0, count); remaining -= count;
            }
        }
    }
}

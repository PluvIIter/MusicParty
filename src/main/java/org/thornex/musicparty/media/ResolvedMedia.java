package org.thornex.musicparty.media;

import org.thornex.musicparty.source.ResourceKey;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ResolvedMedia(ResourceKey resource, String representationKey, String url,
                            String mimeType, String extension, Instant expiresAt,
                            Map<String, String> headers, boolean browserSafe, String timelineKey) {
    public ResolvedMedia(ResourceKey resource, String representationKey, String url, String mimeType,
                         String extension, Instant expiresAt, Map<String, String> headers, boolean browserSafe) {
        this(resource, representationKey, url, mimeType, extension, expiresAt, headers, browserSafe, representationKey);
    }

    public ResolvedMedia {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(representationKey);
        Objects.requireNonNull(timelineKey);
        Objects.requireNonNull(url);
        headers = Map.copyOf(headers);
    }
}

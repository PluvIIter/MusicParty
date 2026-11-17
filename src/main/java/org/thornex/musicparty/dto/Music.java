package org.thornex.musicparty.dto;

import java.util.List;

public record Music(
        String id,
        String name,
        List<String> artists,
        String albumName,
        long duration, // in milliseconds
        String platform,
        String coverUrl,
        String externalUrl
) {
    public Music(String id, String name, List<String> artists, long duration, String platform,
                 String coverUrl, String externalUrl) {
        this(id, name, artists, null, duration, platform, coverUrl, externalUrl);
    }
}


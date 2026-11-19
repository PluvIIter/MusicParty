package org.thornex.musicparty.dto;

import java.util.List;

public record PlayableMusic(
        String id,
        String name,
        List<String> artists,
        String albumName,
        long duration,
        String platform,
        String url, // The actual playable URL for the audio
        String coverUrl,
        String kind,
        String externalUrl
) {
    public PlayableMusic(String id, String name, List<String> artists, long duration, String platform,
                         String url, String coverUrl, String kind, String externalUrl) {
        this(id, name, artists, null, duration, platform, url, coverUrl, kind, externalUrl);
    }
}


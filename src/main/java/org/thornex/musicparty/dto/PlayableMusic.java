package org.thornex.musicparty.dto;

import java.util.List;

public record PlayableMusic(
        String id,
        String name,
        List<String> artists,
        long duration,
        String platform,
        String url, // The actual playable URL for the audio
        String coverUrl,
        boolean needsProxy,
        String kind,
        String externalUrl
) {
    public PlayableMusic(String id, String name, List<String> artists, long duration, String platform,
                         String url, String coverUrl, boolean needsProxy) {
        this(id, name, artists, duration, platform, url, coverUrl, needsProxy, "SONG", null);
    }

    public PlayableMusic(String id, String name, List<String> artists, long duration, String platform,
                         String url, String coverUrl, boolean needsProxy, String kind) {
        this(id, name, artists, duration, platform, url, coverUrl, needsProxy, kind, null);
    }
}


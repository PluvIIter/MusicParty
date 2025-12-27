package org.thornex.musicparty.dto;

import java.util.List;

public record Playlist(
        String id,
        String name,
        String coverImgUrl,
        int trackCount,
        String platform
) {}

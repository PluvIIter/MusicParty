package org.thornex.musicparty.dto;

import java.util.List;

public record Music(
        String id,
        String name,
        List<String> artists,
        long duration, // in milliseconds
        String platform,
        String coverUrl
) {}


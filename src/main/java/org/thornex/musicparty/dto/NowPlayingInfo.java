package org.thornex.musicparty.dto;

import java.util.List;
import java.util.Set;

public record NowPlayingInfo(
        PlayableMusic music,
        long currentPosition,
        String enqueuedById,
        String enqueuedByName,
        Set<String> likedUserIds,
        List<Long> likeMarkers
) {}

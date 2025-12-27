package org.thornex.musicparty.dto;

import java.util.List;

public record NowPlayingInfo(
        PlayableMusic music,
        long startTimeMillis,
        String enqueuedBy
) {}

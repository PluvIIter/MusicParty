package org.thornex.musicparty.dto;

import java.util.List;

public record PlayerState(
        NowPlayingInfo nowPlaying,
        List<MusicQueueItem> queue,
        boolean isShuffle,
        List<UserSummary> onlineUsers,
        boolean isPaused,
        boolean isLoading
) {}